package io.github.eladimany.spindle.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.core.model.TrackSort
import io.github.eladimany.spindle.data.library.ArtworkRepository
import io.github.eladimany.spindle.data.library.LibraryRepository
import io.github.eladimany.spindle.data.library.toDomain
import io.github.eladimany.spindle.playback.PlaybackController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import io.github.eladimany.spindle.ui.components.SectionAnchor
import io.github.eladimany.spindle.ui.components.sectionAnchors
import io.github.eladimany.spindle.ui.components.countLabel
import io.github.eladimany.spindle.ui.components.durationLabel
import javax.inject.Inject

@HiltViewModel
class TracksViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val playbackController: PlaybackController,
    private val artworkRepository: ArtworkRepository,
) : ViewModel() {

    private val _sort = MutableStateFlow(TrackSort.TITLE)
    val sort: StateFlow<TrackSort> = _sort.asStateFlow()

    val currentTrackId: StateFlow<Long?> = playbackController.currentTrackId
    val isPlaying: StateFlow<Boolean> = playbackController.isPlaying

    /** "1,284 tracks · 86 h" under the tab title. */
    val summary: StateFlow<String?> = libraryRepository.libraryStats
        .map { "${countLabel(it.trackCount, "track")} · ${durationLabel(it.totalDurationMs, hoursOnly = true)}" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // A-Z + '#' fast-scroll anchors. Only meaningful while sorted by title — the screen
    // hides the index strip otherwise, since these positions won't line up with any other order.
    val sectionIndex: StateFlow<List<SectionAnchor>> = libraryRepository.tracks
        .map { tracks -> sectionAnchors(tracks) { it.title } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Placeholders + a jump threshold let the paged list load pages around a fast-scroll
    // target directly instead of paginating through everything in between.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val tracks: Flow<PagingData<Track>> = _sort.flatMapLatest { sort ->
        Pager(PagingConfig(pageSize = 60, enablePlaceholders = true, jumpThreshold = 180)) {
            libraryRepository.tracksPagingSource(sort)
        }.flow.map { it.map { entity -> entity.toDomain() } }
    }.cachedIn(viewModelScope)

    fun setSort(sort: TrackSort) {
        _sort.value = sort
    }

    fun playTrack(track: Track) {
        viewModelScope.launch {
            val all = libraryRepository.allTracksSorted(sort.value)
            val startIndex = all.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            playbackController.playTracks(all, startIndex)
        }
    }

    /** Plays the whole library in the current sort order, from the top. */
    fun playAll() {
        viewModelScope.launch {
            val all = libraryRepository.allTracksSorted(sort.value)
            if (all.isNotEmpty()) playbackController.playTracks(all)
        }
    }

    /** Plays the whole library shuffled, starting immediately. */
    fun shuffleAll() {
        viewModelScope.launch {
            val all = libraryRepository.allTracksSorted(sort.value)
            if (all.isEmpty()) return@launch
            playbackController.playTracksShuffled(all)
        }
    }

    /** Like [shuffleAll], ordered by smart shuffle (history + the user's rules). */
    fun smartShuffleAll() {
        viewModelScope.launch {
            val all = libraryRepository.allTracksSorted(sort.value)
            if (all.isEmpty()) return@launch
            playbackController.playTracksSmartShuffled(all)
        }
    }

    fun addToQueue(tracks: List<Track>) = playbackController.addToQueue(tracks)

    suspend fun artworkUriFor(track: Track): String? = artworkRepository.artworkUriFor(track)
}
