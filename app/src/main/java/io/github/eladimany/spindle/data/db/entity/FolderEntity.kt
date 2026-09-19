package io.github.eladimany.spindle.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Every folder MediaStore reports audio in, regardless of whether its tracks are
 * included in the library — this is what the folder picker in Settings lists.
 */
@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val trackCount: Int,
)
