package io.github.eladimany.spindle.data.smartplaylists

import io.github.eladimany.spindle.core.model.QueueItem
import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.playback.SmartShuffle
import io.github.eladimany.spindle.playback.SmartShuffleRules
import io.github.eladimany.spindle.playback.TrackStats
import kotlin.math.ln
import kotlin.random.Random

/** One chip on the builder sheet: a decade or genre and how many tracks carry it. */
data class Facet<T>(val value: T, val count: Int)

/** The decades and genres that actually occur in the library, for the builder's chips. */
data class LibraryFacets(
    /** Oldest first. */
    val decades: List<Facet<Int>>,
    /** Most tracks first. */
    val genres: List<Facet<String>>,
) {
    companion object {
        fun from(tags: List<TrackTags>): LibraryFacets {
            val decades = tags.mapNotNull { PlaylistCriteria.decadeOf(it.year) }
                .groupingBy { it }.eachCount()
                .map { Facet(it.key, it.value) }
                .sortedBy { it.value }
            // Count by key, show the most common spelling.
            val spellings = HashMap<String, MutableMap<String, Int>>()
            for (tag in tags) {
                for (genre in Genres.split(tag.genre)) {
                    spellings.getOrPut(Genres.key(genre)) { HashMap() }.merge(genre, 1, Int::plus)
                }
            }
            val genres = spellings.values
                .map { byName -> Facet(byName.maxBy { it.value }.key, byName.values.sum()) }
                .sortedWith(compareByDescending<Facet<String>> { it.count }.thenBy { Genres.key(it.value) })
            return LibraryFacets(decades, genres)
        }
    }
}

/** The two tags the builder filters on — a light projection of every track. */
data class TrackTags(val genre: String?, val year: Int?)

/**
 * Picks and orders a playlist from the library. Pure (no Android, no I/O), unit tested.
 *
 * 1. Candidates = tracks matching [PlaylistCriteria.matches].
 * 2. A weighted random draw (Efraimidis–Spirakis, same as [SmartShuffle]): with
 *    [PlaylistCriteria.leanOnHistory], favourites come up more, often-skipped less,
 *    long-unheard tracks resurface — [SmartShuffle.weight] with every rule on.
 * 3. Take tracks in draw order until the length is reached, at most [artistCap] per
 *    artist so one prolific artist can't fill the list; if that runs short, the capped
 *    tracks fill the rest.
 * 4. [SmartShuffle.spread] so neighbours don't share an artist or album.
 *
 * For an AI request, 1–2 make a shortlist instead ([songsForAi]), the AI ranks it
 * ([SongPickPrompt]), and 3–4 run over the AI's ranking ([fill]).
 */
object PlaylistMatcher {
    private const val MINUTE_MS = 60_000L

    /** A duration target is met once within this of the goal (the last track overshoots). */
    private const val DURATION_SLACK_MS = 90_000L

    /** For sizing the artist cap before the tracks are known. */
    private const val TYPICAL_TRACK_MS = 4 * MINUTE_MS

    private val historyRules = SmartShuffleRules()

    fun pick(
        library: List<Track>,
        criteria: PlaylistCriteria,
        statsByKey: Map<Long, TrackStats>,
        keyOf: (Track) -> Long,
        nowMs: Long,
        random: Random = Random.Default,
    ): List<Track> {
        val candidates = library.filter { criteria.matches(it.genre, it.year) }
        if (candidates.isEmpty()) return emptyList()
        return fill(draw(candidates, criteria, statsByKey, keyOf, nowMs, random), criteria.length)
    }

