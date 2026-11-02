package io.github.eladimany.spindle.data.covers

import android.net.Uri
import io.github.eladimany.spindle.core.model.Album
import io.github.eladimany.spindle.data.db.dao.TrackDao
import io.github.eladimany.spindle.data.prefs.SettingsRepository
import io.github.eladimany.spindle.data.library.ArtworkRepository
import io.github.eladimany.spindle.data.library.LibraryRepository
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Covers for albums whose files have none (mockups 9A/9B/10A/10B, 2026-11-02). First
 * from image files in the album's folder, once the user has granted the music folder
 * ([findInFolders], on the phone, runs by itself after each check); then from Deezer —
 * one album on request with a confirm step ([candidates] + [use]), or the rest of the
 * library in the background ([findAll]). Jobs run on in this singleton's scope.
 */
@Singleton
class AlbumCoverFinder @Inject constructor(
    private val client: AlbumCoverClient,
    private val artwork: ArtworkRepository,
    private val library: LibraryRepository,
    private val folders: FolderCoverSource,
    private val settings: SettingsRepository,
    private val trackDao: TrackDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The granted music folder, if any. */
    val folderTree: StateFlow<Uri?> = settings.musicFolderTree.map { it?.let(Uri::parse) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    sealed interface FolderPass {
        data object Idle : FolderPass
        data object Running : FolderPass
        data class Done(val found: List<Album>) : FolderPass
    }

    private val _folderPass = MutableStateFlow<FolderPass>(FolderPass.Idle)
    val folderPass: StateFlow<FolderPass> = _folderPass.asStateFlow()
    private var folderJob: Job? = null

    sealed interface Scan {
        data object Idle : Scan
        data class Running(val done: Int, val total: Int, val found: List<Album>, val notFound: List<Album>) : Scan
        data class Finished(val found: List<Album>, val notFound: List<Album>, val error: String?) : Scan
    }

    private val _scan = MutableStateFlow<Scan>(Scan.Idle)
    val scan: StateFlow<Scan> = _scan.asStateFlow()

    // Albums whose files have no cover and that have none found yet. Null until checked —
    // that means trying each album's embedded art once, so it runs when Albums opens.
    private val _missing = MutableStateFlow<List<Album>?>(null)
    val missing: StateFlow<List<Album>?> = _missing.asStateFlow()

    private var checkJob: Job? = null
    private val rejected = mutableSetOf<Long>()
    private var scanJob: Job? = null

    fun checkMissing() {
        if (checkJob?.isActive == true) return
        checkJob = scope.launch {
            val albums = library.albums.first()
            _missing.value = albums.filter { artwork.artworkUriForAlbum(it) == null }
            // Local and read-only, so new albums get their folder covers without asking.
            if (settings.musicFolderTree.first() != null) findInFolders()
        }
    }

    /** After the folder picker: keep the grant, then look in the folders. */
    fun grantFolder(treeUri: Uri) {
        scope.launch {
            settings.setMusicFolderTree(treeUri.toString())
            findInFolders()
        }
    }

    /** Each album still missing a cover takes the likeliest image in its folder. */
    fun findInFolders() {
        if (folderJob?.isActive == true) return
        folderJob = scope.launch {
            val tree = settings.musicFolderTree.first()?.let(Uri::parse) ?: return@launch
            _folderPass.value = FolderPass.Running
            val found = mutableListOf<Album>()
            for (album in _missing.value.orEmpty()) {
                // A folder holding several albums (everything in one Music folder, as on the
                // A73) would hand its one image to all of them: only by hand there (10B).
                val sample = trackDao.firstTrackForAlbum(album.id) ?: continue
                if (trackDao.albumCountInFolder(sample.folderId) != 1) continue
                val best = folderImages(album, tree).firstOrNull() ?: continue
                val bytes = folders.read(best.uri) ?: continue
                artwork.saveDownloadedCover(album, bytes)
                found += album
            }
            _missing.value = _missing.value?.filterNot { a -> found.any { it.id == a.id } }
            _folderPass.value = FolderPass.Done(found)
        }
    }

    /** The images in [album]'s folder, likeliest first; empty without folder access. */
    suspend fun folderImages(album: Album, tree: Uri? = folderTree.value): List<FolderImage> {
        tree ?: return emptyList()
        val sample = trackDao.firstTrackForAlbum(album.id) ?: return emptyList()
        return folders.imagesFor(tree, sample.uri)
    }

    /** A cover picked by hand — from the folder or the photo picker. False if it couldn't be read. */
    suspend fun useImage(album: Album, image: Uri): Boolean {
        val bytes = folders.read(image) ?: return false
        artwork.saveDownloadedCover(album, bytes)
        _missing.value = _missing.value?.filterNot { it.id == album.id }
        return true
    }

    /** Deezer's matches for one album, best first. Throws IOException when Deezer can't be reached. */
    suspend fun candidates(album: Album): List<CoverCandidate> = client.search(album.name, album.artistName)

    suspend fun use(album: Album, candidate: CoverCandidate) {
        artwork.saveDownloadedCover(album, client.download(candidate.coverUrl))
        _missing.value = _missing.value?.filterNot { it.id == album.id }
    }

    fun findAll() {
        if (scanJob?.isActive == true) return
        val todo = _missing.value.orEmpty()
        if (todo.isEmpty()) return
        scanJob = scope.launch {
            val found = mutableListOf<Album>()
            val notFound = mutableListOf<Album>()
            rejected.clear()
            _scan.value = Scan.Running(0, todo.size, emptyList(), emptyList())
            var error: String? = null
            var failuresInARow = 0
            for ((i, album) in todo.withIndex()) {
                try {
                    val best = client.search(album.name, album.artistName).firstOrNull()
                    if (best != null) {
                        artwork.saveDownloadedCover(album, client.download(best.coverUrl))
                        found += album
                    } else {
                        notFound += album
                    }
                    failuresInARow = 0
                } catch (e: IOException) {
                    Timber.w(e, "Cover lookup failed for %s", album.name)
                    notFound += album
                    // Offline or Deezer down: stop rather than fail every album.
                    if (++failuresInARow >= 3) {
                        error = "Couldn't reach Deezer — check the connection and try again."
                        break
                    }
                }
                _scan.value = Scan.Running(i + 1, todo.size, found.filterNot { it.id in rejected }, notFound.toList())
                delay(250) // Well under Deezer's 50 requests / 5 s.
            }
            val kept = found.filterNot { it.id in rejected }
            _missing.value = _missing.value?.filterNot { a -> kept.any { it.id == a.id } }
            _scan.value = Scan.Finished(kept, notFound.toList(), error)
        }
    }

    fun stop() {
        scanJob?.cancel()
        val running = _scan.value as? Scan.Running ?: return
        _missing.value = _missing.value?.filterNot { a -> running.found.any { it.id == a.id } }
        _scan.value = Scan.Finished(running.found, running.notFound, null)
    }

    /** A wrong match from the library scan: drop its cover, back on the missing list. */
    fun reject(album: Album) {
        rejected += album.id
        scope.launch {
            artwork.removeDownloadedCover(album)
            _missing.value = (_missing.value.orEmpty() + album).distinctBy { it.id }
            _scan.value = when (val s = _scan.value) {
                is Scan.Running -> s.copy(found = s.found.filterNot { it.id == album.id })
                is Scan.Finished -> s.copy(found = s.found.filterNot { it.id == album.id })
                Scan.Idle -> Scan.Idle
            }
        }
    }

    fun resetScan() {
        if (scanJob?.isActive != true) _scan.value = Scan.Idle
    }
}
