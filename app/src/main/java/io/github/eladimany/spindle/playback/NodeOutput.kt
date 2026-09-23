package io.github.eladimany.spindle.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.eladimany.spindle.core.model.BluOsPlayer
import io.github.eladimany.spindle.core.model.OutputCapabilities
import io.github.eladimany.spindle.core.model.PlaybackState
import io.github.eladimany.spindle.core.model.QueueItem
import io.github.eladimany.spindle.data.bluos.BluOsClient
import io.github.eladimany.spindle.data.bluos.BluOsStatus
import io.github.eladimany.spindle.data.bluos.IcyName
import io.github.eladimany.spindle.data.bluos.NodeOwnership
import io.github.eladimany.spindle.data.bluos.NodeTrackEnd
import io.github.eladimany.spindle.data.server.MediaHttpServer
import io.github.eladimany.spindle.data.server.NetworkAddress
import io.github.eladimany.spindle.data.server.ServerAddress
import io.github.eladimany.spindle.data.server.TokenRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

private const val LONG_POLL_TIMEOUT_SECONDS = 90
private const val RETRY_DELAY_MS = 5_000L
private const val TAKEOVER_GRACE_MS = 10_000L
/** A mid-track "stop" this recent is assumed to be the IP change killing the stream, not a user. */
private const val IP_CHANGE_STOP_WINDOW_MS = 15_000L

/**
 * [AudioOutput] backed by a BluOS Node: serves the current track over
 * [MediaHttpServer] and drives it with [BluOsClient]'s `/Play?url=`
 * mechanism — the app owns the queue, the Node just decodes (hard
 * constraint #5, see CLAUDE.md). **Not yet wired into [PlaybackController]
 * or exercised against a real Node** — that's the output-switcher task,
 * which also needs to add [connect] as an explicit user action (there's no
 * such concept in the [AudioOutput] interface, which only knows "the
 * current output", not "which Node").
 *
 * All mutable state here (`currentItem`, `lastEtag`, the last-known
 * `secs`/`totlen`) is only ever touched from [scope], a single
 * `Dispatchers.Main.immediate` scope — same confinement [PlaybackController]
 * already uses to call this class's own suspend functions — so the
 * long-poll loop and direct calls like [play]/[stop] never race each other
 * despite one running continuously in the background.
 */
