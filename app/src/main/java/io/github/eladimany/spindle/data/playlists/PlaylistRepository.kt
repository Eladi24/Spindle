package io.github.eladimany.spindle.data.playlists

import io.github.eladimany.spindle.core.model.Playlist
import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.data.db.dao.PlaylistDao
import io.github.eladimany.spindle.data.db.entity.PlaylistEntity
import io.github.eladimany.spindle.data.library.toDomain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Create/rename/delete only for now — adding tracks, reordering, and M3U import/export
 * are Phase 1 §10, not built yet.
 */
@Singleton
class PlaylistRepository @Inject constructor(
    private val playlistDao: PlaylistDao,
) {
    val playlists: Flow<List<Playlist>> = playlistDao.observeAll().map { list ->
        list.map { Playlist(id = it.id, name = it.name, trackCount = 0, createdAt = it.createdAt, updatedAt = it.updatedAt) }
    }

    fun tracksFor(playlistId: Long): Flow<List<Track>> =
        playlistDao.observeTracks(playlistId).map { list -> list.map { it.toDomain() } }

    suspend fun create(name: String) {
        val now = System.currentTimeMillis()
        playlistDao.insert(
            PlaylistEntity(name = name, nameSortKey = name.lowercase(), createdAt = now, updatedAt = now),
        )
    }

    suspend fun delete(playlistId: Long) = playlistDao.deleteById(playlistId)

    suspend fun getById(playlistId: Long): Playlist? =
        playlistDao.getById(playlistId)?.let { Playlist(it.id, it.name, 0, it.createdAt, it.updatedAt) }
}
