package io.github.eladimany.spindle.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.eladimany.spindle.core.model.Album
import io.github.eladimany.spindle.core.model.Artist
import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.data.library.ArtworkRepository
import io.github.eladimany.spindle.data.library.LibraryRepository
import io.github.eladimany.spindle.playback.PlaybackController
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * What a search looks through — set by the tab it was opened from: Tracks searches only
 * tracks, Artists only artists, Albums only albums. [ALL] (Folders, Playlists) searches
 * everything.
 */
enum class SearchScope(val placeholder: String) {
    ALL("Search artists, albums, tracks"),
    TRACKS("Search tracks"),
    ARTISTS("Search artists"),
    ALBUMS("Search albums"),
}

data class SearchResults(
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val tracks: List<Track> = emptyList(),
) {
    val isEmpty: Boolean get() = artists.isEmpty() && albums.isEmpty() && tracks.isEmpty()
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val libraryRepository: LibraryRepository,
    private val playbackController: PlaybackController,
    private val artworkRepository: ArtworkRepository,
) : ViewModel() {

    val scope: SearchScope = savedStateHandle.get<String>("scope")
        ?.let { name -> SearchScope.entries.firstOrNull { it.name == name } }
        ?: SearchScope.ALL

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val currentTrackId: StateFlow<Long?> = playbackController.currentTrackId

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val results: StateFlow<SearchResults> = _query
        .debounce(250)
        .distinctUntilChanged()
        .flatMapLatest { q ->
            val trimmed = q.trim()
            if (trimmed.isEmpty()) {
                flowOf(SearchResults())
            } else {
                val all = scope == SearchScope.ALL
                combine(
                    if (all || scope == SearchScope.ARTISTS) libraryRepository.searchArtists(trimmed) else flowOf(emptyList()),
                    if (all || scope == SearchScope.ALBUMS) libraryRepository.searchAlbums(trimmed) else flowOf(emptyList()),
                    if (all || scope == SearchScope.TRACKS) libraryRepository.search(trimmed) else flowOf(emptyList()),
                ) { artists, albums, tracks -> SearchResults(artists, albums, tracks) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults())

    fun setQuery(text: String) {
        _query.value = text
    }

    fun playTrack(track: Track) {
        val all = results.value.tracks
        val startIndex = all.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        playbackController.playTracks(all, startIndex)
    }

    fun addToQueue(tracks: List<Track>) = playbackController.addToQueue(tracks)

    suspend fun artworkUriFor(track: Track): String? = artworkRepository.artworkUriFor(track)
    suspend fun artworkUriForAlbum(album: Album): String? = artworkRepository.artworkUriForAlbum(album)
}
