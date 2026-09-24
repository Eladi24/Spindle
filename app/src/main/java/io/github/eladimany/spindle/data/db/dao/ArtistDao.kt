package io.github.eladimany.spindle.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.github.eladimany.spindle.data.db.entity.ArtistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ArtistDao {
    @Query("SELECT * FROM artists ORDER BY nameSortKey")
    fun observeAll(): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM artists ORDER BY nameSortKey")
    fun pagingSource(): PagingSource<Int, ArtistEntity>

    @Query("SELECT COUNT(*) FROM artists")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM artists WHERE id = :id")
    suspend fun getById(id: Long): ArtistEntity?

    @Query("SELECT * FROM artists WHERE name LIKE '%' || :query || '%' ORDER BY nameSortKey LIMIT :limit")
    fun search(query: String, limit: Int = 20): Flow<List<ArtistEntity>>

    @Upsert
    suspend fun upsertAll(artists: List<ArtistEntity>)

    @Query("DELETE FROM artists WHERE id NOT IN (:keepIds)")
    suspend fun deleteMissing(keepIds: List<Long>)
}
