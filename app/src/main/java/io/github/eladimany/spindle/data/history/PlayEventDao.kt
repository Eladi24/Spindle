package io.github.eladimany.spindle.data.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface PlayEventDao {
    @Insert
    suspend fun insert(event: PlayEventEntity)

    @Query("DELETE FROM play_events WHERE startedAtMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)

    @Query("SELECT COUNT(*) FROM play_events")
    suspend fun count(): Int
}
