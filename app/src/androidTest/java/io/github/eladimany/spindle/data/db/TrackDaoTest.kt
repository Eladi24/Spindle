package io.github.eladimany.spindle.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.eladimany.spindle.data.db.entity.TrackEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun createDb() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    private fun trackEntity(id: Int) = TrackEntity(
        id = id.toLong(),
        uri = "content://media/external/audio/media/$id",
        title = "Track $id",
        titleSortKey = "track $id",
        artistId = (id % 200).toLong(),
        artistName = "Artist ${id % 200}",
        albumId = (id % 500).toLong(),
        albumName = "Album ${id % 500}",
        trackNumber = id % 20,
        discNumber = 1,
        durationMs = 200_000L,
        year = 2020,
        genre = "Rock",
    )

    @Test
    fun insertAndQuery5000Tracks() = runBlocking {
        val tracks = (1..5000).map(::trackEntity)

        db.trackDao().upsertAll(tracks)

        assertEquals(5000, db.trackDao().count())
        assertEquals(tracks, db.trackDao().observeAll().first().sortedBy { it.id })
        assertEquals("Track 1", db.trackDao().getById(1)?.title)

        val artist0Tracks = db.trackDao().observeByArtist(0).first()
        assertEquals(tracks.count { it.artistId == 0L }, artist0Tracks.size)
    }
}
