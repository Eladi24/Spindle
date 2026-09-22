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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val tracks: Flow<PagingData<Track>> = _sort.flatMapLatest { sort ->
        Pager(PagingConfig(pageSize = 60, enablePlaceholders = false)) {
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

    /** Plays the whole library shuffled, starting immediately. */
    fun shuffleAll() {
        viewModelScope.launch {
            val all = libraryRepository.allTracksSorted(sort.value)
            if (all.isEmpty()) return@launch
            playbackController.playTracks(all, 0)
            playbackController.setShuffled(true)
        }
    }

    suspend fun artworkUriFor(track: Track): String? = artworkRepository.artworkUriFor(track)
}
