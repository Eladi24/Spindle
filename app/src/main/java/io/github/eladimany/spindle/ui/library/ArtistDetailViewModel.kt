package io.github.eladimany.spindle.ui.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.eladimany.spindle.core.model.Album
import io.github.eladimany.spindle.core.model.Artist
import io.github.eladimany.spindle.data.library.ArtworkRepository
import io.github.eladimany.spindle.data.library.LibraryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ArtistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val libraryRepository: LibraryRepository,
    private val artworkRepository: ArtworkRepository,
) : ViewModel() {

    private val artistId: Long = checkNotNull(savedStateHandle["artistId"])

    private val _artist = MutableStateFlow<Artist?>(null)
    val artist: StateFlow<Artist?> = _artist.asStateFlow()

    val albums: StateFlow<List<Album>> = libraryRepository.albumsForArtist(artistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            _artist.value = libraryRepository.artistById(artistId)
        }
    }

    suspend fun artworkUriForAlbum(album: Album): String? = artworkRepository.artworkUriForAlbum(album)
}
