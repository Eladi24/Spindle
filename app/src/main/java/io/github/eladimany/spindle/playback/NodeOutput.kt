package io.github.eladimany.spindle.playback

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.eladimany.spindle.core.model.BluOsPlayer
import io.github.eladimany.spindle.core.model.OutputCapabilities
import io.github.eladimany.spindle.core.model.PlaybackState
import io.github.eladimany.spindle.core.model.QueueItem
import io.github.eladimany.spindle.data.bluos.BluOsClient
import io.github.eladimany.spindle.data.bluos.BluOsStatus
import io.github.eladimany.spindle.data.bluos.IcyName
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
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

private const val LONG_POLL_TIMEOUT_SECONDS = 90
private const val RETRY_DELAY_MS = 5_000L

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
        serverAddress = mediaHttpServer.start(host)
        // Volume matters even before anything's playing (unlike the /Status
        // loop, which only makes sense once play() has loaded something),
        // so this starts right away rather than lazily.
        ensureSyncStatusLoop()
    }

    fun disconnect() {
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
        _state.value = PlaybackState.Idle
    }

    override suspend fun play(item: QueueItem) {
        val target = player ?: return
        val address = serverAddress ?: return
        currentItem = item
        lastKnownSecs = 0
        lastKnownTotalSeconds = null
        _state.value = PlaybackState.Buffering(item)

        val icyName = IcyName.forTrack(item.track.artistName, item.track.title)
        val token = tokenRegistry.tokenFor(item.track.uri, icyName)
        bluOsClient.playUrl(target, address.urlFor(token))
        ensureStatusLoop()
    }

    override suspend fun pause() {
        player?.let { bluOsClient.pause(it) }
    }

    override suspend fun resume() {
        player?.let { bluOsClient.resume(it) }
    }

    override suspend fun stop() {
        player?.let { bluOsClient.stop(it) }
        currentItem = null
        _state.value = PlaybackState.Idle
    }

    /** Only takes effect when the Node's own last-reported status had `canSeek=1`. */
    override suspend fun seek(seconds: Int) {
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
        when {
            status.isHijacked -> {
                _state.value = PlaybackState.Error(item, "Node switched to ${status.title1 ?: "another input"}")
            }
            status.state == "stream" -> {
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
                _state.value = if (NodeTrackEnd.isNaturalEnd(lastKnownSecs, lastKnownTotalSeconds)) {
                    PlaybackState.Ended(item)
                } else {
                    // Someone pressed stop in the BluOS app, mid-track — not our queue's business to advance.
                    PlaybackState.Idle
                }
            }
            else -> Unit // Unrecognized state — keep the last known one rather than guess.
        }
    }
}
