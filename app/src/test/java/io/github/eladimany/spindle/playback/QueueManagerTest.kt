package io.github.eladimany.spindle.playback

import io.github.eladimany.spindle.core.model.QueueItem
import io.github.eladimany.spindle.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class QueueManagerTest {

    private lateinit var manager: QueueManager
    private lateinit var fixture: List<QueueItem>

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

    @Before
    fun setUp() {
        manager = QueueManager()
        fixture = (1..5).map { QueueItem(id = "q$it", track = track(it.toLong())) }
        manager.setQueue(fixture)
    }

    @Test
    fun `setQueue starts at the first item`() {
        assertEquals("q1", manager.currentItem?.id)
        assertEquals(0, manager.currentIndex)
        assertEquals(5, manager.size)
    }

    @Test
    fun `next advances through the queue in order`() {
        assertEquals("q2", manager.next()?.id)
        assertEquals("q3", manager.next()?.id)
    }

    @Test
    fun `next at the end with repeat off returns null and stays on the last track`() {
        repeat(4) { manager.next() }
        assertEquals("q5", manager.currentItem?.id)
        assertNull(manager.next())
        assertEquals("q5", manager.currentItem?.id)
    }

    @Test
    fun `next at the end with repeat all wraps to the first track`() {
        manager.setRepeatMode(QueueManager.RepeatMode.ALL)
        repeat(4) { manager.next() }
        assertEquals("q5", manager.currentItem?.id)
        assertEquals("q1", manager.next()?.id)
    }

    @Test
    fun `previous at the start with repeat off stays on the first track`() {
        assertEquals("q1", manager.previous()?.id)
    }

    @Test
    fun `previous at the start with repeat all wraps to the last track`() {
        manager.setRepeatMode(QueueManager.RepeatMode.ALL)
        assertEquals("q5", manager.previous()?.id)
    }

    @Test
    fun `onTrackEnded with repeat one replays the same track`() {
        manager.setRepeatMode(QueueManager.RepeatMode.ONE)
        manager.next() // now on q2
        assertEquals("q2", manager.onTrackEnded()?.id)
        assertEquals("q2", manager.onTrackEnded()?.id)
        assertEquals("q2", manager.currentItem?.id)
    }

    @Test
    fun `onTrackEnded with repeat all wraps around at the end`() {
        manager.setRepeatMode(QueueManager.RepeatMode.ALL)
        repeat(4) { manager.onTrackEnded() }
        assertEquals("q5", manager.currentItem?.id)
        assertEquals("q1", manager.onTrackEnded()?.id)
    }

    @Test
    fun `onTrackEnded with repeat off returns null at the end of the queue`() {
        repeat(4) { manager.onTrackEnded() }
        assertEquals("q5", manager.currentItem?.id)
        assertNull(manager.onTrackEnded())
        assertEquals("q5", manager.currentItem?.id)
    }

    @Test
    fun `shuffle keeps the current track current and reordering is reversible`() {
        manager.next() // q2
        manager.setShuffled(true)
        assertTrue(manager.isShuffled)
        assertEquals("q2", manager.currentItem?.id)
        assertEquals(5, manager.queue.size)
        assertEquals(fixture.map { it.id }.toSet(), manager.queue.map { it.id }.toSet())

        manager.setShuffled(false)
        assertEquals(fixture.map { it.id }, manager.queue.map { it.id })
        assertEquals("q2", manager.currentItem?.id)
    }

    @Test
    fun `shuffle places the currently playing track first, not wherever it randomly lands`() {
        manager.next() // q2
        manager.setShuffled(true)
        assertEquals(0, manager.currentIndex)
        assertEquals("q2", manager.queue.first().id)
    }

    // Reverse order, but honouring the pin — enough to see which orderer ran.
    private val reversing = QueueManager.ShuffleOrderer { items, pinned ->
        listOfNotNull(pinned) + items.indices.reversed().filter { it != pinned }
    }

    @Test
    fun `smart mode uses its orderer and keeps the current track first`() {
        manager.next() // q2
        manager.setShuffleMode(QueueManager.ShuffleMode.SMART, reversing)
        assertEquals(QueueManager.ShuffleMode.SMART, manager.shuffleMode)
        assertEquals(listOf("q2", "q5", "q4", "q3", "q1"), manager.queue.map { it.id })
        assertEquals("q2", manager.currentItem?.id)
    }

    @Test
    fun `switching shuffle to smart reorders, and off restores the original order`() {
        manager.next() // q2
        manager.setShuffleMode(QueueManager.ShuffleMode.SHUFFLE)
        manager.setShuffleMode(QueueManager.ShuffleMode.SMART, reversing)
        assertEquals(listOf("q2", "q5", "q4", "q3", "q1"), manager.queue.map { it.id })

        manager.setShuffleMode(QueueManager.ShuffleMode.OFF)
        assertEquals(fixture.map { it.id }, manager.queue.map { it.id })
        assertEquals("q2", manager.currentItem?.id)
    }

    @Test
    fun `setQueueShuffled in smart mode starts on the orderer's first track`() {
        manager.setQueueShuffled(fixture, QueueManager.ShuffleMode.SMART, reversing)
        assertEquals(QueueManager.ShuffleMode.SMART, manager.shuffleMode)
        assertEquals("q5", manager.currentItem?.id)
        assertEquals(0, manager.currentIndex)
    }

    @Test
    fun `move reorders the queue and keeps the current item pointer correct`() {
        manager.next() // position 1, q2
        manager.move(4, 0) // move q5 to the front
        assertEquals(listOf("q5", "q1", "q2", "q3", "q4"), manager.queue.map { it.id })
        assertEquals("q2", manager.currentItem?.id)
        assertEquals(2, manager.currentIndex)
    }

    @Test
    fun `remove drops an item and adjusts the current position`() {
        manager.next() // position 1, q2
        manager.remove("q1")
        assertEquals(listOf("q2", "q3", "q4", "q5"), manager.queue.map { it.id })
        assertEquals("q2", manager.currentItem?.id)
        assertEquals(0, manager.currentIndex)
    }

    @Test
    fun `removing the current item falls back to the next available one`() {
        manager.remove("q1")
        assertEquals("q2", manager.currentItem?.id)
    }

    @Test
    fun `clear empties the queue`() {
        manager.clear()
        assertEquals(0, manager.size)
        assertNull(manager.currentItem)
    }

    @Test
    fun `restore puts a removed item back where it was`() {
        manager.setQueue(fixture, startIndex = 2)
        manager.remove("q2")
        assertEquals(1, manager.currentIndex)
        manager.restore("q2", 1)
        assertEquals(fixture, manager.queue)
        assertEquals(2, manager.currentIndex)
        assertEquals("q3", manager.currentItem?.id)
    }

    @Test
    fun `restart goes back to the top, keeping an unshuffled order`() {
        manager.setQueue(fixture, startIndex = 4)
        manager.move(4, 0)
        manager.restart()
        assertEquals(0, manager.currentIndex)
        assertEquals("q5", manager.currentItem?.id)
    }

    @Test
    fun `restart reshuffles a shuffled queue, still every track once`() {
        manager.setQueueShuffled(fixture)
        manager.remove("q3")
        manager.restart { items, _ -> items.indices.reversed().toList() }
        assertEquals(0, manager.currentIndex)
        assertEquals(4, manager.size)
        assertEquals(fixture.filter { it.id != "q3" }.map { it.id }.toSet(), manager.queue.map { it.id }.toSet())
    }
}
