package io.github.eladimany.spindle.data.trackinfo

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Spindle's own correction of one file's info — the file itself is never written. Keyed
 * by [fileKey] (folder path + file name), which survives the MediaStore id changing.
 *
 * Null fields mean "use the file's". How the rest combine with the file's tags is
 * [TrackInfoMerge]: an automatic fix ([manual] false) only fills what the file lacks;
 * a fix the user typed wins over the file.
 */
@Entity(tableName = "track_info_fixes")
data class TrackInfoFixEntity(
    @PrimaryKey val fileKey: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val manual: Boolean = false,
    /** [FixSource] name — where it came from. */
    val source: String,
    /**
     * The file title this fix's artist/title were split from. If the file's title later
     * changes (the user retagged it), the split no longer applies.
     */
    val splitFrom: String? = null,
    val updatedAtMs: Long,
)

enum class FixSource { TITLE, FILE_NAME, MUSICBRAINZ, MANUAL }

@Dao
interface TrackInfoFixDao {
    @Query("SELECT * FROM track_info_fixes")
    suspend fun getAll(): List<TrackInfoFixEntity>

    @Query("SELECT * FROM track_info_fixes WHERE fileKey = :fileKey")
    suspend fun get(fileKey: String): TrackInfoFixEntity?

    /** Files that already have a fix — the MusicBrainz fill-in skips them (looked up, or typed by the user). */
    @Query("SELECT fileKey FROM track_info_fixes")
    fun observeKeys(): Flow<List<String>>

    @Upsert
    suspend fun upsertAll(fixes: List<TrackInfoFixEntity>)

    @Query("DELETE FROM track_info_fixes WHERE fileKey = :fileKey")
    suspend fun delete(fileKey: String)
}
