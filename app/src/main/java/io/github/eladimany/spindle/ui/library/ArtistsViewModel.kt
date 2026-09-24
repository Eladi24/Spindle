package io.github.eladimany.spindle.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.eladimany.spindle.core.model.Artist
import io.github.eladimany.spindle.data.artists.ArtistArtworkRepository
import io.github.eladimany.spindle.data.library.LibraryRepository
import io.github.eladimany.spindle.data.library.toDomain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import io.github.eladimany.spindle.ui.components.SectionAnchor
import io.github.eladimany.spindle.ui.components.sectionAnchors
import javax.inject.Inject

@HiltViewModel
class ArtistsViewModel @Inject constructor(
    libraryRepository: LibraryRepository,
    private val artistArtworkRepository: ArtistArtworkRepository,
) : ViewModel() {
    // Placeholders + a jump threshold so the alphabet rail can jump straight to a distant
    // letter — same setup as TracksViewModel.
    val artists: Flow<PagingData<Artist>> =
        Pager(PagingConfig(pageSize = 60, enablePlaceholders = true, jumpThreshold = 180)) {
            libraryRepository.artistsPagingSource()
        }.flow.map { it.map { entity -> entity.toDomain() } }.cachedIn(viewModelScope)

    // Positions into LibraryRepository.artists, which uses the same nameSortKey order as the pager.
    val sectionIndex: StateFlow<List<SectionAnchor>> = libraryRepository.artists
        .map { artists -> sectionAnchors(artists) { it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val artistCount: StateFlow<Int?> = libraryRepository.artistCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val artworkByArtistId: StateFlow<Map<Long, String>> = artistArtworkRepository.artworkByArtistId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _fetchingIds = MutableStateFlow<Set<Long>>(emptySet())
    val fetchingIds: StateFlow<Set<Long>> = _fetchingIds.asStateFlow()

    /** Only ever called from an explicit tap on that artist's row — see ArtistArtworkRepository's own doc comment. */
    fun fetchArtwork(artist: Artist) {
        if (artist.id in _fetchingIds.value) return
        _fetchingIds.value = _fetchingIds.value + artist.id
        viewModelScope.launch {
            try {
                artistArtworkRepository.fetch(artist.id, artist.name)
            } catch (e: Exception) {
                Timber.w(e, "Artist artwork fetch failed for %s", artist.name)
            } finally {
                _fetchingIds.value = _fetchingIds.value - artist.id
            }
        }
    }
}
