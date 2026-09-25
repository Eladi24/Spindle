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
import io.github.eladimany.spindle.core.model.elapsedMs
import io.github.eladimany.spindle.data.bluos.BluOsClient
import io.github.eladimany.spindle.data.bluos.BluOsDiscovery
import io.github.eladimany.spindle.data.bluos.BluOsStatus
import io.github.eladimany.spindle.data.bluos.IcyName
import io.github.eladimany.spindle.data.bluos.NodeOwnership
import io.github.eladimany.spindle.data.bluos.NodeTrackEnd
import io.github.eladimany.spindle.data.server.MediaHttpServer
import io.github.eladimany.spindle.data.server.NetworkAddress
import io.github.eladimany.spindle.data.server.ServerAddress
import io.github.eladimany.spindle.data.server.TokenRegistry
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

private const val LONG_POLL_TIMEOUT_SECONDS = 90
private const val RETRY_DELAY_MS = 5_000L
private const val MAX_RETRY_DELAY_MS = 30_000L
/** Consecutive failed /SyncStatus polls before the Node counts as unreachable. */
private const val OUTAGE_AFTER_FAILURES = 2
private const val REDISCOVERY_WINDOW_MS = 60_000L
private const val REDISCOVERY_PAUSE_MS = 60_000L
/** After this long unreachable, stop auto-resuming and show Paused (releases the streaming locks). */
private const val OUTAGE_GIVE_UP_MS = 10 * 60_000L
private const val TAKEOVER_GRACE_MS = 10_000L
/** How long the UI keeps showing a seek's target while the Node's reported secs catch up. */
private const val SEEK_SETTLE_MS = 8_000L
/** Measured: the Node lands ~+2 s past a requested seek (bluos-api.md). */
private const val SEEK_LANDING_SLACK_SECONDS = 5
/** A held seek is dropped if the Node hasn't reported canSeek=1 this long after /Play. */
private const val PENDING_SEEK_TIMEOUT_MS = 6_000L
/** A mid-track "stop" this recent is assumed to be the IP change killing the stream, not a user. */
private const val IP_CHANGE_STOP_WINDOW_MS = 15_000L

