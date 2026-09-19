package io.github.eladimany.spindle.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tracks",
    indices = [
        Index("artistId"),
        Index("albumId"),
        Index("titleSortKey"),
        Index("folderId"),
    ],
)
data class TrackEntity(
    @PrimaryKey val id: Long,
    val uri: String,
    val title: String,
    val titleSortKey: String,
    val artistId: Long,
    val artistName: String,
    val albumId: Long,
    val albumName: String,
    val trackNumber: Int?,
    val discNumber: Int?,
    val durationMs: Long,
    val year: Int?,
    val genre: String?,
    val folderId: Long,
    val folderName: String,
)
