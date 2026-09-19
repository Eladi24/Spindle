package io.github.eladimany.spindle.data.library

import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.data.db.dao.AlbumDao
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ArtworkRepository @Inject constructor(
    private val albumDao: AlbumDao,
    private val extractor: ArtworkExtractor,
) {
    /** Returns a cached artwork URI for the track's album, extracting it on first use. */
    suspend fun artworkUriFor(track: Track): String? {
        val album = albumDao.getById(track.albumId) ?: return null
        album.artworkUri?.let { return it }

        val uri = extractor.extract(track.albumId, track.uri) ?: return null
        albumDao.updateArtworkUri(track.albumId, uri.toString())
        return uri.toString()
    }
}
