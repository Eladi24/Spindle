package io.github.eladimany.spindle.ui.playlists

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.eladimany.spindle.core.model.Playlist
import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.data.library.ArtworkRepository
import io.github.eladimany.spindle.data.playlists.PlaylistRepository
import io.github.eladimany.spindle.playback.PlaybackController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: PlaylistRepository,
    private val artworkRepository: ArtworkRepository,
    private val playbackController: PlaybackController,
) : ViewModel() {

    private val playlistId: Long = checkNotNull(savedStateHandle["playlistId"])

    private val _playlist = MutableStateFlow<Playlist?>(null)
    val playlist: StateFlow<Playlist?> = _playlist.asStateFlow()

    val tracks: StateFlow<List<Track>> = repository.tracksFor(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val currentTrackId: StateFlow<Long?> = playbackController.currentTrackId

    init {
        viewModelScope.launch { _playlist.value = repository.getById(playlistId) }
    }

    fun playTrack(track: Track) {
        val all = tracks.value
        val startIndex = all.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        playbackController.playTracks(all, startIndex)
    }

    suspend fun artworkUriFor(track: Track): String? = artworkRepository.artworkUriFor(track)
}
