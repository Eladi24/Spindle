package io.github.eladimany.spindle.playback

import io.github.eladimany.spindle.core.model.QueueItem
import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.data.smartplaylists.Genres
import io.github.eladimany.spindle.data.smartplaylists.PlaylistCriteria
import kotlin.random.Random

/**
 * Swipe right in the queue: up to [COUNT] songs like the swiped one, to play right after
 * it. Pure, unit tested. Rules, no AI — instant and offline; similar = same artist,
 * shared genre, same or neighbouring decade, from the tags the library already has.
 *
 * Songs still to come in the queue go first (the user picked them), then the rest of the
 * library. At most [ARTIST_CAP] by one artist, and spread so neighbours differ.
 */
object MoreLikeThis {
    const val COUNT = 8
    const val ARTIST_CAP = 3

    /** Below this a song isn't "like" the seed: a shared decade alone isn't enough. */
    private const val MIN_SCORE = 2.0

    /** One song to place, and why — shown under it in the queue. */
    data class Pick(val track: Track, val queueItem: QueueItem?, val reason: String)

    /**
     * [upcoming]: the queue after the swiped row, in play order. [library]: every track;
     * anything already in [queueTrackIds] (played or still to come) is left out of it.
     */
    fun pick(
        seed: Track,
        upcoming: List<QueueItem>,
        library: List<Track>,
        queueTrackIds: Set<Long>,
        random: Random = Random.Default,
    ): List<Pick> {
        val seedGenres = Genres.split(seed.genre).associateBy { Genres.key(it) }
        val seedDecade = PlaylistCriteria.decadeOf(seed.year)

        fun ranked(tracks: List<Pair<Track, QueueItem?>>) = tracks
            .filter { (t, _) -> t.id != seed.id }
            .map { (t, q) -> Triple(t, q, score(seed, seedGenres.keys, seedDecade, t)) }
            .filter { it.third >= MIN_SCORE }
            // A random tiebreak, so the same swipe doesn't always give the same songs.
            .sortedWith(compareByDescending<Triple<Track, QueueItem?, Double>> { it.third }.thenBy { random.nextDouble() })

        val fromQueue = ranked(upcoming.distinctBy { it.track.id }.map { it.track to it })
        val fromLibrary = ranked(library.filter { it.id !in queueTrackIds }.map { it to null })

        val picks = mutableListOf<Pick>()
        val perArtist = HashMap<Long, Int>()
        for ((track, item, _) in fromQueue + fromLibrary) {
            if (picks.size >= COUNT) break
            if (picks.any { it.track.id == track.id }) continue
            if ((perArtist[track.artistId] ?: 0) >= ARTIST_CAP) continue
            perArtist.merge(track.artistId, 1, Int::plus)
            val why = reason(seed, seedGenres, seedDecade, track)
            picks += Pick(track, item, "$why · from your ${if (item != null) "queue" else "library"}")
        }
        return spread(picks)
    }

    internal fun score(seed: Track, seedGenres: Set<String>, seedDecade: Int?, t: Track): Double {
        var s = 0.0
        if (t.artistId == seed.artistId) s += 3.0
        if (seedGenres.isNotEmpty() && Genres.split(t.genre).any { Genres.key(it) in seedGenres }) s += 2.0
        val decade = PlaylistCriteria.decadeOf(t.year)
        if (seedDecade != null && decade != null) {
            if (decade == seedDecade) s += 1.0 else if (kotlin.math.abs(decade - seedDecade) == 10) s += 0.5
        }
        return s
    }

    private fun reason(seed: Track, seedGenres: Map<String, String>, seedDecade: Int?, t: Track): String {
        if (t.artistId == seed.artistId) return "same artist"
        Genres.split(t.genre).firstNotNullOfOrNull { seedGenres[Genres.key(it)] }?.let { return it }
        return seedDecade?.let { PlaylistCriteria.decadeLabel(it) } ?: "similar"
    }

    private fun spread(picks: List<Pick>): List<Pick> {
        val items = picks.mapIndexed { i, p -> QueueItem(id = "m$i", track = p.track) }
        val order = items.indices.toMutableList()
        SmartShuffle.spread(order, items)
        return order.map { picks[it] }
    }
}
