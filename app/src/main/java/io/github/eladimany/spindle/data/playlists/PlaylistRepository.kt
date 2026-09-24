package io.github.eladimany.spindle.data.playlists

import io.github.eladimany.spindle.core.model.Playlist
import io.github.eladimany.spindle.core.model.PlaylistEntry
import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.data.db.dao.PlaylistDao
import io.github.eladimany.spindle.data.db.dao.TrackDao
import io.github.eladimany.spindle.data.db.entity.PlaylistEntity
import io.github.eladimany.spindle.data.db.entity.PlaylistTrackCrossRef
import io.github.eladimany.spindle.data.library.toDomain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** [matched] of [total] M3U rows resolved to a track already in the library. */
data class M3uImportResult(val playlistId: Long, val matched: Int, val total: Int)

@Singleton
class PlaylistRepository @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val trackDao: TrackDao,
) {
    val playlists: Flow<List<Playlist>> = playlistDao.observeAllWithCounts().map { list ->
        list.map {
            Playlist(
                id = it.id,
                name = it.name,
                trackCount = it.trackCount,
                createdAt = it.createdAt,
                updatedAt = it.updatedAt,
                totalDurationMs = it.totalDurationMs,
            )
        }
    }

    /** Tracks for a playlist's cover mosaic: the first few, one per album. */
    suspend fun coverTracks(playlistId: Long): List<Track> =
        playlistDao.leadingTracks(playlistId, limit = 40).map { it.toDomain() }.distinctBy { it.albumId }.take(4)

    fun tracksFor(playlistId: Long): Flow<List<Track>> =
        playlistDao.observeTracks(playlistId).map { list -> list.map { it.toDomain() } }

    /** Ordered membership rows, each carrying the cross-ref id remove/reorder need. */
    fun entriesFor(playlistId: Long): Flow<List<PlaylistEntry>> =
        playlistDao.observeEntries(playlistId).map { rows ->
            rows.map { PlaylistEntry(crossRefId = it.crossRefId, position = it.position, track = it.track.toDomain()) }
        }

    suspend fun create(name: String): Long {
        val now = System.currentTimeMillis()
        return playlistDao.insert(
            PlaylistEntity(name = name, nameSortKey = name.lowercase(), createdAt = now, updatedAt = now),
        )
    }

    suspend fun rename(playlistId: Long, name: String) {
        val existing = playlistDao.getById(playlistId) ?: return
        playlistDao.update(
            existing.copy(name = name, nameSortKey = name.lowercase(), updatedAt = System.currentTimeMillis()),
        )
    }

    suspend fun delete(playlistId: Long) = playlistDao.deleteById(playlistId)

    suspend fun getById(playlistId: Long): Playlist? =
        playlistDao.getById(playlistId)?.let {
            Playlist(it.id, it.name, playlistDao.trackCount(it.id), it.createdAt, it.updatedAt)
        }

    /** Appends one or more tracks to the end of the playlist, in the order given. */
    suspend fun addTracks(playlistId: Long, trackIds: List<Long>) {
        if (trackIds.isEmpty()) return
        val start = playlistDao.nextPosition(playlistId)
        playlistDao.addTracks(
            trackIds.mapIndexed { offset, trackId ->
                PlaylistTrackCrossRef(playlistId = playlistId, trackId = trackId, position = start + offset)
            },
        )
        touchUpdatedAt(playlistId)
    }

    suspend fun removeEntry(playlistId: Long, crossRefId: Long) {
        playlistDao.removeTrack(crossRefId)
        touchUpdatedAt(playlistId)
    }

    /** Undo for [removeEntry]: the same membership row back, same id and position. */
    suspend fun restoreEntry(playlistId: Long, entry: PlaylistEntry) {
        playlistDao.addTrack(
            PlaylistTrackCrossRef(id = entry.crossRefId, playlistId = playlistId, trackId = entry.track.id, position = entry.position),
        )
        touchUpdatedAt(playlistId)
    }

    /**
     * Persists a full reorder — [orderedEntries] is the new top-to-bottom order.
     * Room's @Update overwrites every column by primary key, so this needs each
     * entry's real trackId, not just its crossRefId — a placeholder there would
     * silently corrupt the row's track reference.
     */
    suspend fun reorder(playlistId: Long, orderedEntries: List<PlaylistEntry>) {
        playlistDao.updatePositions(
            orderedEntries.mapIndexed { index, entry ->
                PlaylistTrackCrossRef(id = entry.crossRefId, playlistId = playlistId, trackId = entry.track.id, position = index)
            },
        )
        touchUpdatedAt(playlistId)
    }

    private suspend fun touchUpdatedAt(playlistId: Long) {
        val existing = playlistDao.getById(playlistId) ?: return
        playlistDao.update(existing.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun exportM3u(playlistId: Long): String {
        val tracks = entriesFor(playlistId).first().map { it.track }
        return M3uFormat.export(tracks)
    }

    /** Creates a new playlist named [name] from an M3U file's contents. A row resolves by
     * matching its content URI against the library first (round-trips a Spindle export
     * exactly); failing that, falls back to a title search, preferring a matching artist —
     * this is how a file exported elsewhere or with a stale URI still recovers most tracks. */
    suspend fun importM3u(name: String, content: String): M3uImportResult {
        val parsed = M3uFormat.parse(content)
        val matchedIds = mutableListOf<Long>()
        for (entry in parsed) {
            val byUri = trackDao.getByUri(entry.location)
            val resolved = byUri ?: entry.title?.let { title ->
                val candidates = trackDao.findByTitleLike(title)
                candidates.firstOrNull { it.artistName.equals(entry.artist, ignoreCase = true) }
                    ?: candidates.firstOrNull()
            }
            if (resolved != null) matchedIds += resolved.id
        }
        val playlistId = create(name)
        if (matchedIds.isNotEmpty()) addTracks(playlistId, matchedIds)
        return M3uImportResult(playlistId = playlistId, matched = matchedIds.size, total = parsed.size)
    }
}
