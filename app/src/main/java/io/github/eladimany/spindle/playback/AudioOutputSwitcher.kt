package io.github.eladimany.spindle.playback

import io.github.eladimany.spindle.core.model.BluOsPlayer
import io.github.eladimany.spindle.core.model.OutputCapabilities
import io.github.eladimany.spindle.core.model.PlaybackState
import io.github.eladimany.spindle.core.model.QueueItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    override suspend fun play(item: QueueItem) = active.play(item)
    override suspend fun pause() = active.pause()
    override suspend fun resume() = active.resume()
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

        val (resumeItem, resumePositionMs) = when (val current = state.value) {
            is PlaybackState.Playing -> current.item to current.positionMs
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

        if (resumeItem != null) {
            active.play(resumeItem)
            val resumeSeconds = (resumePositionMs / 1000).toInt()
            if (resumeSeconds > 1) active.seek(resumeSeconds)
        }
    }
}
