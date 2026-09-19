package io.github.eladimany.spindle.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.github.eladimany.spindle.data.db.entity.AlbumEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumDao {
    @Query("SELECT * FROM albums ORDER BY nameSortKey")
    fun observeAll(): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums ORDER BY nameSortKey")
    fun pagingSource(): PagingSource<Int, AlbumEntity>

    @Query("SELECT * FROM albums WHERE artistId = :artistId ORDER BY year, nameSortKey")
    fun observeByArtist(artistId: Long): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE id = :id")
    suspend fun getById(id: Long): AlbumEntity?

    @Query("UPDATE albums SET artworkUri = :uri WHERE id = :albumId")
    suspend fun updateArtworkUri(albumId: Long, uri: String)

    @Upsert
    suspend fun upsertAll(albums: List<AlbumEntity>)

    @Query("DELETE FROM albums WHERE id NOT IN (:keepIds)")
    suspend fun deleteMissing(keepIds: List<Long>)
}
