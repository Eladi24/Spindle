package io.github.eladimany.spindle.data.library

import android.content.Context
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.eladimany.spindle.core.model.Album
import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.data.db.dao.AlbumDao
import io.github.eladimany.spindle.data.db.dao.TrackDao
import io.github.eladimany.spindle.data.history.trackKey
import io.github.eladimany.spindle.di.IoDispatcher
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Bumped whenever a cover is added or removed by hand, so artwork already on screen
 * (keyed by album) loads again. Read by TrackArtwork/AlbumArtwork.
 */
object ArtworkRevision {
    private val _value = MutableStateFlow(0)
    val value: StateFlow<Int> = _value.asStateFlow()
    fun bump() { _value.value++ }
}

@Singleton
class ArtworkRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val albumDao: AlbumDao,
    private val trackDao: TrackDao,
    private val extractor: ArtworkExtractor,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    // Covers found online (mockups 9A/9B). Kept in files/, not the cache, and named by
    // artist + album rather than album id: the library is rebuilt by rescans, these aren't.
    private val downloadedDir = File(context.filesDir, "album-covers")

    /** Returns a cached artwork URI for the track's album, extracting it on first use. */
    suspend fun artworkUriFor(track: Track): String? {
        val album = albumDao.getById(track.albumId) ?: return null
        album.artworkUri?.let { return it }
        return extractAndCache(track.albumId, track.uri) ?: downloadedUri(album.artistName, album.name)
    }

    /** Same, when you only have the album — finds a sample track to extract from. */
    suspend fun artworkUriForAlbum(album: Album): String? {
        album.artworkUri?.let { return it }
        val sampleTrack = trackDao.firstTrackForAlbum(album.id)
        return sampleTrack?.let { extractAndCache(album.id, it.uri) } ?: downloadedUri(album.artistName, album.name)
    }

    /** Saves a cover found online for [album]; music files are never touched. */
    suspend fun saveDownloadedCover(album: Album, bytes: ByteArray): String = withContext(ioDispatcher) {
        downloadedDir.mkdirs()
        val file = downloadedFile(album.artistName, album.name)
        file.writeBytes(bytes)
        // A new file at the same path: a fresh URI so Coil doesn't serve its cached copy.
        val uri = file.toUri().buildUpon().appendQueryParameter("v", System.currentTimeMillis().toString()).build().toString()
        albumDao.updateArtworkUri(album.id, uri)
        ArtworkRevision.bump()
        uri
    }

    /** Drops a cover found online (a wrong match). */
    suspend fun removeDownloadedCover(album: Album) = withContext(ioDispatcher) {
        downloadedFile(album.artistName, album.name).delete()
        albumDao.clearArtworkUri(album.id)
        ArtworkRevision.bump()
    }

    private suspend fun downloadedUri(artist: String, album: String): String? = withContext(ioDispatcher) {
        downloadedFile(artist, album).takeIf { it.exists() }?.toUri()?.toString()
    }

    private fun downloadedFile(artist: String, album: String) =
        File(downloadedDir, "${trackKey(artist, album, "").toULong()}.jpg")

    private suspend fun extractAndCache(albumId: Long, sampleTrackUri: String): String? {
        val uri = extractor.extract(albumId, sampleTrackUri) ?: return null
        albumDao.updateArtworkUri(albumId, uri.toString())
        return uri.toString()
    }
}
