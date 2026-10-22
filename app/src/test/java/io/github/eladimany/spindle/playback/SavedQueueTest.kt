package io.github.eladimany.spindle.playback

import io.github.eladimany.spindle.core.model.QueueItem
import io.github.eladimany.spindle.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavedQueueTest {

    private fun track(id: Long) = Track(
        id = id,
        uri = "content://media/external/audio/media/$id",
        title = "Track $id",
        artistId = 1,
        artistName = "Artist",
        albumId = 1,
        albumName = "Album",
        trackNumber = id.toInt(),
        discNumber = 1,
        durationMs = 200_000L,
        year = 2020,
        genre = null,
        dateAddedMs = 1_700_000_000_000L,
    )

    private val library = (1L..5L).associateWith { track(it) }

    /** A shuffled queue of tracks 1–5, playing the 3rd slot (track 2), 42 s in. */
    private fun shuffled(): SavedQueue {
        val manager = QueueManager()
        manager.load(
            QueueManager.Snapshot(
                items = (1L..5L).map { QueueItem("q$it", track(it)) },
                order = listOf(4, 0, 1, 3, 2),
                position = 2,
                repeatMode = QueueManager.RepeatMode.ALL,
                shuffleMode = QueueManager.ShuffleMode.SMART,
            ),
        )
        return SavedQueue.from(manager.snapshot(), finished = false, positionMs = 42_000L)
    }

    @Test
    fun `round-trips through JSON and back to the same queue`() {
        val saved = SavedQueue.fromJson(shuffled().toJson())!!
        val (snapshot, positionMs) = saved.resolve(library)!!
        val manager = QueueManager().apply { load(snapshot) }
        assertEquals(listOf(5L, 1L, 2L, 4L, 3L), manager.queue.map { it.track.id })
        assertEquals(2L, manager.currentItem!!.track.id)
        assertEquals(42_000L, positionMs)
        assertEquals(QueueManager.ShuffleMode.SMART, manager.shuffleMode)
        assertEquals(QueueManager.RepeatMode.ALL, manager.repeatMode)
    }

    @Test
    fun `tracks gone from the library drop out, the current one survives`() {
        val (snapshot, positionMs) = shuffled().resolve(library - 5L - 4L)!!
        val manager = QueueManager().apply { load(snapshot) }
        assertEquals(listOf(1L, 2L, 3L), manager.queue.map { it.track.id })
        assertEquals(2L, manager.currentItem!!.track.id)
        assertEquals(42_000L, positionMs)
    }

    @Test
    fun `if the current track is gone, the next one plays from its start`() {
        val (snapshot, positionMs) = shuffled().resolve(library - 2L)!!
        val manager = QueueManager().apply { load(snapshot) }
        assertEquals(4L, manager.currentItem!!.track.id)
        assertEquals(0L, positionMs)
    }

    @Test
    fun `nothing left or a broken file means no queue`() {
        assertNull(shuffled().resolve(emptyMap()))
        assertNull(SavedQueue.fromJson("not json"))
        assertNull(SavedQueue.fromJson("""{"itemIds":["a"],"trackIds":[],"order":[],"position":0,"repeat":"OFF","shuffle":"OFF"}"""))
        assertNull(SavedQueue.fromJson("""{"itemIds":[],"trackIds":[],"order":[],"position":0,"repeat":"SIDEWAYS","shuffle":"OFF"}"""))
    }
}
