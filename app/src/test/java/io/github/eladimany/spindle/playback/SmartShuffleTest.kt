package io.github.eladimany.spindle.playback

import io.github.eladimany.spindle.core.model.QueueItem
import io.github.eladimany.spindle.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SmartShuffleTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000
    private val allOn = SmartShuffleRules()
    private val allOff = SmartShuffleRules(false, false, false, false, false)

    private fun item(id: Long, artistId: Long = id, albumId: Long = id) = QueueItem(
        id = "q$id",
        track = Track(
            id = id,
            uri = "content://media/external/audio/media/$id",
            title = "Track $id",
            artistId = artistId,
            artistName = "Artist $artistId",
            albumId = albumId,
            albumName = "Album $albumId",
            trackNumber = null,
            discNumber = null,
            durationMs = 200_000L,
            year = null,
            genre = null,
            dateAddedMs = 0L,
        ),
    )

    // Keyed by track id in these tests; the app keys by trackKey().
    private fun order(
        items: List<QueueItem>,
        stats: Map<Long, TrackStats> = emptyMap(),
        rules: SmartShuffleRules = allOn,
        pinned: Int? = null,
        seed: Int = 1,
    ) = SmartShuffle.order(items, stats, { it.track.id }, rules, now, pinned, Random(seed))

    @Test
    fun `every track appears exactly once`() {
        val items = (1L..300L).map { item(it, artistId = it % 7, albumId = it % 23) }
        val stats = (1L..300L step 3).associateWith { TrackStats(plays = 5, fullListens = 4, skips = 1, lastPlayedMs = now - it * day) }
        val result = order(items, stats)
        assertEquals(items.indices.toList(), result.sorted())
    }

    @Test
    fun `pinned track stays first`() {
        val items = (1L..50L).map { item(it) }
        repeat(20) { seed -> assertEquals(17, order(items, pinned = 17, seed = seed).first()) }
    }

    @Test
    fun `spread keeps same-artist tracks apart when possible`() {
        // 3 artists x 10 tracks — a perfect spread exists.
        val items = (0L until 30L).map { item(it, artistId = it % 3, albumId = 100 + it % 3) }
        repeat(20) { seed ->
            val result = order(items, seed = seed)
            result.zipWithNext().forEach { (a, b) ->
                assertTrue(items[a].track.artistId != items[b].track.artistId)
            }
        }
    }

    @Test
    fun `spread also separates tracks from the same album`() {
        // Different artists (a compilation), same two albums.
        val items = (0L until 20L).map { item(it, artistId = it, albumId = it % 2) }
        val result = order(items)
        result.zipWithNext().forEach { (a, b) -> assertTrue(items[a].track.albumId != items[b].track.albumId) }
    }

    @Test
    fun `one artist only still gives a full order`() {
        val items = (1L..10L).map { item(it, artistId = 1, albumId = 1) }
        assertEquals(items.indices.toList(), order(items).sorted())
    }

    @Test
    fun `no history weighs the same as plain shuffle`() {
        assertEquals(1.0, SmartShuffle.weight(null, allOn, now), 0.0)
        assertEquals(1.0, SmartShuffle.weight(TrackStats(0, 0, 0, null), allOn, now), 0.0)
    }

    @Test
    fun `favourites weigh up to three times`() {
        val loved = TrackStats(plays = 4, fullListens = 4, skips = 0, lastPlayedMs = now - 10 * day)
        assertEquals(3.0, SmartShuffle.weight(loved, allOn, now), 1e-9)
    }

    @Test
    fun `often skipped tracks are held back`() {
        val skipped = TrackStats(plays = 4, fullListens = 0, skips = 2, lastPlayedMs = now - 10 * day)
        assertEquals(0.25, SmartShuffle.weight(skipped, allOn, now), 1e-9)
    }

    @Test
    fun `a single listen is too little to judge`() {
        val once = TrackStats(plays = 1, fullListens = 0, skips = 1, lastPlayedMs = now - 10 * day)
        assertEquals(1.0, SmartShuffle.weight(once, allOn, now), 0.0)
    }

    @Test
    fun `rediscover favours long-unheard and delays just-heard`() {
        val old = TrackStats(plays = 1, fullListens = 1, skips = 0, lastPlayedMs = now - 90 * day)
        val recent = TrackStats(plays = 1, fullListens = 1, skips = 0, lastPlayedMs = now - 60_000)
        assertEquals(2.0, SmartShuffle.weight(old, allOn, now), 0.0)
        assertEquals(0.5, SmartShuffle.weight(recent, allOn, now), 0.0)
    }

    @Test
    fun `rules switched off ignore history`() {
        val stats = TrackStats(plays = 6, fullListens = 0, skips = 6, lastPlayedMs = now - 60_000)
        assertEquals(1.0, SmartShuffle.weight(stats, allOff, now), 0.0)
    }

    @Test
    fun `a favourite tends to come earlier than an often-skipped track`() {
        val items = (1L..40L).map { item(it) }
        val stats = mapOf(
            1L to TrackStats(plays = 10, fullListens = 10, skips = 0, lastPlayedMs = now - 10 * day),
            2L to TrackStats(plays = 10, fullListens = 0, skips = 10, lastPlayedMs = now - 10 * day),
        )
        var favouriteSum = 0
        var skippedSum = 0
        repeat(400) { seed ->
            val result = order(items, stats, rules = allOn.copy(spreadArtists = false), seed = seed)
            favouriteSum += result.indexOf(0)
            skippedSum += result.indexOf(1)
        }
        // Weights 3 vs 0.25 among 38 tracks of weight 1: expected positions ≈ 7 vs 30.
        assertTrue("favourite avg ${favouriteSum / 400}", favouriteSum / 400 < 12)
        assertTrue("skipped avg ${skippedSum / 400}", skippedSum / 400 > 24)
    }

    @Test
    fun `a boost triples the weight, and only while its rule is on`() {
        assertEquals(SmartShuffle.BOOST, SmartShuffle.weight(null, allOn, now, boosted = true), 1e-9)
        assertEquals(1.0, SmartShuffle.weight(null, allOn.copy(boosted = false), now, boosted = true), 1e-9)
        assertEquals(1.0, SmartShuffle.weight(null, allOn, now, boosted = false), 1e-9)
    }
}
