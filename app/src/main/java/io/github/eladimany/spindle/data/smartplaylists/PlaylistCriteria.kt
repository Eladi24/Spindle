package io.github.eladimany.spindle.data.smartplaylists

enum class PlaylistLength(val label: String) {
    TWENTY_TRACKS("20 tracks"),
    ONE_HOUR("~1 hour"),
    TWO_HOURS("~2 hours"),
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