@Singleton
class NodeOutput @Inject constructor(
    private val bluOsClient: BluOsClient,
    private val mediaHttpServer: MediaHttpServer,
    private val tokenRegistry: TokenRegistry,
    private val streamingLocks: NodeStreamingLocks,
    @ApplicationContext private val context: Context,
) : AudioOutput {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var statusJob: Job? = null
    private var syncStatusJob: Job? = null

    private var player: BluOsPlayer? = null
    private var serverAddress: ServerAddress? = null
    private var currentItem: QueueItem? = null
    private var lastEtag: String? = null
    private var lastSyncEtag: String? = null
    private var lastKnownSecs: Int = 0
    private var lastKnownTotalSeconds: Int? = null

    // Ownership of the Node for the current item — see NodeOwnership. Once
    // takenOver, status is ignored until our next play(); the item shows as
    // Paused and resume() takes the Node back at lastKnownSecs.
    private var currentToken: String? = null
    private var playStartedAtMs = 0L
    private var seenOurs = false
    private var takenOver = false

    // Phone IP change / WiFi drop recovery: the server is bound to one WLAN address,
    // so a new address (or a dead listen socket) means restart it and re-issue the
    // current track. reloadOnResume
    // covers the change happening while paused — the Node's paused stream points
    // at the dead URL, so resume() must replay rather than /Play.
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var reloadOnResume = false
    private var stoppedMidTrackAtMs: Long? = null
    // One network change fires several callbacks, and the rebind suspends —
    // without this, two of them could both see the old address and both restart.
    private val serverMutex = Mutex()

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    // Kept in sync by syncStatusLoop (long-polled /SyncStatus) — setVolume()
    // also writes it optimistically for instant UI feedback, but the next
    // poll response is the actual source of truth.
    private val _volume = MutableStateFlow(100)
    override val volume: StateFlow<Int> = _volume.asStateFlow()

    // Static, not derived from the current track's own /Status canSeek —
    // the AudioOutput interface only exposes a fixed val, not a StateFlow,
    // so per-track seek support can't be represented yet. isGapless is a
    // confirmed hard fact (bluos-api.md: "gapless is not achievable this
    // way"), not a simplification.
    override val capabilities = OutputCapabilities(canSeek = true, canSetVolume = true, isGapless = false)

    init {
        // Idle/Paused/Ended/Error (incl. takeover and disconnect) all release.
        scope.launch {
            _state.collect { streamingLocks.setHeld(it is PlaybackState.Playing || it is PlaybackState.Buffering) }
        }
    }

    /**
     * Starts serving from this device and targets [target]. Throws if the
     * phone isn't on WiFi — [MediaHttpServer] must bind to a real WLAN
     * address, never `0.0.0.0` (see its own docs). Whoever calls this
     * (the not-yet-built output switcher) is expected to catch that and
     * tell the user, not treat it as a bug.
     */
    suspend fun connect(target: BluOsPlayer) {
        disconnect()
        val host = NetworkAddress.wlanIpv4(context)
            ?: error("Not on WiFi — can't serve files to a BluOS Node")
        player = target
        serverAddress = withContext(Dispatchers.IO) { mediaHttpServer.start(host) }
        registerNetworkCallback()
        // Volume matters even before anything's playing (unlike the /Status
        // loop, which only makes sense once play() has loaded something),
        // so this starts right away rather than lazily.
        ensureSyncStatusLoop()
    }

    fun disconnect() {
        unregisterNetworkCallback()
        statusJob?.cancel()
        statusJob = null
        syncStatusJob?.cancel()
        syncStatusJob = null
        mediaHttpServer.stop()
        tokenRegistry.clear()
        player = null
        serverAddress = null
        currentItem = null
        lastEtag = null
        lastSyncEtag = null
        lastKnownSecs = 0
        lastKnownTotalSeconds = null
        currentToken = null
        seenOurs = false
        takenOver = false
        reloadOnResume = false
        stoppedMidTrackAtMs = null
        _state.value = PlaybackState.Idle
    }

    override suspend fun play(item: QueueItem) {
        val target = player ?: return
        if (serverAddress == null) return
        // Cheap re-check so a track change never builds a URL on a stale address,
        // even if the network callback hasn't fired yet.
        moveServerToCurrentAddress()
        val address = serverAddress ?: return
        currentItem = item
        lastKnownSecs = 0
        lastKnownTotalSeconds = null
        _state.value = PlaybackState.Buffering(item)

        val icyName = IcyName.forTrack(item.track.artistName, item.track.title)
        val token = tokenRegistry.tokenFor(item.track.uri, icyName)
        currentToken = token
        playStartedAtMs = SystemClock.elapsedRealtime()
        seenOurs = false
        takenOver = false
        reloadOnResume = false
        stoppedMidTrackAtMs = null
        bluOsClient.playUrl(target, address.urlFor(token))
        ensureStatusLoop()
    }

    override suspend fun pause() {
        if (takenOver) return // Already not ours — don't pause someone else's music.
        player?.let { bluOsClient.pause(it) }
    }

    override suspend fun resume() {
        if (takenOver || reloadOnResume) {
            // Take the Node back / reload from the new address, where we left off.
            currentItem?.let { replayAt(it, lastKnownSecs) }
            return
        }
        player?.let { bluOsClient.resume(it) }
    }

    override suspend fun stop() {
        if (!takenOver) player?.let { bluOsClient.stop(it) }
        takenOver = false
        currentItem = null
        _state.value = PlaybackState.Idle
    }

    /** Only takes effect when the Node's own last-reported status had `canSeek=1`. */
    override suspend fun seek(seconds: Int) {
        if (takenOver) {
            // Don't seek their stream — just move where "take it back" will resume.
            val item = currentItem ?: return
            lastKnownSecs = seconds
            _state.value = PlaybackState.Paused(item, seconds * 1000L, (lastKnownTotalSeconds ?: 0) * 1000L)
            return
        }
        player?.let { bluOsClient.seek(it, seconds) }
    }

    override suspend fun setVolume(percent: Int) {
        val target = player ?: return
        val clamped = percent.coerceIn(0, 100)
        bluOsClient.setVolume(target, clamped)
        _volume.value = clamped
    }

    private fun ensureStatusLoop() {
        if (statusJob?.isActive == true) return
        statusJob = scope.launch { statusLoop() }
    }

    private suspend fun statusLoop() {
        while (true) {
            val target = player ?: return
            val status = try {
                bluOsClient.status(target, timeoutSeconds = LONG_POLL_TIMEOUT_SECONDS, etag = lastEtag)
            } catch (e: Exception) {
                Timber.w(e, "BluOS status long-poll failed")
                delay(RETRY_DELAY_MS)
                continue
            }
            lastEtag = status.etag
            applyStatus(status)
        }
    }

    private fun ensureSyncStatusLoop() {
        if (syncStatusJob?.isActive == true) return
        syncStatusJob = scope.launch { syncStatusLoop() }
    }

    private suspend fun syncStatusLoop() {
        while (true) {
            val target = player ?: return
            val sync = try {
                bluOsClient.syncStatus(target, timeoutSeconds = LONG_POLL_TIMEOUT_SECONDS, etag = lastSyncEtag)
            } catch (e: Exception) {
                Timber.w(e, "BluOS sync-status long-poll failed")
                delay(RETRY_DELAY_MS)
                continue
            }
            lastSyncEtag = sync.etag
            sync.volume?.let { _volume.value = it }
        }
    }

    private fun applyStatus(status: BluOsStatus) {
        val item = currentItem ?: return
        val token = currentToken ?: return
        if (takenOver) return
        val msSincePlay = SystemClock.elapsedRealtime() - playStartedAtMs
        val verdict = NodeOwnership.classify(status, token, seenOurs, msSincePlay, TAKEOVER_GRACE_MS)
        if (verdict == NodeOwnership.Verdict.TAKEN_OVER) {
            Timber.i("Node taken over (state=%s service=%s) — stepping back", status.state, status.serviceType)
            takenOver = true
            _state.value = PlaybackState.Paused(item, lastKnownSecs * 1000L, (lastKnownTotalSeconds ?: 0) * 1000L)
            return
        }
        if (verdict == NodeOwnership.Verdict.OURS) seenOurs = true
        when {
            status.state == "stream" -> {
                // UNKNOWN here = a foreign stream inside the post-/Play grace window; not ours to display.
                if (verdict != NodeOwnership.Verdict.OURS) return
                lastKnownSecs = status.secs
                lastKnownTotalSeconds = status.totalSeconds
                _state.value = PlaybackState.Playing(item, status.secs * 1000L, (status.totalSeconds ?: 0) * 1000L)
            }
            // Unverified against real hardware — Phase 0 only ever observed
            // "stream"/"stop". Worst case if this guess is wrong: pausing
            // just doesn't show as Paused here (falls to the else branch
            // below), not a crash or a wrong action.
            status.state == "pause" -> {
                _state.value = PlaybackState.Paused(item, lastKnownSecs * 1000L, (lastKnownTotalSeconds ?: 0) * 1000L)
            }
            status.state == "stop" -> {
                // Before our stream has shown up, "stop" is most likely the Node's
                // leftover pre-/Play state — ignore it inside the grace window.
                if (!seenOurs && msSincePlay < TAKEOVER_GRACE_MS) return
                _state.value = if (seenOurs && NodeTrackEnd.isNaturalEnd(lastKnownSecs, lastKnownTotalSeconds)) {
                    PlaybackState.Ended(item)
                } else {
                    // Someone pressed stop in the BluOS app, mid-track — not our queue's business
                    // to advance. (Or our stream died from an IP change — see onNetworkChanged.)
                    stoppedMidTrackAtMs = SystemClock.elapsedRealtime()
                    PlaybackState.Idle
                }
            }
            else -> Unit // Unrecognized state — keep the last known one rather than guess.
        }
    }

    private suspend fun replayAt(item: QueueItem, seconds: Int) {
        play(item)
        if (seconds > 1) seek(seconds)
    }

    /**
     * Rebinds [MediaHttpServer] if the phone's WLAN address changed **or the server
     * stopped listening** — returns true if it did. The second case is real: found on
     * the A73 that WiFi off/on comes back with the *same* IP but the listen socket
     * gone (connection refused), so comparing addresses alone isn't enough.
     * Ktor's start/stop block (stop waits up to ~1.2 s), so all of it runs on IO.
     */
    private suspend fun moveServerToCurrentAddress(): Boolean = serverMutex.withLock {
        val address = serverAddress ?: return false
        // Off WiFi: nothing to bind to yet — the callback fires again when it's back.
        val host = NetworkAddress.wlanIpv4(context) ?: return false
        val newAddress = withContext(Dispatchers.IO) {
            when {
                host != address.host ->
                    Timber.i("Phone IP changed %s -> %s — restarting media server", address.host, host)
                !isListening(address) ->
                    Timber.i("Media server on %s:%d stopped listening — restarting", address.host, address.port)
                else -> return@withContext null
            }
            mediaHttpServer.start(host)
        } ?: return false
        serverAddress = newAddress
        true
    }

    private fun isListening(address: ServerAddress): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(address.host, address.port), 500) }
        true
    } catch (e: Exception) {
        false
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            // Called on ConnectivityThread — hop to scope, where all state lives.
            override fun onAvailable(network: Network) {
                scope.launch { onNetworkChanged() }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                scope.launch { onNetworkChanged() }
            }
        }
        cm.registerDefaultNetworkCallback(callback)
        networkCallback = callback
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
    }

    private suspend fun onNetworkChanged() {
        if (!moveServerToCurrentAddress()) return
        val item = currentItem ?: return
        if (takenOver) return // resume() replays anyway, on the new address.
        when (val state = _state.value) {
            is PlaybackState.Playing -> {
                val positionMs = state.positionMs + (System.currentTimeMillis() - state.capturedAtMs)
                replayAt(item, (positionMs.coerceIn(0, state.durationMs.coerceAtLeast(0)) / 1000).toInt())
            }
            is PlaybackState.Buffering -> replayAt(item, 0)
            is PlaybackState.Idle -> {
                val stoppedAt = stoppedMidTrackAtMs
                if (stoppedAt != null && SystemClock.elapsedRealtime() - stoppedAt < IP_CHANGE_STOP_WINDOW_MS) {
                    replayAt(item, lastKnownSecs)
                }
            }
            is PlaybackState.Paused -> reloadOnResume = true
            // Ended: PlaybackController is about to play() the next track, which
            // already uses the new address. Error: nothing to recover.
            else -> Unit
        }
    }
}
