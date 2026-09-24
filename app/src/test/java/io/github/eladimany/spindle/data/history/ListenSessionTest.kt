package io.github.eladimany.spindle.data.history

import io.github.eladimany.spindle.core.model.QueueItem
import io.github.eladimany.spindle.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ListenSessionTest {

    private val item = QueueItem(
        id = "q1",
        track = Track(
            id = 1, uri = "content://x/1", title = "Song", artistId = 1, artistName = "Artist",
            albumId = 1, albumName = "Album", trackNumber = 1, discNumber = 1,
            durationMs = 200_000, year = null, genre = null, dateAddedMs = 0,
        ),
    )

    @Test
    fun repeatedPlayingEmissionsCountOnce() {
        val s = ListenSession(item, startedAtMs = 0)
        s.onPlaying(1_000)
        s.onPlaying(5_000) // position update, not a restart
        s.onPlaying(9_000)
        assertEquals(10_000, s.listenedMs(11_000))
    }

    @Test
    fun pausesAreExcluded() {
        val s = ListenSession(item, startedAtMs = 0)
        s.onPlaying(0)
        s.onNotPlaying(10_000)   // paused
        s.onNotPlaying(50_000)   // still paused
        s.onPlaying(60_000)
        assertEquals(15_000, s.listenedMs(65_000))
    }

    @Test
    fun neverPlayedIsZero() {
        val s = ListenSession(item, startedAtMs = 0)
        s.onNotPlaying(3_000) // buffering only
        assertEquals(0, s.listenedMs(4_000))
    }

    @Test
    fun trackKeyIgnoresCaseAndWhitespace() {
        assertEquals(trackKey("Artist", "Album", "Song"), trackKey(" artist", "ALBUM ", "song"))
        assertNotEquals(trackKey("Artist", "Album", "Song"), trackKey("Artist", "Album", "Song 2"))
        // Separator prevents field-boundary collisions.
        assertNotEquals(trackKey("ab", "c", "d"), trackKey("a", "bc", "d"))
    }
}
