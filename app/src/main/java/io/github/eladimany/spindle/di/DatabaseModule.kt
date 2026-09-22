package io.github.eladimany.spindle.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.eladimany.spindle.data.db.AppDatabase
import io.github.eladimany.spindle.data.db.dao.AlbumDao
import io.github.eladimany.spindle.data.db.dao.ArtistArtworkDao
import io.github.eladimany.spindle.data.db.dao.ArtistDao
import io.github.eladimany.spindle.data.db.dao.FolderDao
import io.github.eladimany.spindle.data.db.dao.PlaylistDao
import io.github.eladimany.spindle.data.db.dao.TrackDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "spindle.db")
            // Pre-release: no user data worth migrating yet. Revisit before v1.0 ships.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

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
