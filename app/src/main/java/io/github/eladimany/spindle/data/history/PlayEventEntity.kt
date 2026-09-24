package io.github.eladimany.spindle.data.history

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One listen of one track, written when the listen ends. Raw material for smart
 * shuffle / smart playlists — aggregates (play count, skip rate) are computed from
 * these with SQL on demand rather than stored.
 */
@Entity(
    tableName = "play_events",
    indices = [Index("trackKey"), Index("startedAtMs")],
)
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** MediaStore id at the time of the listen. Can change if the file is re-indexed. */
    val trackId: Long,
    /** [trackKey] of artist/album/title — survives a MediaStore id change. */
    val trackKey: Long,
    val startedAtMs: Long,
    /** Wall-clock time actually spent in the Playing state (pauses excluded). */
    val listenedMs: Long,
    val trackDurationMs: Long,
    val endReason: PlayEndReason,
)

enum class PlayEndReason {
    /** Played to the end naturally. */
    COMPLETED,
    /** User pressed next. */
    SKIPPED,
    /** User pressed previous (including "restart this track"). */
    PREVIOUS,
    /** Something else started (tapped another track, new queue, jump in queue). */
    REPLACED,
    ERROR,
}
