package io.github.eladimany.spindle.data.library

import io.github.eladimany.spindle.core.model.Album
import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.data.db.dao.AlbumDao
import io.github.eladimany.spindle.data.db.dao.TrackDao
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ArtworkRepository @Inject constructor(
    private val albumDao: AlbumDao,
    private val trackDao: TrackDao,
    private val extractor: ArtworkExtractor,
) {
    /** Returns a cached artwork URI for the track's album, extracting it on first use. */
    suspend fun artworkUriFor(track: Track): String? {
        val album = albumDao.getById(track.albumId) ?: return null
        album.artworkUri?.let { return it }
        return extractAndCache(track.albumId, track.uri)
    }

    /** Same, when you only have the album — finds a sample track to extract from. */
    suspend fun artworkUriForAlbum(album: Album): String? {
        album.artworkUri?.let { return it }
        val sampleTrack = trackDao.firstTrackForAlbum(album.id) ?: return null
        return extractAndCache(album.id, sampleTrack.uri)
    }

    private suspend fun extractAndCache(albumId: Long, sampleTrackUri: String): String? {
        val uri = extractor.extract(albumId, sampleTrackUri) ?: return null
        albumDao.updateArtworkUri(albumId, uri.toString())
        return uri.toString()
    }
}
