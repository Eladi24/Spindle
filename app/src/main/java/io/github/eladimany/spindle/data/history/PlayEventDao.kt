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

    /**
     * Per-track totals for smart shuffle. A listen counts as full at 80% heard, and as
     * a skip only when next was pressed before half — judged by listenedMs, not
     * endReason alone (a seek to the end still ends as COMPLETED).
     */
    @Query(
        """
        SELECT trackKey,
            COUNT(*) AS plays,
            SUM(CASE WHEN trackDurationMs > 0 AND listenedMs * 10 >= trackDurationMs * 8 THEN 1 ELSE 0 END) AS fullListens,
            SUM(CASE WHEN endReason = 'SKIPPED' AND (trackDurationMs <= 0 OR listenedMs * 2 < trackDurationMs) THEN 1 ELSE 0 END) AS skips,
            MAX(startedAtMs) AS lastPlayedMs
        FROM play_events
        GROUP BY trackKey
        """
    )
    suspend fun statsByTrackKey(): List<TrackStatsRow>
}

data class TrackStatsRow(
    val trackKey: Long,
    val plays: Int,
    val fullListens: Int,
    val skips: Int,
    val lastPlayedMs: Long,
)
