package io.github.eladimany.spindle.ui.player

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.eladimany.spindle.core.model.PlaybackState
import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.data.library.ArtworkRepository
import io.github.eladimany.spindle.playback.AudioOutputSwitcher
import io.github.eladimany.spindle.playback.OutputTarget
import io.github.eladimany.spindle.playback.PlaybackController
import io.github.eladimany.spindle.playback.QueueManager
import io.github.eladimany.spindle.playback.QueueState
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Shared playback UI state — the mini-player bar and Now Playing screen both use the
 * same (Activity-scoped) instance, since there's only ever one active queue.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val controller: PlaybackController,
    private val artworkRepository: ArtworkRepository,
    audioOutputSwitcher: AudioOutputSwitcher,
) : ViewModel() {

    val playbackState: StateFlow<PlaybackState> = controller.playbackState
    val queueState: StateFlow<QueueState> = controller.queueState
    val volume: StateFlow<Int> = controller.volume
    // Only the output picker actually needs to know NodeOutput/LocalOutput exist —
    // PlaybackController itself never does (see its own doc comment) — so this reads
    // AudioOutputSwitcher directly rather than routing "current target" through it.
    val outputTarget: StateFlow<OutputTarget> = audioOutputSwitcher.target

    fun togglePlayPause() = controller.togglePlayPause()
    fun next() = controller.next()
    fun previous() = controller.previous()
    fun seekTo(seconds: Int) = controller.seekTo(seconds)
    fun jumpTo(queueItemId: String) = controller.jumpTo(queueItemId)
    fun removeFromQueue(queueItemId: String) = controller.remove(queueItemId)
    fun moveInQueue(from: Int, to: Int) = controller.move(from, to)
    fun setShuffled(enabled: Boolean) = controller.setShuffled(enabled)
    fun setVolume(percent: Int) = controller.setVolume(percent)

    suspend fun artworkUriFor(track: Track): String? = artworkRepository.artworkUriFor(track)

    fun cycleRepeatMode() {
        val next = when (queueState.value.repeatMode) {
            QueueManager.RepeatMode.OFF -> QueueManager.RepeatMode.ALL
            QueueManager.RepeatMode.ALL -> QueueManager.RepeatMode.ONE
            QueueManager.RepeatMode.ONE -> QueueManager.RepeatMode.OFF
        }
        controller.setRepeatMode(next)
    }
}