    /**
     * The shortlist the AI picks from ([SongPickPrompt]), at most [size] songs: tracks
     * matching the filters first, in the same weighted draw as [pick] — so each request
     * and Remix sees a different slice of a big match — then tracks only missing a tag
     * the filters ask about ([PlaylistCriteria.mightMatch]): the AI knows the song even
     * when the file doesn't say.
     */
    fun songsForAi(
        library: List<Track>,
        criteria: PlaylistCriteria,
        statsByKey: Map<Long, TrackStats>,
        keyOf: (Track) -> Long,
        nowMs: Long,
        size: Int,
        random: Random = Random.Default,
    ): List<Track> {
        val (matching, rest) = library.partition { criteria.matches(it.genre, it.year) }
        val untagged = rest.filter { criteria.mightMatch(it.genre, it.year) }
        return (draw(matching, criteria, statsByKey, keyOf, nowMs, random) +
            draw(untagged, criteria, statsByKey, keyOf, nowMs, random)).take(size)
    }

    /** About how many tracks [length] takes, judged from [songs]' own lengths. */
    fun expectedCount(length: PlaylistLength, songs: List<Track>): Int {
        val target = Target.of(length)
        if (target.tracks != null) return target.tracks
        val typical = songs.map { it.durationMs }.filter { it > 0 }.sorted()
            .let { if (it.isEmpty()) TYPICAL_TRACK_MS else it[it.size / 2] }
        return (target.durationMs!! / typical).toInt().coerceAtLeast(1)
    }

    /**
     * Takes [ranked] in order until [length] is reached, at most [artistCap] per artist
     * so one prolific artist can't fill the list; if that runs short, the capped tracks
     * fill the rest. Then spreads it out so neighbours don't share an artist or album.
     */
    fun fill(ranked: List<Track>, length: PlaylistLength): List<Track> {
        val target = Target.of(length)
        val cap = artistCap(target)
        val picked = mutableListOf<Track>()
        val held = mutableListOf<Track>()
        val perArtist = HashMap<Long, Int>()
        var totalMs = 0L
        fun done() = target.isMet(picked.size, totalMs)

        for (track in ranked) {
            if (done()) break
            if ((perArtist[track.artistId] ?: 0) >= cap) {
                held += track
                continue
            }
            picked += track
            totalMs += track.durationMs
            perArtist.merge(track.artistId, 1, Int::plus)
        }
        for (track in held) {
            if (done()) break
            picked += track
            totalMs += track.durationMs
        }
        return spreadOut(picked)
    }

    /** Weighted random order (Efraimidis–Spirakis), by listening history when asked. */
    private fun draw(
        tracks: List<Track>,
        criteria: PlaylistCriteria,
        statsByKey: Map<Long, TrackStats>,
        keyOf: (Track) -> Long,
        nowMs: Long,
        random: Random,
    ): List<Track> = tracks
        .map { track ->
            val w = if (criteria.leanOnHistory) {
                SmartShuffle.weight(statsByKey[keyOf(track)], historyRules, nowMs)
            } else {
                1.0
            }
            // nextDouble() can return 0.0; ln(0) would tie everything at -inf.
            val u = random.nextDouble().coerceAtLeast(Double.MIN_VALUE)
            track to ln(u) / w
        }
        .sortedByDescending { it.second }
        .map { it.first }

    /** At most a fifth of the playlist from one artist, but never fewer than 2. */
    internal fun artistCap(target: Target): Int {
        val expectedTracks = target.tracks ?: (target.durationMs!! / TYPICAL_TRACK_MS).toInt()
        return maxOf(2, expectedTracks / 5)
    }

    private fun spreadOut(tracks: List<Track>): List<Track> {
        val items = tracks.mapIndexed { i, t -> QueueItem(id = "g$i", track = t) }
        val order = items.indices.toMutableList()
        SmartShuffle.spread(order, items)
        return order.map { tracks[it] }
    }

    internal data class Target(val tracks: Int?, val durationMs: Long?) {
        fun isMet(count: Int, totalMs: Long): Boolean =
            if (tracks != null) count >= tracks else totalMs >= durationMs!! - DURATION_SLACK_MS

        companion object {
            fun of(length: PlaylistLength) =
                if (length.byTime) Target(tracks = null, durationMs = length.amount * MINUTE_MS)
                else Target(tracks = length.amount, durationMs = null)
        }
    }
}
