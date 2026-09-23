package io.github.eladimany.spindle.data.history

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Listening history, deliberately a separate file from [io.github.eladimany.spindle.data.db.AppDatabase].
 * The library DB is a rebuildable cache and uses destructive migration; history can't
 * be rebuilt, so schema changes here need real migrations — never destructive fallback.
 */
@Database(entities = [PlayEventEntity::class], version = 1, exportSchema = true)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun playEventDao(): PlayEventDao
}
