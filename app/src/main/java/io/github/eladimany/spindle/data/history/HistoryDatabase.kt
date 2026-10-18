package io.github.eladimany.spindle.data.history

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.eladimany.spindle.data.trackinfo.TrackInfoFixDao
import io.github.eladimany.spindle.data.trackinfo.TrackInfoFixEntity

/**
 * Listening history, deliberately a separate file from [io.github.eladimany.spindle.data.db.AppDatabase].
 * The library DB is a rebuildable cache; history can't be rebuilt, so schema changes here
 * need real migrations — never destructive fallback. Track-info fixes live here for the
 * same reason (the user's own work; a rescan can't bring it back).
 */
@Database(entities = [PlayEventEntity::class, TrackInfoFixEntity::class], version = 2, exportSchema = true)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun playEventDao(): PlayEventDao
    abstract fun trackInfoFixDao(): TrackInfoFixDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `track_info_fixes` (`fileKey` TEXT NOT NULL, `title` TEXT, " +
                        "`artist` TEXT, `album` TEXT, `year` INTEGER, `genre` TEXT, `manual` INTEGER NOT NULL, " +
                        "`source` TEXT NOT NULL, `splitFrom` TEXT, `updatedAtMs` INTEGER NOT NULL, PRIMARY KEY(`fileKey`))",
                )
            }
        }
    }
}
