package io.github.eladimany.spindle.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.eladimany.spindle.data.db.AppDatabase
import io.github.eladimany.spindle.data.db.MIGRATION_4_5
import io.github.eladimany.spindle.data.db.dao.AlbumDao
import io.github.eladimany.spindle.data.db.dao.ArtistArtworkDao
import io.github.eladimany.spindle.data.db.dao.ArtistDao
import io.github.eladimany.spindle.data.db.dao.FolderDao
import io.github.eladimany.spindle.data.db.dao.PlaylistDao
import io.github.eladimany.spindle.data.db.dao.TrackDao
import io.github.eladimany.spindle.data.history.HistoryDatabase
import io.github.eladimany.spindle.data.trackinfo.TrackInfoFixDao
import io.github.eladimany.spindle.data.history.PlayEventDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "spindle.db")
            .addMigrations(MIGRATION_4_5)
            // Only for versions older than the first handed-out build (4). This DB holds
            // playlists too: every change from 4 on needs a real Migration.
            .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1, 2, 3)
            .build()

    // No destructive fallback here: history can't be rebuilt by a rescan. Schema
    // changes need a real Migration — see HistoryDatabase.
    @Provides
    @Singleton
    fun provideHistoryDatabase(@ApplicationContext context: Context): HistoryDatabase =
        Room.databaseBuilder(context, HistoryDatabase::class.java, "spindle-history.db")
            .addMigrations(HistoryDatabase.MIGRATION_1_2)
            .build()

    @Provides
    fun providePlayEventDao(db: HistoryDatabase): PlayEventDao = db.playEventDao()

    @Provides
    fun provideTrackInfoFixDao(db: HistoryDatabase): TrackInfoFixDao = db.trackInfoFixDao()

    @Provides
    fun provideTrackDao(db: AppDatabase): TrackDao = db.trackDao()

    @Provides
    fun provideAlbumDao(db: AppDatabase): AlbumDao = db.albumDao()

    @Provides
    fun provideArtistDao(db: AppDatabase): ArtistDao = db.artistDao()

    @Provides
    fun providePlaylistDao(db: AppDatabase): PlaylistDao = db.playlistDao()

    @Provides
    fun provideFolderDao(db: AppDatabase): FolderDao = db.folderDao()

    @Provides
    fun provideArtistArtworkDao(db: AppDatabase): ArtistArtworkDao = db.artistArtworkDao()
}
