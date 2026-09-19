package io.github.eladimany.spindle.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import io.github.eladimany.spindle.data.db.entity.PlaylistEntity
import io.github.eladimany.spindle.data.db.entity.PlaylistTrackCrossRef
import io.github.eladimany.spindle.data.db.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY nameSortKey")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getById(id: Long): PlaylistEntity?

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Delete
    suspend fun delete(playlist: PlaylistEntity)

    @Query(
        "SELECT t.* FROM tracks t " +
            "INNER JOIN playlist_track_cross_ref x ON x.trackId = t.id " +
            "WHERE x.playlistId = :playlistId ORDER BY x.position",
    )
    fun observeTracks(playlistId: Long): Flow<List<TrackEntity>>

    @Query("SELECT COUNT(*) FROM playlist_track_cross_ref WHERE playlistId = :playlistId")
    suspend fun trackCount(playlistId: Long): Int

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_track_cross_ref WHERE playlistId = :playlistId")
    suspend fun nextPosition(playlistId: Long): Int

    @Insert
    suspend fun addTrack(crossRef: PlaylistTrackCrossRef)

    @Query("DELETE FROM playlist_track_cross_ref WHERE id = :crossRefId")
    suspend fun removeTrack(crossRefId: Long)

    @Update
    suspend fun updatePositions(crossRefs: List<PlaylistTrackCrossRef>)
}
