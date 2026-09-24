package io.github.eladimany.spindle.data.smartplaylists

import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.playback.TrackStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PlaylistMatcherTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    private fun track(
        id: Long,
        artistId: Long = id,
        albumId: Long = id,
        genre: String? = null,
        year: Int? = null,
        durationMs: Long = 4 * minute,
    ) = Track(
        id = id,
        uri = "content://media/external/audio/media/$id",
        title = "Track $id",
        artistId = artistId,
        artistName = "Artist $artistId",
        albumId = albumId,
        albumName = "Album $albumId",
        trackNumber = null,
        discNumber = null,
        durationMs = durationMs,
        year = year,
        genre = genre,
        dateAddedMs = 0L,
    )

    // Keyed by track id in these tests; the app keys by trackKey().
    private fun pick(
        library: List<Track>,
        criteria: PlaylistCriteria,
        stats: Map<Long, TrackStats> = emptyMap(),
        seed: Int = 1,
    ) = PlaylistMatcher.pick(library, criteria, stats, { it.id }, now, Random(seed))

    @Test
    fun `genre tags split on separators, trim, and drop repeats`() {
        assertEquals(listOf("Rock"), Genres.split("Rock, Rock"))
        assertEquals(listOf("Trip-hop", "Electronic"), Genres.split("Trip-hop/Electronic"))
        assertEquals(listOf("Rock", "blues"), Genres.split(" Rock ; blues;BLUES "))
        assertEquals(emptyList<String>(), Genres.split("  "))
        assertEquals(emptyList<String>(), Genres.split(null))
    }

    @Test
    fun `criteria match decade and genre, case-insensitively, and empty means any`() {
        val c = PlaylistCriteria(decades = setOf(1990), genres = setOf("Trip-hop"))
        assertTrue(c.matches("trip-hop; Electronic", 1994))
        assertFalse(c.matches("Trip-hop", 2001))
        assertFalse(c.matches("Rock", 1994))
        assertFalse(c.matches(null, 1994))
        assertFalse(c.matches("Trip-hop", null))
        assertTrue(PlaylistCriteria().matches(null, null))
    }

    @Test
    fun `decade labels and suggested names`() {
        assertEquals("70s", PlaylistCriteria.decadeLabel(1970))
        assertEquals("2000s", PlaylistCriteria.decadeLabel(2000))
        assertEquals(
            "90s Indie rock & Trip-hop",
            PlaylistCriteria(decades = setOf(1990), genres = setOf("Trip-hop", "Indie rock")).suggestedName(),
        )
        assertEquals("Library mix", PlaylistCriteria().suggestedName())
    }

    @Test
    fun `only matching tracks are picked`() {
        val library = (1L..40L).map { track(it, genre = if (it % 2 == 0L) "Jazz" else "Rock", year = 1995) }
        val result = pick(library, PlaylistCriteria(genres = setOf("jazz"), length = PlaylistLength.TWENTY_TRACKS))
        assertEquals(20, result.size)
        assertTrue(result.all { it.genre == "Jazz" })
    }

    @Test
    fun `twenty tracks means twenty when the library has them, and never a repeat`() {
        val library = (1L..100L).map { track(it) }
        val result = pick(library, PlaylistCriteria(length = PlaylistLength.TWENTY_TRACKS))
        assertEquals(20, result.size)
        assertEquals(20, result.map { it.id }.toSet().size)
    }

    @Test
    fun `an hour lands within one track of sixty minutes`() {
        val library = (1L..100L).map { track(it, durationMs = (3 + it % 4) * minute) }
        val total = pick(library, PlaylistCriteria(length = PlaylistLength.ONE_HOUR)).sumOf { it.durationMs }
        assertTrue("total $total", total >= 60 * minute - 90_000L)
        assertTrue("total $total", total < 60 * minute + 6 * minute)
    }

    @Test
    fun `a small match returns everything that matched`() {
        val library = (1L..5L).map { track(it, genre = "Blues") } + (6L..50L).map { track(it) }
        val result = pick(library, PlaylistCriteria(genres = setOf("Blues")))
        assertEquals((1L..5L).toSet(), result.map { it.id }.toSet())
    }

    @Test
    fun `no match gives an empty playlist`() {
        assertTrue(pick(listOf(track(1, genre = "Rock")), PlaylistCriteria(genres = setOf("Jazz"))).isEmpty())
    }

    @Test
    fun `one prolific artist is capped while others are available`() {
        // 60 tracks by artist 1, 40 by forty different artists; 20 tracks → cap 4.
        val library = (1L..60L).map { track(it, artistId = 1) } + (61L..100L).map { track(it) }
        repeat(20) { seed ->
            val result = pick(library, PlaylistCriteria(length = PlaylistLength.TWENTY_TRACKS), seed = seed)
            assertTrue(result.count { it.artistId == 1L } <= 4)
        }
    }

    @Test
    fun `the cap relaxes when there is nothing else to fill with`() {
        val library = (1L..30L).map { track(it, artistId = 1) }
        assertEquals(20, pick(library, PlaylistCriteria(length = PlaylistLength.TWENTY_TRACKS)).size)
    }

    @Test
    fun `neighbours are spread by artist when possible`() {
        val library = (1L..40L).map { track(it, artistId = it % 4, albumId = 100 + it % 4) }
        val result = pick(library, PlaylistCriteria(length = PlaylistLength.TWENTY_TRACKS))
        result.zipWithNext().forEach { (a, b) -> assertTrue(a.artistId != b.artistId) }
    }

    @Test
    fun `leaning on history favours tracks that are played to the end`() {
        val library = (1L..200L).map { track(it) }
        val favourites = (1L..20L).associateWith { TrackStats(plays = 10, fullListens = 10, skips = 0, lastPlayedMs = now - 10 * 86_400_000L) }
        val criteria = PlaylistCriteria(length = PlaylistLength.TWENTY_TRACKS)
        var withHistory = 0
        var without = 0
        repeat(50) { seed ->
            withHistory += pick(library, criteria, favourites, seed).count { it.id <= 20 }
            without += pick(library, criteria.copy(leanOnHistory = false), favourites, seed).count { it.id <= 20 }
        }
        assertTrue("with $withHistory vs without $without", withHistory > without * 2)
    }

    @Test
    fun `facets count decades and genres, most common spelling wins`() {
        val facets = LibraryFacets.from(
            listOf(
                TrackTags("Rock, Rock", 1977),
                TrackTags("rock", 1979),
                TrackTags("Rock; Blues", 1995),
                TrackTags(null, null),
            ),
        )
        assertEquals(listOf(Facet(1970, 2), Facet(1990, 1)), facets.decades)
        assertEquals(listOf(Facet("Rock", 3), Facet("Blues", 1)), facets.genres)
    }
}
