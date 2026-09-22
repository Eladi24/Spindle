package io.github.eladimany.spindle.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.github.eladimany.spindle.data.db.entity.ArtistArtworkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ArtistArtworkDao {
    @Query("SELECT * FROM artist_artwork")
    fun observeAll(): Flow<List<ArtistArtworkEntity>>

    @Upsert
    suspend fun upsert(entity: ArtistArtworkEntity)
}
