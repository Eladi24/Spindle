package io.github.eladimany.spindle.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "albums",
    indices = [
        Index("artistId"),
        Index("nameSortKey"),
    ],
)
data class AlbumEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val nameSortKey: String,
    val artistId: Long,
    val artistName: String,
    val year: Int?,
    val trackCount: Int,
    val artworkUri: String?,
)
