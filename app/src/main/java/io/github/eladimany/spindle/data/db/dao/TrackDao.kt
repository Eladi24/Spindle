package io.github.eladimany.spindle.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.github.eladimany.spindle.data.db.entity.TrackEntity
import io.github.eladimany.spindle.data.smartplaylists.TrackTags
import kotlinx.coroutines.flow.Flow

/** Size of the whole library, for the Tracks tab's "1,284 tracks · 86 h" line. */
data class LibraryStats(val trackCount: Int, val totalDurationMs: Long)

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY titleSortKey")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks ORDER BY titleSortKey")
    fun pagingSourceByTitle(): PagingSource<Int, TrackEntity>

    @Query("SELECT * FROM tracks ORDER BY dateAddedMs DESC")
    fun pagingSourceByDateAdded(): PagingSource<Int, TrackEntity>

    @Query("SELECT * FROM tracks ORDER BY year DESC, titleSortKey")
    fun pagingSourceByYear(): PagingSource<Int, TrackEntity>

    @Query("SELECT * FROM tracks ORDER BY titleSortKey")
    suspend fun getAllByTitle(): List<TrackEntity>

    @Query("SELECT * FROM tracks ORDER BY dateAddedMs DESC")
    suspend fun getAllByDateAdded(): List<TrackEntity>

    @Query("SELECT * FROM tracks ORDER BY year DESC, titleSortKey")
    suspend fun getAllByYear(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE albumId = :albumId ORDER BY discNumber, trackNumber")
    fun observeByAlbum(albumId: Long): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE artistId = :artistId ORDER BY titleSortKey")
    fun observeByArtist(artistId: Long): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE folderId = :folderId ORDER BY titleSortKey")
    fun observeByFolder(folderId: Long): Flow<List<TrackEntity>>

    @Query(
        """
        SELECT * FROM tracks
        WHERE title LIKE '%' || :query || '%'
           OR artistName LIKE '%' || :query || '%'
           OR albumName LIKE '%' || :query || '%'
        ORDER BY titleSortKey LIMIT :limit
        """,
    )
    fun search(query: String, limit: Int = 50): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getById(id: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE title LIKE '%' || :title || '%' ORDER BY titleSortKey LIMIT 5")
    suspend fun findByTitleLike(title: String): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE albumId = :albumId LIMIT 1")
    suspend fun firstTrackForAlbum(albumId: Long): TrackEntity?

    /** Just the tags the playlist builder filters on — a few bytes per track. */
    @Query("SELECT genre, year FROM tracks")
    suspend fun allTags(): List<TrackTags>

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) AS trackCount, COALESCE(SUM(durationMs), 0) AS totalDurationMs FROM tracks")
    fun observeStats(): Flow<LibraryStats>

    @Upsert
    suspend fun upsertAll(tracks: List<TrackEntity>)

    @Query("DELETE FROM tracks WHERE id NOT IN (:keepIds)")
    suspend fun deleteMissing(keepIds: List<Long>)
}
