package io.github.eladimany.spindle.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.eladimany.spindle.core.model.PlaybackState
import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.data.db.dao.TrackDao
import io.github.eladimany.spindle.data.history.trackKey
import io.github.eladimany.spindle.data.library.ArtworkRepository
import io.github.eladimany.spindle.data.library.toDomain
import io.github.eladimany.spindle.data.prefs.SettingsRepository
import io.github.eladimany.spindle.di.IoDispatcher
import io.github.eladimany.spindle.playback.AudioOutputSwitcher
import io.github.eladimany.spindle.playback.MoreLikeThis
import io.github.eladimany.spindle.playback.OutputTarget
import io.github.eladimany.spindle.playback.PlaybackController
import io.github.eladimany.spindle.playback.QueueManager
import io.github.eladimany.spindle.playback.QueueState
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared playback UI state — the mini-player bar and Now Playing screen both use the
 * same (Activity-scoped) instance, since there's only ever one active queue.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val controller: PlaybackController,
    private val artworkRepository: ArtworkRepository,
    private val settings: SettingsRepository,
    private val trackDao: TrackDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
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
    fun restoreToQueue(queueItemId: String, index: Int) = controller.restore(queueItemId, index)

    /** Tracks swiped right in the queue — they come up sooner in the next smart shuffle. */
    val boostedTrackKeys: StateFlow<Set<Long>> = settings.boostedTrackKeys
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun isBoosted(track: Track): Boolean = trackKey(track) in boostedTrackKeys.value

    fun toggleBoost(track: Track) {
        val key = trackKey(track)
        viewModelScope.launch { settings.setBoosted(key, key !in boostedTrackKeys.value) }
    }
    fun moveInQueue(from: Int, to: Int) = controller.move(from, to)

    /** The songs the last "more like this" placed — the queue labels and tints them. */
    data class LikeGroup(val seedTitle: String, val reasons: Map<String, String>, val placed: PlaybackController.Placed)

    private val _likeGroup = MutableStateFlow<LikeGroup?>(null)
    val likeGroup: StateFlow<LikeGroup?> = _likeGroup.asStateFlow()

    /**
     * Swipe right in the queue: up to [MoreLikeThis.COUNT] similar songs right after
     * [anchorId] — the rest of the queue first, then the library. Returns how many.
     */
    suspend fun moreLikeThis(anchorId: String): Int {
        val items = queueState.value.items
        val anchorIndex = items.indexOfFirst { it.id == anchorId }
        if (anchorIndex == -1) return 0
        val seed = items[anchorIndex].track
        val picks = withContext(ioDispatcher) {
            val library = trackDao.getAllByTitle().map { it.toDomain() }
            MoreLikeThis.pick(seed, items.drop(anchorIndex + 1), library, items.mapTo(HashSet()) { it.track.id })
        }
        if (picks.isEmpty()) return 0
        val placed = controller.placeAfter(anchorId, picks)
        _likeGroup.value = LikeGroup(seed.title, placed.itemIds.zip(picks.map { it.reason }).toMap(), placed)
        return picks.size
    }

    fun undoMoreLikeThis() {
        val group = _likeGroup.value ?: return
        controller.undoPlaceAfter(group.placed)
        _likeGroup.value = null
    }
    /** Off → Shuffle → Smart → Off. Returns the new mode, for the button's pop-up label. */
    fun cycleShuffleMode(): QueueManager.ShuffleMode {
        val next = when (queueState.value.shuffleMode) {
            QueueManager.ShuffleMode.OFF -> QueueManager.ShuffleMode.SHUFFLE
            QueueManager.ShuffleMode.SHUFFLE -> QueueManager.ShuffleMode.SMART
            QueueManager.ShuffleMode.SMART -> QueueManager.ShuffleMode.OFF
        }
        controller.setShuffleMode(next)
        return next
    }
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
