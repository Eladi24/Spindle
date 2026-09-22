package io.github.eladimany.spindle.ui.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.eladimany.spindle.core.model.Folder
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
class FolderDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val libraryRepository: LibraryRepository,
    private val artworkRepository: ArtworkRepository,
    private val playbackController: PlaybackController,
) : ViewModel() {

    private val folderId: Long = checkNotNull(savedStateHandle["folderId"])

    private val _folder = MutableStateFlow<Folder?>(null)
    val folder: StateFlow<Folder?> = _folder.asStateFlow()

    val tracks: StateFlow<List<Track>> = libraryRepository.tracksForFolder(folderId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val currentTrackId: StateFlow<Long?> = playbackController.currentTrackId

    init {
        viewModelScope.launch {
            _folder.value = libraryRepository.folderById(folderId)
        }
    }

    fun playTrack(track: Track) {
        val all = tracks.value
        val startIndex = all.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        playbackController.playTracks(all, startIndex)
    }

    suspend fun artworkUriFor(track: Track): String? = artworkRepository.artworkUriFor(track)
}
