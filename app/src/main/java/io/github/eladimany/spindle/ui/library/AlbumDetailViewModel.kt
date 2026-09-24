package io.github.eladimany.spindle.ui.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.eladimany.spindle.core.model.Album
import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.data.library.ArtworkRepository
import io.github.eladimany.spindle.data.library.LibraryRepository
import io.github.eladimany.spindle.playback.PlaybackController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val libraryRepository: LibraryRepository,
    private val artworkRepository: ArtworkRepository,
    private val playbackController: PlaybackController,
) : ViewModel() {

    private val albumId: Long = checkNotNull(savedStateHandle["albumId"])

    private val _album = MutableStateFlow<Album?>(null)
    val album: StateFlow<Album?> = _album.asStateFlow()

    val tracks: StateFlow<List<Track>> = libraryRepository.tracksForAlbum(albumId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val currentTrackId: StateFlow<Long?> = playbackController.currentTrackId

    init {
        viewModelScope.launch {
            _album.value = libraryRepository.albumById(albumId)
        }
    }

    fun playTrack(track: Track) {
        val all = tracks.value
        val startIndex = all.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        playbackController.playTracks(all, startIndex)
    }

    fun playAll() {
        playbackController.playTracks(tracks.value)
    }

    fun playShuffled() {
        if (tracks.value.isNotEmpty()) playbackController.playTracksShuffled(tracks.value)
    }

    fun playSmartShuffled() {
        if (tracks.value.isNotEmpty()) playbackController.playTracksSmartShuffled(tracks.value)
    }

    fun addToQueue(tracks: List<Track>) = playbackController.addToQueue(tracks)

    suspend fun artworkUriForAlbum(album: Album): String? = artworkRepository.artworkUriForAlbum(album)
    suspend fun artworkUriFor(track: Track): String? = artworkRepository.artworkUriFor(track)
}
