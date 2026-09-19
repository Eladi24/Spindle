package io.github.eladimany.spindle.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.eladimany.spindle.core.model.PlaybackState
import io.github.eladimany.spindle.core.model.QueueItem
import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.data.library.ArtworkRepository
import io.github.eladimany.spindle.data.library.LibraryRepository
import io.github.eladimany.spindle.playback.PlaybackController
import io.github.eladimany.spindle.playback.QueueManager
import io.github.eladimany.spindle.playback.QueueState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val controller: PlaybackController,
    private val artworkRepository: ArtworkRepository,
    libraryRepository: LibraryRepository,
) : ViewModel() {

    val playbackState: StateFlow<PlaybackState> = controller.playbackState
    val queueState: StateFlow<QueueState> = controller.queueState

    val tracks: StateFlow<List<Track>> = libraryRepository.tracks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun playTrack(track: Track) {
        val library = tracks.value
        val startIndex = library.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        val queueItems = library.map { QueueItem(id = "q${it.id}", track = it) }
        controller.playQueue(queueItems, startIndex)
    }

    fun togglePlayPause() = controller.togglePlayPause()
    fun next() = controller.next()
    fun previous() = controller.previous()
    fun seekTo(seconds: Int) = controller.seekTo(seconds)
    fun jumpTo(queueItemId: String) = controller.jumpTo(queueItemId)
    fun removeFromQueue(queueItemId: String) = controller.remove(queueItemId)
    fun moveInQueue(from: Int, to: Int) = controller.move(from, to)
    fun setShuffled(enabled: Boolean) = controller.setShuffled(enabled)

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
