package io.github.eladimany.spindle.data.library

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.paging.PagingSource
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.eladimany.spindle.core.model.Album
import io.github.eladimany.spindle.core.model.Artist
import io.github.eladimany.spindle.core.model.Folder
import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.core.model.TrackSort
import io.github.eladimany.spindle.data.db.dao.AlbumDao
import io.github.eladimany.spindle.data.db.dao.ArtistDao
import io.github.eladimany.spindle.data.db.dao.FolderDao
import io.github.eladimany.spindle.data.db.dao.TrackDao
import io.github.eladimany.spindle.data.db.entity.AlbumEntity
import io.github.eladimany.spindle.data.db.entity.ArtistEntity
import io.github.eladimany.spindle.data.db.entity.TrackEntity
import io.github.eladimany.spindle.data.prefs.SettingsRepository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trackDao: TrackDao,
    private val albumDao: AlbumDao,
    private val artistDao: ArtistDao,
    private val folderDao: FolderDao,
    private val scanner: MediaStoreScanner,
    private val settings: SettingsRepository,
) {
    val tracks: Flow<List<Track>> = trackDao.observeAll().map { list -> list.map { it.toDomain() } }
    val albums: Flow<List<Album>> = albumDao.observeAll().map { list -> list.map { it.toDomain() } }
    val artists: Flow<List<Artist>> = artistDao.observeAll().map { list -> list.map { it.toDomain() } }

    /** Every folder MediaStore found, flagged with whether it's excluded from the library. */
    val folders: Flow<List<Folder>> = combine(folderDao.observeAll(), settings.excludedFolderIds) { folders, excluded ->
        folders.map { it.toDomain(isExcluded = it.id in excluded) }
    }

    /** Folders actually worth browsing — included, and non-empty. */
    val browsableFolders: Flow<List<Folder>> = folders.map { list -> list.filter { !it.isExcluded && it.trackCount > 0 } }

    suspend fun folderById(folderId: Long): Folder? {
        val excluded = settings.excludedFolderIds.first()
        return folderDao.getById(folderId)?.toDomain(isExcluded = folderId in excluded)
    }

    fun tracksPagingSource(sort: TrackSort): PagingSource<Int, TrackEntity> = when (sort) {
        TrackSort.TITLE -> trackDao.pagingSourceByTitle()
        TrackSort.DATE_ADDED -> trackDao.pagingSourceByDateAdded()
        TrackSort.YEAR -> trackDao.pagingSourceByYear()
    }

    /** Full sorted track list — used to build a "play all" queue that matches what's on screen. */
    suspend fun allTracksSorted(sort: TrackSort): List<Track> = when (sort) {
        TrackSort.TITLE -> trackDao.getAllByTitle()
        TrackSort.DATE_ADDED -> trackDao.getAllByDateAdded()
        TrackSort.YEAR -> trackDao.getAllByYear()
    }.map { it.toDomain() }

    fun albumsPagingSource(): PagingSource<Int, AlbumEntity> = albumDao.pagingSource()

    fun artistsPagingSource(): PagingSource<Int, ArtistEntity> = artistDao.pagingSource()

    fun tracksForAlbum(albumId: Long): Flow<List<Track>> =
        trackDao.observeByAlbum(albumId).map { list -> list.map { it.toDomain() } }

    fun tracksForArtist(artistId: Long): Flow<List<Track>> =
        trackDao.observeByArtist(artistId).map { list -> list.map { it.toDomain() } }

    fun tracksForFolder(folderId: Long): Flow<List<Track>> =
        trackDao.observeByFolder(folderId).map { list -> list.map { it.toDomain() } }

    fun albumsForArtist(artistId: Long): Flow<List<Album>> =
        albumDao.observeByArtist(artistId).map { list -> list.map { it.toDomain() } }

    suspend fun albumById(albumId: Long): Album? = albumDao.getById(albumId)?.toDomain()

    suspend fun artistById(artistId: Long): Artist? = artistDao.getById(artistId)?.toDomain()

    fun search(query: String): Flow<List<Track>> =
        trackDao.search(query).map { list -> list.map { it.toDomain() } }

    fun searchArtists(query: String): Flow<List<Artist>> =
        artistDao.search(query).map { list -> list.map { it.toDomain() } }

    fun searchAlbums(query: String): Flow<List<Album>> =
        albumDao.search(query).map { list -> list.map { it.toDomain() } }

    /** Toggles a folder's inclusion. Callers should [rescan] afterward to apply it. */
    suspend fun setFolderExcluded(folderId: Long, excluded: Boolean) {
        settings.setFolderExcluded(folderId, excluded)
    }

    /** Runs a full MediaStore scan and persists the result, replacing anything removed. */
    fun rescan(): Flow<ScanProgress> = flow {
        val excludedFolderIds = settings.excludedFolderIds.first()
        scanner.scan(excludedFolderIds).collect { progress ->
            if (progress is ScanProgress.Complete) {
                trackDao.upsertAll(progress.result.tracks)
                albumDao.upsertAll(progress.result.albums)
                artistDao.upsertAll(progress.result.artists)
                folderDao.upsertAll(progress.result.folders)
                trackDao.deleteMissing(progress.result.tracks.map { it.id })
                albumDao.deleteMissing(progress.result.albums.map { it.id })
                artistDao.deleteMissing(progress.result.artists.map { it.id })
                folderDao.deleteMissing(progress.result.folders.map { it.id })
            }
            emit(progress)
        }
    }

    /** Emits whenever the audio collection changes (new file, delete, etc.). */
    fun observeMediaStoreChanges(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        context.contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            true,
            observer,
        )
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }
}
