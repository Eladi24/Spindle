package io.github.eladimany.spindle.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.eladimany.spindle.data.db.dao.AlbumDao
import io.github.eladimany.spindle.data.db.dao.ArtistArtworkDao
import io.github.eladimany.spindle.data.db.dao.ArtistDao
import io.github.eladimany.spindle.data.db.dao.FolderDao
import io.github.eladimany.spindle.data.db.dao.PlaylistDao
import io.github.eladimany.spindle.data.db.dao.TrackDao
import io.github.eladimany.spindle.data.db.entity.AlbumEntity
import io.github.eladimany.spindle.data.db.entity.ArtistArtworkEntity
import io.github.eladimany.spindle.data.db.entity.ArtistEntity
import io.github.eladimany.spindle.data.db.entity.FolderEntity
import io.github.eladimany.spindle.data.db.entity.PlaylistEntity
import io.github.eladimany.spindle.data.db.entity.PlaylistTrackCrossRef
import io.github.eladimany.spindle.data.db.entity.TrackEntity

@Database(
    entities = [
        TrackEntity::class,
        AlbumEntity::class,
        ArtistEntity::class,
        PlaylistEntity::class,
        PlaylistTrackCrossRef::class,
        FolderEntity::class,
        ArtistArtworkEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun albumDao(): AlbumDao
    abstract fun artistDao(): ArtistDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun folderDao(): FolderDao
    abstract fun artistArtworkDao(): ArtistArtworkDao
}

/**
 * 4 → 5: track-info fix columns. A real migration, not the destructive fallback: this DB
 * also holds the user's playlists, and handed-out beta builds have real ones.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tracks ADD COLUMN fileKey TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE tracks ADD COLUMN fileName TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE tracks ADD COLUMN fileArtistMissing INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE tracks ADD COLUMN infoFixed INTEGER NOT NULL DEFAULT 0")
    }
}
