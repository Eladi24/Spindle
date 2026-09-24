package io.github.eladimany.spindle.data.artists

import io.github.eladimany.spindle.data.db.dao.ArtistArtworkDao
import io.github.eladimany.spindle.data.db.entity.ArtistArtworkEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ArtistArtworkRepository @Inject constructor(
    private val artistArtworkDao: ArtistArtworkDao,
    private val deezerClient: DeezerArtistArtworkClient,
) {
    val artworkByArtistId: Flow<Map<Long, String>> =
        artistArtworkDao.observeAll().map { rows ->
            // Rows saved before placeholders were filtered out still hold the silhouette.
            rows.filterNot { DeezerArtistArt.isPlaceholder(it.imageUrl) }.associate { it.artistId to it.imageUrl }
        }

    /** No-op if Deezer has no match — never overwrites a good URL with nothing. */
    suspend fun fetch(artistId: Long, artistName: String) {
        val imageUrl = deezerClient.searchArtistImageUrl(artistName) ?: return
        artistArtworkDao.upsert(ArtistArtworkEntity(artistId, imageUrl))
    }
}
