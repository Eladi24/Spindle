package io.github.eladimany.spindle.playback

import io.github.eladimany.spindle.core.model.QueueItem
import io.github.eladimany.spindle.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MoreLikeThisTest {

    private fun track(id: Long, artistId: Long = id, genre: String? = null, year: Int? = null) = Track(
        id = id,
        uri = "content://media/external/audio/media/$id",
        title = "Track $id",
        artistId = artistId,
        artistName = "Artist $artistId",
        albumId = id,
        albumName = "Album $id",
        trackNumber = null,
        discNumber = null,
        durationMs = 240_000L,
        year = year,
        genre = genre,
        dateAddedMs = 0L,
    )

    private fun item(t: Track) = QueueItem(id = "q${t.id}", track = t)

    private val seed = track(1, artistId = 50, genre = "Hard rock", year = 1980)

    @Test
    fun `queue songs come first, then the library, and unlike songs never`() {
        val upcoming = listOf(item(track(2, genre = "Hard Rock")), item(track(3, genre = "Jazz", year = 1955)))
        val library = listOf(track(4, genre = "hard rock; Metal"), track(5, genre = "Pop"), track(6, genre = "Jazz", year = 1983))
        val picks = MoreLikeThis.pick(seed, upcoming, library, setOf(1L, 2L, 3L), Random(1))
        assertEquals(setOf(2L, 4L), picks.map { it.track.id }.toSet())
        assertEquals("q2", picks.first { it.track.id == 2L }.queueItem?.id)
        assertEquals("Hard rock · from your library", picks.first { it.track.id == 4L }.reason)
    }

    @Test
    fun `the same artist counts on its own, a shared decade alone doesn't`() {
        val library = listOf(track(2, artistId = 50), track(3, year = 1984), track(4, year = 1981, genre = "Hard rock"))
        val picks = MoreLikeThis.pick(seed, emptyList(), library, setOf(1L), Random(1))
        assertEquals(setOf(2L, 4L), picks.map { it.track.id }.toSet())
        assertEquals("same artist · from your library", picks.first { it.track.id == 2L }.reason)
    }

    @Test
    fun `at most eight, at most three per artist, never the seed or songs already queued`() {
        val library = (2L..40L).map { track(it, artistId = if (it < 20) 50 else it, genre = "Hard rock") } + seed
        val picks = MoreLikeThis.pick(seed, emptyList(), library, setOf(1L, 30L), Random(2))
        assertEquals(MoreLikeThis.COUNT, picks.size)
        assertTrue(picks.count { it.track.artistId == 50L } <= MoreLikeThis.ARTIST_CAP)
        assertTrue(picks.none { it.track.id == 1L || it.track.id == 30L })
    }

    @Test
    fun `a song with no tags finds only its own artist`() {
        val bare = track(1, artistId = 7)
        val library = listOf(track(2, artistId = 7), track(3, genre = "Rock", year = 1990))
        assertEquals(listOf(2L), MoreLikeThis.pick(bare, emptyList(), library, setOf(1L)).map { it.track.id })
    }
}
