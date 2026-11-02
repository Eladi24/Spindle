package io.github.eladimany.spindle.ui.library

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.eladimany.spindle.core.model.Album
import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.data.covers.AlbumCoverFinder
import io.github.eladimany.spindle.data.covers.CoverCandidate
import io.github.eladimany.spindle.data.covers.FolderImage
import io.github.eladimany.spindle.data.library.ArtworkRepository
import io.github.eladimany.spindle.data.library.ArtworkRevision
import io.github.eladimany.spindle.data.library.LibraryRepository
import io.github.eladimany.spindle.playback.PlaybackController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val libraryRepository: LibraryRepository,
    private val artworkRepository: ArtworkRepository,
    private val playbackController: PlaybackController,
    private val coverFinder: AlbumCoverFinder,
) : ViewModel() {

    private val albumId: Long = checkNotNull(savedStateHandle["albumId"])

    private val _album = MutableStateFlow<Album?>(null)
    val album: StateFlow<Album?> = _album.asStateFlow()

    val tracks: StateFlow<List<Track>> = libraryRepository.tracksForAlbum(albumId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val currentTrackId: StateFlow<Long?> = playbackController.currentTrackId

    /** Null until known; false shows "Find cover" (mockup 9A). Re-checked when a cover is saved. */
    private val _hasCover = MutableStateFlow<Boolean?>(null)
    val hasCover: StateFlow<Boolean?> = _hasCover.asStateFlow()

    sealed interface CoverLookup {
        data object Idle : CoverLookup
        data object Searching : CoverLookup
        data class Showing(val candidates: List<CoverCandidate>, val index: Int, val saving: Boolean = false, val error: String? = null) : CoverLookup
        data class Failed(val message: String) : CoverLookup
    }

    private val _coverLookup = MutableStateFlow<CoverLookup>(CoverLookup.Idle)
    val coverLookup: StateFlow<CoverLookup> = _coverLookup.asStateFlow()

    init {
        viewModelScope.launch {
            _album.value = libraryRepository.albumById(albumId)
        }
        viewModelScope.launch {
            ArtworkRevision.value.collect {
                val album = _album.value ?: libraryRepository.albumById(albumId) ?: return@collect
                _hasCover.value = artworkRepository.artworkUriForAlbum(album) != null
            }
        }
    }

    // 10B: the "Album cover" sheet — this album's folder images, Deezer, or any picture.
    private val _coverOptionsOpen = MutableStateFlow(false)
    val coverOptionsOpen: StateFlow<Boolean> = _coverOptionsOpen.asStateFlow()

    private val _folderImages = MutableStateFlow<List<FolderImage>?>(null)
    val folderImages: StateFlow<List<FolderImage>?> = _folderImages.asStateFlow()

    val folderGranted: StateFlow<Boolean> = coverFinder.folderTree.map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        // Load (again) when the sheet opens or the folder has just been granted.
        viewModelScope.launch {
            combine(_coverOptionsOpen, coverFinder.folderTree) { open, tree -> open to tree }.collect { (open, tree) ->
                val album = _album.value
                if (!open || tree == null || album == null) return@collect
                _folderImages.value = null
                _folderImages.value = coverFinder.folderImages(album, tree)
            }
        }
    }

    fun openCoverOptions() {
        _coverOptionsOpen.value = true
    }

    fun closeCoverOptions() {
        _coverOptionsOpen.value = false
    }

    fun grantFolder(treeUri: Uri) = coverFinder.grantFolder(treeUri)

    /** A folder image or a picture from the photo picker. */
    fun useImage(image: Uri) {
        val album = _album.value ?: return
        viewModelScope.launch {
            if (coverFinder.useImage(album, image)) {
                _coverOptionsOpen.value = false
            } else {
                _coverLookup.value = CoverLookup.Failed("Couldn\u2019t read that picture")
            }
        }
    }

    fun findCoverOnDeezer() {
        _coverOptionsOpen.value = false
        findCover()
    }

    fun findCover() {
        val album = _album.value ?: return
        _coverLookup.value = CoverLookup.Searching
        viewModelScope.launch {
            _coverLookup.value = try {
                val found = coverFinder.candidates(album)
                if (found.isEmpty()) CoverLookup.Failed("Deezer has no cover for this album") else CoverLookup.Showing(found, 0)
            } catch (e: IOException) {
                CoverLookup.Failed("Couldn't reach Deezer — check the connection")
            }
        }
    }

    fun useShownCover() {
        val album = _album.value ?: return
        val showing = _coverLookup.value as? CoverLookup.Showing ?: return
        _coverLookup.value = showing.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                coverFinder.use(album, showing.candidates[showing.index])
                _coverLookup.value = CoverLookup.Idle
            } catch (e: IOException) {
                _coverLookup.value = showing.copy(saving = false, error = "Couldn't download it — try again")
            }
        }
    }

    fun notThisCover() {
        val showing = _coverLookup.value as? CoverLookup.Showing ?: return
        _coverLookup.value = if (showing.index + 1 < showing.candidates.size) {
            showing.copy(index = showing.index + 1, error = null)
        } else {
            CoverLookup.Failed("No other match on Deezer")
        }
    }

    fun closeCoverLookup() {
        _coverLookup.value = CoverLookup.Idle
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