/**
 * [AudioOutput] backed by a BluOS Node: serves the current track over
 * [MediaHttpServer] and drives it with [BluOsClient]'s `/Play?url=`
 * mechanism — the app owns the queue, the Node just decodes (hard
 * constraint #5, see CLAUDE.md). [connect]/[disconnect] are driven by
 * [AudioOutputSwitcher]; the [AudioOutput] interface itself has no notion
 * of "which Node".
 *
 * **Never throws from an [AudioOutput] call** — a Node that's rebooting, off,
 * or at a new IP must not crash the app (it did: pause with the Node gone
 * crashed [PlaybackController]'s scope). Failures go through [nodeCommand]
 * into outage handling instead — see [enterOutage].
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
    private val discovery: BluOsDiscovery,
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

    // Seek sync. A /Play?seek sent right after /Play?url — before the Node reports
    // our stream with canSeek=1 — is the likely cause of a real glitch (user,
    // 2026-09-23: garbled noise on switching to the Node, then playback from 0:00
    // while the app showed +8 s). So a seek before the stream is seekable is held
    // in pendingSeekSeconds and sent from applyStatus; after any seek, the UI keeps
    // showing seekTarget until the Node's secs land near it (or SEEK_SETTLE_MS).
    private var streamCanSeek = false
    private var pendingSeekSeconds: Int? = null
    // A seek made while paused — sent with the next resume, not before: on the real Node
    // /Play?seek also starts playback, so seeking a paused stream un-paused it (2026-09-25).
    private var seekOnResumeSeconds: Int? = null
    private var lastLoggedNodeState: String? = null
    private var seekTarget: Int? = null
    private var seekIssuedAtMs = 0L

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

    // Node reachability. While outage != null the Node isn't answering: status is
    // ignored, rediscovery looks for it at a new IP (matched by MAC from
    // /SyncStatus, else name), and once it answers recoverFromOutage() replays the
    // resume point unless the Node is still playing our stream.
    private class Outage(val resumeItem: QueueItem?, val resumeSeconds: Int)
    private var outage: Outage? = null
    private var syncFailures = 0
    private var nodeMac: String? = null
    private var nodeName: String? = null
    private var rediscoveryJob: Job? = null
    private var recoveryJob: Job? = null

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
        rediscoveryJob?.cancel()
        rediscoveryJob = null
        recoveryJob?.cancel()
        recoveryJob = null
        outage = null
        syncFailures = 0
        nodeMac = null
        nodeName = null
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
        resetSeekSync()
        reloadOnResume = false
        stoppedMidTrackAtMs = null
        _state.value = PlaybackState.Idle
    }

    private fun resetSeekSync() {
        streamCanSeek = false
        pendingSeekSeconds = null
        seekOnResumeSeconds = null
        seekTarget = null
    }

    override suspend fun play(item: QueueItem) {
        playInternal(item)
    }

    /** Returns false if the Node couldn't be reached (already handed to outage handling). */
    private suspend fun playInternal(item: QueueItem, resumeSeconds: Int = 0): Boolean {
        if (player == null || serverAddress == null) return false
        // Cheap re-check so a track change never builds a URL on a stale address,
        // even if the network callback hasn't fired yet.
        moveServerToCurrentAddress()
        val address = serverAddress ?: return false
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
        resetSeekSync()
        reloadOnResume = false
        stoppedMidTrackAtMs = null
        if (outage != null) {
            // Node known unreachable — don't wait out a timeout; resume here on recovery.
            outage = Outage(item, resumeSeconds)
            return false
        }
        val url = address.urlFor(token)
        if (!nodeCommand("play", Outage(item, resumeSeconds)) { bluOsClient.playUrl(it, url) }) return false
        ensureStatusLoop()
        return true
    }

    override suspend fun pause() {
        if (takenOver) return // Already not ours — don't pause someone else's music.
        if (outage == null && nodeCommand("pause") { bluOsClient.pause(it) }) return
        // Node unreachable: honour the pause here, so nothing auto-resumes when it's back.
        pauseAtResumePoint()
    }

    override suspend fun resume() {
        if (takenOver || reloadOnResume) {
            // Take the Node back / reload from the new address, where we left off.
            currentItem?.let { replayAt(it, lastKnownSecs) }
            return
        }
        seekOnResumeSeconds?.let { seconds ->
            // Paused and then moved: one /Play?seek both jumps there and resumes.
            seekOnResumeSeconds = null
            val item = currentItem ?: return
            if (!nodeCommand("seek") { bluOsClient.seek(it, seconds) }) return
            seekTarget = seconds
            seekIssuedAtMs = System.currentTimeMillis()
            _state.value = PlaybackState.Playing(item, seconds * 1000L, (lastKnownTotalSeconds ?: 0) * 1000L, seekIssuedAtMs)
            return
        }
        nodeCommand("resume") { bluOsClient.resume(it) }
    }

    override suspend fun stop() {
        if (!takenOver && outage == null) nodeCommand("stop") { bluOsClient.stop(it) }
        if (outage != null) outage = Outage(null, 0) // Stopped: nothing to resume on recovery.
        takenOver = false
        currentItem = null
        _state.value = PlaybackState.Idle
    }

    /**
     * The Node only honours `/Play?seek` once it reports our stream with
     * `canSeek=1` — before that (right after `/Play?url`) the seek is held and sent
     * by [applyStatus], and the item shows as Buffering meanwhile.
     */
    override suspend fun seek(seconds: Int) {
        if (takenOver) {
            // Don't seek their stream — just move where "take it back" will resume.
            val item = currentItem ?: return
            lastKnownSecs = seconds
            _state.value = PlaybackState.Paused(item, seconds * 1000L, (lastKnownTotalSeconds ?: 0) * 1000L)
            return
        }
        // Unreachable: just move the resume point rather than wait out a timeout.
        outage?.resumeItem?.let {
            outage = Outage(it, seconds)
            return
        }
        val item = currentItem ?: return
        lastKnownSecs = seconds
        if (_state.value is PlaybackState.Paused) {
            // Don't send it now — /Play?seek would start playback. Resume sends it.
            Timber.d("Paused: seek to %ds kept for resume", seconds)
            seekOnResumeSeconds = seconds
            _state.value = PlaybackState.Paused(item, seconds * 1000L, (lastKnownTotalSeconds ?: 0) * 1000L)
            return
        }
        if (!seenOurs || !streamCanSeek) {
            Timber.d("Seek to %ds held until the Node's stream is seekable", seconds)
            pendingSeekSeconds = seconds
            seekTarget = null
            _state.value = PlaybackState.Buffering(item)
            return
        }
        sendSeek(item, seconds)
    }

    private suspend fun sendSeek(item: QueueItem, seconds: Int) {
        if (!nodeCommand("seek") { bluOsClient.seek(it, seconds) }) return
        seekTarget = seconds
        seekIssuedAtMs = System.currentTimeMillis()
        // Show the target at once (the seek bar would otherwise snap back to the
        // Node's pre-seek secs until the next status); paused stays paused.
        val durationMs = (lastKnownTotalSeconds ?: 0) * 1000L
        _state.value = if (_state.value is PlaybackState.Paused) {
            PlaybackState.Paused(item, seconds * 1000L, durationMs)
        } else {
            PlaybackState.Playing(item, seconds * 1000L, durationMs, seekIssuedAtMs)
        }
    }

    override suspend fun setVolume(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        if (nodeCommand("volume") { bluOsClient.setVolume(it, clamped) }) _volume.value = clamped
    }

    /**
     * Runs one Node command. A failure is logged and treated as the Node being
     * unreachable — never thrown. [resumePoint] overrides what [enterOutage] would
     * infer from the current state (play() knows the intended item/position while
     * state only says Buffering).
     */
    private suspend fun nodeCommand(
        what: String,
        resumePoint: Outage? = null,
        block: suspend (BluOsPlayer) -> Unit,
    ): Boolean {
        val target = player ?: return false
        return try {
            block(target)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "BluOS %s failed", what)
            enterOutage(resumePoint)
            false
        }
    }

    /** 5 s, 10 s, 20 s, then 30 s between attempts. */
    private fun retryDelayMs(failures: Int): Long =
        (RETRY_DELAY_MS shl (failures - 1).coerceIn(0, 3)).coerceAtMost(MAX_RETRY_DELAY_MS)

    private fun ensureStatusLoop() {
        if (statusJob?.isActive == true) return
        statusJob = scope.launch { statusLoop() }
    }

    private suspend fun statusLoop() {
        var failures = 0
        while (true) {
            val target = player ?: return
            val status = try {
                bluOsClient.status(target, timeoutSeconds = LONG_POLL_TIMEOUT_SECONDS, etag = lastEtag)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "BluOS status long-poll failed")
                // Declaring an outage is the /SyncStatus loop's job (it always runs); this just backs off.
                delay(retryDelayMs(++failures))
                continue
            }
            failures = 0
            lastEtag = status.etag
            if (outage != null) {
                onNodeReachable()
                continue // recoverFromOutage decides what the Node's state means now.
            }
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "BluOS sync-status long-poll failed")
                if (++syncFailures >= OUTAGE_AFTER_FAILURES) enterOutage()
                delay(retryDelayMs(syncFailures))
                continue
            }
            syncFailures = 0
            lastSyncEtag = sync.etag
            sync.volume?.let { _volume.value = it }
            sync.mac?.let { nodeMac = it }
            sync.name?.let { nodeName = it }
            onNodeReachable()
        }
    }

    /**
     * The Node stopped answering. Remembers what to resume ([resumePoint], or from
     * state: Playing → interpolated position, Buffering → its item from 0, else
     * nothing), shows Buffering meanwhile, and starts looking for the Node at a new
     * address. Called from failed commands and from the /SyncStatus loop.
     */
    private fun enterOutage(resumePoint: Outage? = null) {
        if (outage != null) {
            if (resumePoint != null) outage = resumePoint
            return
        }
        val state = _state.value
        val pending = resumePoint ?: when (state) {
            is PlaybackState.Playing -> Outage(state.item, (interpolatedPositionMs(state) / 1000).toInt())
            is PlaybackState.Buffering -> Outage(state.item, 0)
            else -> Outage(null, 0)
        }
        outage = pending
        Timber.i("Node unreachable — waiting for it (resume=%s)", pending.resumeItem?.track?.title)
        if (pending.resumeItem != null && !takenOver) _state.value = PlaybackState.Buffering(pending.resumeItem)
        if (state is PlaybackState.Paused) reloadOnResume = true // It may come back rebooted.
        startRediscovery()
    }

    private fun onNodeReachable() {
        if (outage == null || recoveryJob?.isActive == true) return
        recoveryJob = scope.launch { recoverFromOutage() }
    }

    private suspend fun recoverFromOutage() {
        val target = player ?: return
        val pending = outage ?: return
        // One immediate (non-long-poll) status: did our stream survive the outage?
        val status = try {
            bluOsClient.status(target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return // Still flaky — the next successful poll tries again.
        }
        rediscoveryJob?.cancel()
        rediscoveryJob = null
        outage = null
        syncFailures = 0
        val token = currentToken
        val stillOurs = token != null && status.state == "stream" &&
            NodeOwnership.classify(status, token, seenOurs = true, msSincePlay = 0) == NodeOwnership.Verdict.OURS
        val item = pending.resumeItem
        Timber.i("Node reachable again (ourStreamSurvived=%s, resume=%s)", stillOurs, item?.track?.title)
        if (!stillOurs && item != null && !takenOver) replayAt(item, pending.resumeSeconds) else applyStatus(status)
    }

    /**
     * While unreachable, browse for BluOS players and adopt one that is the same
     * physical Node (MAC, else name) at a different address. Discovery needs
     * NEARBY_WIFI_DEVICES on 33+; without it this finds nothing and the loops just
     * keep retrying the old address.
     */
    private fun startRediscovery() {
        if (rediscoveryJob?.isActive == true) return
        val startedAtMs = SystemClock.elapsedRealtime()
        rediscoveryJob = scope.launch {
            // Bounded, for battery: discovery (with its multicast lock) in windows,
            // and after OUTAGE_GIVE_UP_MS stop auto-resuming — see pauseAtResumePoint.
            while (outage != null) {
                if (SystemClock.elapsedRealtime() - startedAtMs >= OUTAGE_GIVE_UP_MS) {
                    Timber.i("Node still unreachable after %d min — pausing", OUTAGE_GIVE_UP_MS / 60_000)
                    pauseAtResumePoint()
                    return@launch
                }
                val match = withTimeoutOrNull(REDISCOVERY_WINDOW_MS) {
                    var same: BluOsPlayer? = null
                    // firstOrNull, not first: the flow completes at once without the permission.
                    discovery.discover().firstOrNull { found ->
                        same = findSameNodeElsewhere(found)
                        same != null
                    }
                    same
                }
                if (match != null) {
                    adoptAddress(match)
                    return@launch
                }
                delay(REDISCOVERY_PAUSE_MS)
            }
        }
    }

    private suspend fun findSameNodeElsewhere(found: List<BluOsPlayer>): BluOsPlayer? {
        val current = player ?: return null
        return found.firstOrNull { candidate ->
            (candidate.host != current.host || candidate.port != current.port) && isSameNode(candidate)
        }
    }

    private fun adoptAddress(found: BluOsPlayer) {
        val current = player ?: return
        Timber.i("Node found at %s:%d (was %s:%d)", found.host, found.port, current.host, current.port)
        player = current.copy(host = found.host, port = found.port)
        // Restart both loops on the new address now rather than after their backoff;
        // the first successful poll triggers recoverFromOutage.
        lastEtag = null
        lastSyncEtag = null
        statusJob?.cancel()
        syncStatusJob?.cancel()
        ensureSyncStatusLoop()
        if (currentItem != null) ensureStatusLoop()
    }

    /**
     * During an outage, turn "resume when the Node is back" into a plain Paused at
     * the resume point — for a user pause, and for a long outage (Buffering holds
     * the WiFi/CPU locks, and music shouldn't blast whenever the Node reappears
     * hours later). Play then replays from there. The poll loops keep retrying.
     */
    private fun pauseAtResumePoint() {
        val pending = outage ?: return
        val item = pending.resumeItem ?: return
        outage = Outage(null, 0)
        lastKnownSecs = pending.resumeSeconds
        reloadOnResume = true
        _state.value = PlaybackState.Paused(item, pending.resumeSeconds * 1000L, (lastKnownTotalSeconds ?: 0) * 1000L)
    }

    private suspend fun isSameNode(candidate: BluOsPlayer): Boolean {
        val sync = try {
            bluOsClient.syncStatus(candidate)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        }
        val mac = nodeMac
        return if (mac != null) sync.mac.equals(mac, ignoreCase = true) else sync.name != null && sync.name == nodeName
    }

    private fun interpolatedPositionMs(state: PlaybackState.Playing): Long =
        (state.positionMs + (System.currentTimeMillis() - state.capturedAtMs)).coerceIn(0, state.durationMs.coerceAtLeast(0))

    private suspend fun applyStatus(status: BluOsStatus) {
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
        if (status.state != lastLoggedNodeState) {
            // Every change of the Node's own state — the only record of *why* Spindle's view
            // changed (an unexplained pause at the Node, 2026-09-25, left no trace without it).
            Timber.d("Node state %s -> %s at %ds (%s)", lastLoggedNodeState, status.state, status.secs, verdict)
            lastLoggedNodeState = status.state
        }
        when {
            status.state == "stream" -> {
                // UNKNOWN here = a foreign stream inside the post-/Play grace window; not ours to display.
                if (verdict != NodeOwnership.Verdict.OURS) return
                streamCanSeek = status.canSeek
                lastKnownTotalSeconds = status.totalSeconds
                val durationMs = (status.totalSeconds ?: 0) * 1000L
                pendingSeekSeconds?.let { target ->
                    if (status.canSeek) {
                        pendingSeekSeconds = null
                        Timber.d("Stream seekable — sending held seek to %ds", target)
                        sendSeek(item, target)
                        return
                    }
                    // Stay Buffering (not Playing from 0:00) until the held seek is sent —
                    // unless this stream never becomes seekable; then follow the Node.
                    if (msSincePlay < PENDING_SEEK_TIMEOUT_MS) return
                    Timber.w("Node never reported canSeek=1 — dropping the seek to %ds", target)
                    pendingSeekSeconds = null
                }
                seekTarget?.let { target ->
                    // The Node lands ~2 s past the target (bluos-api.md); anything else
                    // is a pre-seek status — keep showing the target until it settles.
                    val landed = status.secs in (target - 1)..(target + SEEK_LANDING_SLACK_SECONDS)
                    val expired = System.currentTimeMillis() - seekIssuedAtMs > SEEK_SETTLE_MS
                    if (!landed && !expired) return
                    if (!landed) Timber.w("Seek to %ds didn't land (Node at %ds) — trusting the Node", target, status.secs)
                    seekTarget = null
                }
                lastKnownSecs = status.secs
                _state.value = PlaybackState.Playing(item, status.secs * 1000L, durationMs)
            }
            // Unverified against real hardware — Phase 0 only ever observed
            // "stream"/"stop". Worst case if this guess is wrong: pausing
            // just doesn't show as Paused here (falls to the else branch
            // below), not a crash or a wrong action.
            status.state == "pause" -> {
                // The Node's secs is current here — lastKnownSecs is from the last *stream*
                // status, which can be minutes old (it only reports on changes). Unless a
                // paused seek is waiting to be sent: then that's where we are.
                if (seekOnResumeSeconds == null) lastKnownSecs = status.secs
                _state.value = PlaybackState.Paused(item, lastKnownSecs * 1000L, (lastKnownTotalSeconds ?: 0) * 1000L)
            }
            status.state == "stop" -> {
                // Before our stream has shown up, "stop" is most likely the Node's
                // leftover pre-/Play state — ignore it inside the grace window.
                if (!seenOurs && msSincePlay < TAKEOVER_GRACE_MS) return
                // Where playback had got to, not the last secs the Node reported: it only
                // reports on changes, so after a seek near the end its last secs can be
                // several seconds short and a natural end read as a stop (found at the Node).
                val positionSecs = ((_state.value.elapsedMs() ?: (lastKnownSecs * 1000L)) / 1000L).toInt()
                val naturalEnd = seenOurs && NodeTrackEnd.isNaturalEnd(positionSecs, lastKnownTotalSeconds)
                Timber.d(
                    "Node stopped at ~%ds of %ss (last reported %ds) -> %s",
                    positionSecs, lastKnownTotalSeconds, lastKnownSecs, if (naturalEnd) "track ended" else "stopped mid-track",
                )
                _state.value = if (naturalEnd) {
                    PlaybackState.Ended(item)
                } else {
                    // Stopped mid-track: from the BluOS app, the Node itself (seen at the real
                    // Node: pause then stop, e.g. powering off), or our stream dying on an IP
                    // change (see onNetworkChanged). Not our queue's business to advance —
                    // but keep the song: show it paused where it stopped, and play replays
                    // from there (it used to go Idle, and the whole player disappeared).
                    stoppedMidTrackAtMs = SystemClock.elapsedRealtime()
                    lastKnownSecs = positionSecs
                    reloadOnResume = true
                    PlaybackState.Paused(item, positionSecs * 1000L, (lastKnownTotalSeconds ?: 0) * 1000L)
                }
            }
            else -> Unit // Unrecognized state — keep the last known one rather than guess.
        }
    }

    private suspend fun replayAt(item: QueueItem, seconds: Int) {
        if (playInternal(item, seconds) && seconds > 1) seek(seconds)
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
            is PlaybackState.Playing -> replayAt(item, (interpolatedPositionMs(state) / 1000).toInt())
            is PlaybackState.Buffering -> replayAt(item, 0)
            is PlaybackState.Paused -> {
                // A mid-track stop just before the address changed is our stream dying,
                // not a user's stop: carry on by ourselves, as if nothing happened.
                val stoppedAt = stoppedMidTrackAtMs
                if (stoppedAt != null && SystemClock.elapsedRealtime() - stoppedAt < IP_CHANGE_STOP_WINDOW_MS) {
                    replayAt(item, lastKnownSecs)
                } else {
                    reloadOnResume = true
                }
            }
            // Ended: PlaybackController is about to play() the next track, which
            // already uses the new address. Error: nothing to recover.
            else -> Unit
        }
    }
}
