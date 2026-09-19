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
import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.data.db.dao.AlbumDao
import io.github.eladimany.spindle.data.db.dao.ArtistDao
import io.github.eladimany.spindle.data.db.dao.TrackDao
import io.github.eladimany.spindle.data.db.entity.TrackEntity
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
    private val scanner: MediaStoreScanner,
) {
    val tracks: Flow<List<Track>> = trackDao.observeAll().map { list -> list.map { it.toDomain() } }
    val albums: Flow<List<Album>> = albumDao.observeAll().map { list -> list.map { it.toDomain() } }
    val artists: Flow<List<Artist>> = artistDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun tracksPagingSource(): PagingSource<Int, TrackEntity> = trackDao.pagingSource()

    fun tracksForAlbum(albumId: Long): Flow<List<Track>> =
        trackDao.observeByAlbum(albumId).map { list -> list.map { it.toDomain() } }

    fun tracksForArtist(artistId: Long): Flow<List<Track>> =
        trackDao.observeByArtist(artistId).map { list -> list.map { it.toDomain() } }

    fun albumsForArtist(artistId: Long): Flow<List<Album>> =
        albumDao.observeByArtist(artistId).map { list -> list.map { it.toDomain() } }

    fun search(query: String): Flow<List<Track>> =
        trackDao.search(query).map { list -> list.map { it.toDomain() } }

    /** Runs a full MediaStore scan and persists the result, replacing anything removed. */
    fun rescan(): Flow<ScanProgress> = flow {
        scanner.scan().collect { progress ->
            if (progress is ScanProgress.Complete) {
                trackDao.upsertAll(progress.result.tracks)
                albumDao.upsertAll(progress.result.albums)
                artistDao.upsertAll(progress.result.artists)
                trackDao.deleteMissing(progress.result.tracks.map { it.id })
                albumDao.deleteMissing(progress.result.albums.map { it.id })
                artistDao.deleteMissing(progress.result.artists.map { it.id })
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
