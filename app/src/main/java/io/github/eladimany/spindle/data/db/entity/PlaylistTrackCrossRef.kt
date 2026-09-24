package io.github.eladimany.spindle.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Ordered membership of a track in a playlist. [id] is its own key (rather than a
 * composite of playlistId+position) so reordering only ever updates [position],
 * never the row's identity.
 */
@Entity(
    tableName = "playlist_track_cross_ref",
    indices = [
        Index("playlistId"),
        Index("trackId"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PlaylistTrackCrossRef(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val trackId: Long,
    val position: Int,
)
