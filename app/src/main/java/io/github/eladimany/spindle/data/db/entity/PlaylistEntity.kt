package io.github.eladimany.spindle.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "playlists",
    indices = [
        Index("nameSortKey"),
    ],
)
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val nameSortKey: String,
    val createdAt: Long,
    val updatedAt: Long,
)
