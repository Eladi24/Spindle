package io.github.eladimany.spindle.data.smartplaylists

/**
 * How long a generated playlist is: [amount] tracks, or about [amount] minutes when
 * [byTime]. Only the slider's steps ([steps]) are offered; [snapped] puts any other
 * value (one the AI read from a request) on the nearest one.
 */
data class PlaylistLength(val byTime: Boolean, val amount: Int) {
    /** "20 tracks", "~45 min", "~1 h 30 min" — the draft's chip. */
    val label: String
        get() = if (byTime) "~" + minutesLabel(amount) else "$amount tracks"

    /** The same length in the other unit, at [TYPICAL_TRACK_MINUTES] a song. */
    fun switched(): PlaylistLength =
        if (byTime) tracks(amount / TYPICAL_TRACK_MINUTES) else minutes(amount * TYPICAL_TRACK_MINUTES)

    fun snapped(): PlaylistLength = copy(amount = steps(byTime).minBy { kotlin.math.abs(it - amount) })

    companion object {
        /** For estimates both ways ("about 11 songs"); the matcher measures real lengths. */
        const val TYPICAL_TRACK_MINUTES = 4

        /** 5-minute steps up to an hour, then 15 — fine where it matters, short slider. */
        val TIME_STEPS: List<Int> = (15..60 step 5) + (75..240 step 15)
        val TRACK_STEPS: List<Int> = (10..100 step 5).toList()

        fun steps(byTime: Boolean) = if (byTime) TIME_STEPS else TRACK_STEPS

        fun tracks(count: Int) = PlaylistLength(byTime = false, amount = count).snapped()
        fun minutes(minutes: Int) = PlaylistLength(byTime = true, amount = minutes).snapped()

        val TWENTY_TRACKS = tracks(20)
        val ONE_HOUR = minutes(60)
        val TWO_HOURS = minutes(120)

        /** "45 min", "1 h", "1 h 30 min". */
        fun minutesLabel(minutes: Int): String = when {
            minutes < 60 -> "$minutes min"
            minutes % 60 == 0 -> "${minutes / 60} h"
            else -> "${minutes / 60} h ${minutes % 60} min"
        }
    }
}

/**
 * What a generated playlist should be. Built by the fallback chip sheet today; the AI
 * engine's job later is only to turn a sentence into one of these — track selection
 * stays [PlaylistMatcher], so the AI can never pick a track the user doesn't own.
 *
 * Empty [decades] / [genres] mean "any".
 */
data class PlaylistCriteria(
    /** First year of each decade: 1990 = the 90s. */
    val decades: Set<Int> = emptySet(),
    /** Display names; compared through [Genres.key]. */
    val genres: Set<String> = emptySet(),
    val length: PlaylistLength = PlaylistLength.ONE_HOUR,
    /** Weight tracks by listening history, like smart shuffle. */
    val leanOnHistory: Boolean = true,
) {
    private val genreKeys = genres.mapTo(HashSet()) { Genres.key(it) }

    fun matches(genreTag: String?, year: Int?): Boolean =
        (decades.isEmpty() || decadeOf(year) in decades) &&
            (genres.isEmpty() || Genres.split(genreTag).any { Genres.key(it) in genreKeys })

    /**
     * Like [matches], but a missing year or genre tag doesn't rule a track out — for the
     * AI's song list, where the AI knows the song even when the file says nothing.
     */
    fun mightMatch(genreTag: String?, year: Int?): Boolean =
        (decades.isEmpty() || decadeOf(year) == null || decadeOf(year) in decades) &&
            (genres.isEmpty() || Genres.split(genreTag).let { tags -> tags.isEmpty() || tags.any { Genres.key(it) in genreKeys } })

    /** A default name from the filters: "90s Trip-hop & Indie rock", or "Library mix". */
    fun suggestedName(): String {
        val parts = listOfNotNull(
            decades.sorted().joinToString(" & ") { decadeLabel(it) }.ifEmpty { null },
            genres.sortedBy { Genres.key(it) }.joinToString(" & ").ifEmpty { null },
        )
        return parts.joinToString(" ").ifEmpty { "Library mix" }
    }

    companion object {
        fun decadeOf(year: Int?): Int? = year?.takeIf { it > 0 }?.let { it / 10 * 10 }

        /** "70s", "90s", "2000s", "2010s". */
        fun decadeLabel(decade: Int): String =
            if (decade in 1950..1999) "${decade % 100}s" else "${decade}s"
    }
}
