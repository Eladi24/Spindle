package io.github.eladimany.spindle.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.github.eladimany.spindle.data.db.entity.FolderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY name")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getById(id: Long): FolderEntity?

    @Upsert
    suspend fun upsertAll(folders: List<FolderEntity>)

    @Query("DELETE FROM folders WHERE id NOT IN (:keepIds)")
    suspend fun deleteMissing(keepIds: List<Long>)
}
