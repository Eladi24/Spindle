package io.github.eladimany.spindle.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.eladimany.spindle.core.model.Album
import io.github.eladimany.spindle.data.covers.AlbumCoverFinder
import io.github.eladimany.spindle.data.library.ArtworkRepository
import io.github.eladimany.spindle.data.library.LibraryRepository
import io.github.eladimany.spindle.data.library.toDomain
import io.github.eladimany.spindle.data.prefs.SettingsRepository
import io.github.eladimany.spindle.playback.PlaybackController
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class AlbumsViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val artworkRepository: ArtworkRepository,
    private val settings: SettingsRepository,
    private val controller: PlaybackController,
    private val coverFinder: AlbumCoverFinder,
) : ViewModel() {
    init {
        // Tries each album's embedded cover once (in the background) to count the missing ones.
        coverFinder.checkMissing()
    }

    val coverScan: StateFlow<AlbumCoverFinder.Scan> = coverFinder.scan

    /** How many albums have no cover, when the notice should show; else 0 (mockup 9B). */
    val missingCoverNotice: StateFlow<Int> = combine(coverFinder.missing, settings.coverNoticeDismissedAt) { missing, dismissedAt ->
        val count = missing?.size ?: 0
        if (count > dismissedAt) count else 0
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val missingCoverCount: StateFlow<Int> = coverFinder.missing.map { it?.size ?: 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun dismissCoverNotice() {
        viewModelScope.launch { settings.dismissCoverNotice(coverFinder.missing.value?.size ?: 0) }
    }

    val folderGranted: StateFlow<Boolean> = coverFinder.folderTree.map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val folderPass: StateFlow<AlbumCoverFinder.FolderPass> = coverFinder.folderPass

    fun grantFolder(treeUri: android.net.Uri) = coverFinder.grantFolder(treeUri)

    fun findAllCovers() = coverFinder.findAll()
    fun stopFindingCovers() = coverFinder.stop()
    fun rejectCover(album: Album) = coverFinder.reject(album)
    fun closeCoverScan() = coverFinder.resetScan()

    val albumCount: StateFlow<Int?> = libraryRepository.albumCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val albums: Flow<PagingData<Album>> =
        Pager(PagingConfig(pageSize = 60, enablePlaceholders = false)) {
            libraryRepository.albumsPagingSource()
        }.flow.map { it.map { entity -> entity.toDomain() } }.cachedIn(viewModelScope)

    /** The flip views need the whole list (a few hundred rows) to place covers by index. */
    val allAlbums: StateFlow<List<Album>> = libraryRepository.albums
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val flowView: StateFlow<Boolean?> = settings.albumsFlowView
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The album the flip view was on — kept here so it survives opening an album and turning the phone. */
    var flowAlbumId: Long? = null

    fun setFlowView(flow: Boolean) {
        viewModelScope.launch { settings.setAlbumsFlowView(flow) }
    }

    enum class PlayMode { IN_ORDER, SHUFFLE, SMART }

    fun play(album: Album, mode: PlayMode) {
        viewModelScope.launch {
            val tracks = libraryRepository.tracksForAlbum(album.id).first()
            if (tracks.isEmpty()) return@launch
            when (mode) {
                PlayMode.IN_ORDER -> controller.playTracks(tracks)
                PlayMode.SHUFFLE -> controller.playTracksShuffled(tracks)
                PlayMode.SMART -> controller.playTracksSmartShuffled(tracks)
            }
        }
    }

    suspend fun artworkUriForAlbum(album: Album): String? = artworkRepository.artworkUriForAlbum(album)
}
