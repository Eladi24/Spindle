package io.github.eladimany.spindle.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "artists",
    indices = [
        Index("nameSortKey"),
    ],
)
data class ArtistEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val nameSortKey: String,
    val albumCount: Int,
    val trackCount: Int,
)
