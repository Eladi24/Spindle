package io.github.eladimany.spindle.data.db.entity

import androidx.room.ColumnInfo
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
    val dateAddedMs: Long,
    /** Folder path + file name, lower-cased — what a track-info fix is keyed by (ids can change). */
    @ColumnInfo(defaultValue = "") val fileKey: String = "",
    @ColumnInfo(defaultValue = "") val fileName: String = "",
    /** The file itself has no artist tag (MediaStore says "<unknown>"). */
    @ColumnInfo(defaultValue = "0") val fileArtistMissing: Boolean = false,
    /** Title/artist/album/year/genre here include a saved fix ([io.github.eladimany.spindle.data.trackinfo]). */
    @ColumnInfo(defaultValue = "0") val infoFixed: Boolean = false,
)
