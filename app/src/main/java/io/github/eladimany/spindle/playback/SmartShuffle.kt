package io.github.eladimany.spindle.playback

import io.github.eladimany.spindle.core.model.QueueItem
import kotlin.math.ln
import kotlin.random.Random

/** One track's listening history, summed over its listens. See [SmartShuffle.weight]. */
data class TrackStats(
    val plays: Int,
    /** Listens where at least 80% of the track was heard — not just COMPLETED, since a
     * seek to the last few seconds also ends as COMPLETED. */
    val fullListens: Int,
    /** Skipped (next pressed) before half the track was heard. */
    val skips: Int,
    val lastPlayedMs: Long?,
)

/** The four switches on the Smart shuffle sheet. All on by default. */
data class SmartShuffleRules(
    val spreadArtists: Boolean = true,
    val favourites: Boolean = true,
    val holdBackSkipped: Boolean = true,
    val rediscover: Boolean = true,
)

/**
 * Smart shuffle — rules plus listening history, no AI. Pure (no Android, no I/O) so
 * it's unit tested; ordering the whole library is a few milliseconds.
 *
 * 1. A weighted shuffle: every track still appears exactly once (the same "each track
 *    once per pass" promise as plain shuffle), but tracks with a higher [weight] tend
 *    to come earlier. Efraimidis–Spirakis: sort by `ln(u) / weight`, u uniform in (0,1).
 * 2. Then, if [SmartShuffleRules.spreadArtists], a pass that pulls the nearest later
 *    track forward whenever two neighbours share an artist or an album.
 */
object SmartShuffle {
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Fewer listens than this and a track's favourite/skip rates are just noise. */
    private const val MIN_PLAYS_FOR_RATES = 2
    private const val REDISCOVER_AFTER_MS = 60 * DAY_MS
    private const val RECENT_MS = DAY_MS

    fun weight(stats: TrackStats?, rules: SmartShuffleRules, nowMs: Long): Double {
        if (stats == null || stats.plays == 0) return 1.0
        var w = 1.0
        if (stats.plays >= MIN_PLAYS_FOR_RATES) {
            if (rules.favourites) w *= 1.0 + 2.0 * stats.fullListens / stats.plays
            if (rules.holdBackSkipped && stats.skips * 2 >= stats.plays) w *= 0.25
        }
        if (rules.rediscover && stats.lastPlayedMs != null) {
            val age = nowMs - stats.lastPlayedMs
            if (age >= REDISCOVER_AFTER_MS) w *= 2.0
            else if (age < RECENT_MS) w *= 0.5
        }
        return w
    }

    /**
     * A play order over [items]' indices. [pinnedFirst] (the track playing now, when
     * smart shuffle is turned on mid-queue) stays at the front.
     */
    fun order(
        items: List<QueueItem>,
        statsByKey: Map<Long, TrackStats>,
        keyOf: (QueueItem) -> Long,
        rules: SmartShuffleRules,
        nowMs: Long,
        pinnedFirst: Int? = null,
        random: Random = Random.Default,
    ): List<Int> {
        val rest = items.indices
            .filter { it != pinnedFirst }
            .map { i ->
                val w = weight(statsByKey[keyOf(items[i])], rules, nowMs)
                // nextDouble() can return 0.0; ln(0) would tie everything at -inf.
                val u = random.nextDouble().coerceAtLeast(Double.MIN_VALUE)
                i to ln(u) / w
            }
            .sortedByDescending { it.second }
            .map { it.first }
        val order = (listOfNotNull(pinnedFirst) + rest).toMutableList()
        if (rules.spreadArtists) spread(order, items)
        return order
    }

    /**
     * Rebuilds [order] so neighbours don't share an artist or album, staying as close
     * to the weighted order as it can: each slot takes the earliest remaining track
     * that doesn't clash with the previous one. Plain greedy paints itself into a
     * corner (one artist's leftovers bunched at the end), so when an artist has so
     * many tracks left that it needs every other remaining slot, it goes next. The
     * first track (possibly pinned) never moves. When no order avoids a clash (one
     * artist fills the tail), the clash stays.
     */
    internal fun spread(order: MutableList<Int>, items: List<QueueItem>) {
        if (order.size < 3) return
        fun artist(i: Int) = items[i].track.artistId
        fun clash(a: Int, b: Int) =
            artist(a) == artist(b) || items[a].track.albumId == items[b].track.albumId

        val remaining = order.subList(1, order.size).toMutableList()
        val left = remaining.groupingBy { artist(it) }.eachCount().toMutableMap()
        val result = mutableListOf(order[0])
        while (remaining.isNotEmpty()) {
            val prev = result.last()
            val n = remaining.size
            val crowded = left.entries
                .firstOrNull { (a, count) -> count * 2 > n && a != artist(prev) }
                ?.key
            val pick = when {
                crowded != null ->
                    remaining.indexOfFirst { artist(it) == crowded && !clash(prev, it) }
                        .takeIf { it >= 0 }
                        ?: remaining.indexOfFirst { artist(it) == crowded }
                else -> remaining.indexOfFirst { !clash(prev, it) }.takeIf { it >= 0 } ?: 0
            }
            val next = remaining.removeAt(pick)
            left.merge(artist(next), -1, Int::plus)
            result += next
        }
        order.clear()
        order.addAll(result)
    }
}
