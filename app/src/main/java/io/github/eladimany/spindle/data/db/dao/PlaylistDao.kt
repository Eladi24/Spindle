package io.github.eladimany.spindle.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import io.github.eladimany.spindle.data.db.entity.PlaylistEntity
import io.github.eladimany.spindle.data.db.entity.PlaylistTrackCrossRef
import io.github.eladimany.spindle.data.db.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

/** A playlist row plus its live track count, for list screens that show "N tracks". */
data class PlaylistWithCount(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val trackCount: Int,
    val totalDurationMs: Long,
)

/** One playlist membership row joined to its track — carries [crossRefId] so remove/reorder
 * can target the membership itself rather than guessing from track id (which could repeat). */
data class PlaylistTrackEntryRow(
    val crossRefId: Long,
    val position: Int,
    @Embedded val track: TrackEntity,
)

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY nameSortKey")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query(
        "SELECT p.id, p.name, p.createdAt, p.updatedAt, COUNT(x.id) AS trackCount, " +
            "COALESCE(SUM(t.durationMs), 0) AS totalDurationMs " +
            "FROM playlists p LEFT JOIN playlist_track_cross_ref x ON x.playlistId = p.id " +
            "LEFT JOIN tracks t ON t.id = x.trackId " +
            "GROUP BY p.id ORDER BY p.nameSortKey",
    )
    fun observeAllWithCounts(): Flow<List<PlaylistWithCount>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getById(id: Long): PlaylistEntity?

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Delete
    suspend fun delete(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query(
        "SELECT t.* FROM tracks t " +
            "INNER JOIN playlist_track_cross_ref x ON x.trackId = t.id " +
            "WHERE x.playlistId = :playlistId ORDER BY x.position",
    )
    fun observeTracks(playlistId: Long): Flow<List<TrackEntity>>

    @Query(
        "SELECT x.id AS crossRefId, x.position AS position, t.* FROM tracks t " +
            "INNER JOIN playlist_track_cross_ref x ON x.trackId = t.id " +
            "WHERE x.playlistId = :playlistId ORDER BY x.position",
    )
    fun observeEntries(playlistId: Long): Flow<List<PlaylistTrackEntryRow>>

    /** The first tracks in playlist order — enough to find four different album covers. */
    @Query(
        "SELECT t.* FROM tracks t " +
            "INNER JOIN playlist_track_cross_ref x ON x.trackId = t.id " +
            "WHERE x.playlistId = :playlistId ORDER BY x.position LIMIT :limit",
    )
    suspend fun leadingTracks(playlistId: Long, limit: Int): List<TrackEntity>

    @Query("SELECT COUNT(*) FROM playlist_track_cross_ref WHERE playlistId = :playlistId")
    suspend fun trackCount(playlistId: Long): Int

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_track_cross_ref WHERE playlistId = :playlistId")
    suspend fun nextPosition(playlistId: Long): Int

    @Insert
    suspend fun addTrack(crossRef: PlaylistTrackCrossRef)

    @Insert
    suspend fun addTracks(crossRefs: List<PlaylistTrackCrossRef>)

    @Query("DELETE FROM playlist_track_cross_ref WHERE id = :crossRefId")
    suspend fun removeTrack(crossRefId: Long)

    @Update
    suspend fun updatePositions(crossRefs: List<PlaylistTrackCrossRef>)
}
