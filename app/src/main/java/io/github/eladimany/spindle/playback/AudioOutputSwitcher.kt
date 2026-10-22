package io.github.eladimany.spindle.playback

import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.eladimany.spindle.core.model.BluOsPlayer
import io.github.eladimany.spindle.core.model.OutputCapabilities
import io.github.eladimany.spindle.core.model.PlaybackState
import io.github.eladimany.spindle.core.model.QueueItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import io.github.eladimany.spindle.data.server.NetworkAddress
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

sealed interface OutputTarget {
    data object Local : OutputTarget
    data class Node(val player: BluOsPlayer) : OutputTarget
}

/**
 * The [AudioOutput] [PlaybackController] actually holds — see
 * `PlaybackBindingsModule`. Forwards every call to whichever concrete
 * output is currently active, and owns switching between them, so
 * `PlaybackController` stays exactly as ignorant of "which output" as
 * CLAUDE.md's architecture section says it should be; only this class and
 * whatever UI calls [switchTo] know `NodeOutput`/`LocalOutput` exist at all.
 */
@Singleton
class AudioOutputSwitcher @Inject constructor(
    private val localOutput: LocalOutput,
    private val nodeOutput: NodeOutput,
    @ApplicationContext private val context: Context,
) : AudioOutput {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _target = MutableStateFlow<OutputTarget>(OutputTarget.Local)
    val target: StateFlow<OutputTarget> = _target.asStateFlow()

    private fun outputFor(target: OutputTarget): AudioOutput = when (target) {
        OutputTarget.Local -> localOutput
        is OutputTarget.Node -> nodeOutput
    }

    private val active: AudioOutput get() = outputFor(_target.value)

    // Forwarded live from whichever output is active — flatMapLatest resubscribes
    // to the new output's own state/volume the instant `_target` changes, rather
    // than this class ever caching a value that could go stale across a switch.
    override val state: StateFlow<PlaybackState> = _target
        .flatMapLatest { outputFor(it).state }
        .stateIn(scope, SharingStarted.Eagerly, PlaybackState.Idle)

    override val volume: StateFlow<Int> = _target
        .flatMapLatest { outputFor(it).volume }
        .stateIn(scope, SharingStarted.Eagerly, 100)

    override val capabilities: OutputCapabilities get() = active.capabilities

    init {
        // The Node turned off (user, 2026-10-22: the app still showed it, and play did
        // nothing). Unreachable for NODE_GONE_AFTER_MS while the phone is on WiFi → back to
        // the phone, paused where it was. The clock restarts on the phone's own network
        // changes, so a WiFi hop (35 s at the user's home) still recovers on the Node.
        scope.launch {
            nodeOutput.unreachableSince.collectLatest { since ->
                if (since == null) return@collectLatest
                delay((since + NODE_GONE_AFTER_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                if (_target.value is OutputTarget.Node && NetworkAddress.wlanIpv4(context) != null) {
                    fallBackToPhone(play = false)
                }
            }
        }
    }

    // Play or resume while the Node isn't answering: the phone plays it now, rather
    // than the button doing nothing until the Node comes back.
    override suspend fun play(item: QueueItem) {
        if (nodeIsGone()) {
            // Play on the item the Node was waiting to resume (shown as buffering): carry on
            // from where it was, not from the top.
            if (nodeOutput.resumePoint()?.first?.id == item.id) {
                fallBackToPhone(play = true)
                return
            }
            fallBackToPhone(play = false)
        }
        active.play(item)
    }

    override suspend fun prepare(item: QueueItem, positionMs: Long) = active.prepare(item, positionMs)
    override suspend fun pause() = active.pause()

    override suspend fun resume() {
        if (nodeIsGone()) {
            fallBackToPhone(play = true)
            return
        }
        active.resume()
        // The Node went off moments ago and this very command found out: still play now.
        if (nodeIsGone()) fallBackToPhone(play = true)
    }

    private fun nodeIsGone() = _target.value is OutputTarget.Node && nodeOutput.isUnreachable

    /** Disconnects the Node and continues on the phone from the same item and position. */
    private suspend fun fallBackToPhone(play: Boolean) {
        val node = (_target.value as? OutputTarget.Node)?.player ?: return
        val resume = nodeOutput.resumePoint()
        Timber.i("%s isn't answering — switching to this phone (resume=%s, play=%s)", node.name, resume?.first?.track?.title, play)
        nodeOutput.disconnect()
        _target.value = OutputTarget.Local
        resume?.let { (item, positionMs) ->
            localOutput.prepare(item, positionMs)
            if (play) localOutput.resume()
        }
        Toast.makeText(context, "${node.name} isn't answering — switched to this phone", Toast.LENGTH_LONG).show()
    }
    override suspend fun stop() = active.stop()
    override suspend fun seek(seconds: Int) = active.seek(seconds)
    override suspend fun setVolume(percent: Int) = active.setVolume(percent)

    /**
     * Switches the active output, carrying over whatever was playing:
     * stops the old output, connects the new one, and re-`play()`s (then
     * `seek()`s back to roughly the same position) the item that was
     * loaded — a fresh `/Play?url=`/`MediaItem` load either way, not a
     * seamless handoff, since neither output can hand the other a mid-stream
     * decode. A no-op if [newTarget] is already active.
     */
    suspend fun switchTo(newTarget: OutputTarget) {
        val previousTarget = _target.value
        if (newTarget == previousTarget) return

        val wasPaused = state.value is PlaybackState.Paused
        val (resumeItem, resumePositionMs) = when (val current = state.value) {
            // Playing.positionMs is a snapshot as of capturedAtMs, not a live
            // value (see PlaybackState's own doc) — neither output updates it
            // every second, only on real state changes. Reading it bare here
            // meant switching outputs minutes into a track jumped back to
            // wherever the last state change happened to be (found on-device,
            // testing against the real Node) — interpolate the same way the
            // seek bar does.
            is PlaybackState.Playing -> current.item to (current.positionMs + (System.currentTimeMillis() - current.capturedAtMs))
                .coerceIn(0, current.durationMs.coerceAtLeast(0))
            is PlaybackState.Paused -> current.item to current.positionMs
            is PlaybackState.Buffering -> current.item to 0L
            else -> null to 0L
        }

        if (newTarget is OutputTarget.Node) {
            // NodeOutput.connect() disconnects any previous Node session itself —
            // covers Node-to-Node switches without this class managing that too.
            nodeOutput.connect(newTarget.player)
        }
        active.stop()
        if (previousTarget is OutputTarget.Node && newTarget !is OutputTarget.Node) {
            nodeOutput.disconnect()
        }
        _target.value = newTarget

        if (resumeItem != null && wasPaused) {
            // Paused stays paused on the new output (it used to start playing there).
            active.prepare(resumeItem, resumePositionMs)
        } else if (resumeItem != null) {
            active.play(resumeItem)
            val resumeSeconds = (resumePositionMs / 1000).toInt()
            if (resumeSeconds > 1) active.seek(resumeSeconds)
        }
    }

    private companion object {
        /** Unreachable this long, with the phone on WiFi the whole time, means the Node is off. */
        const val NODE_GONE_AFTER_MS = 30_000L
    }
}
