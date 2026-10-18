package io.github.eladimany.spindle.data.trackinfo

/** "Deep Purple - Highway Star" read as artist + title, for a track whose file has no artist. */
data class SplitSuggestion(
    val artist: String,
    val title: String,
    val trackNumber: Int?,
    /** [FixSource.TITLE] or [FixSource.FILE_NAME]. */
    val source: FixSource,
    /**
     * False when neither side is an artist already in the library — the left side is
     * taken as the artist, but the review screen asks the user to check.
     */
    val sure: Boolean,
    /** The text it was read from, shown on the review screen. */
    val readFrom: String,
)

/**
 * Finds "Artist - Title" in a track's title, or else its file name. Pure, unit tested.
 * Offline — MusicBrainz can confirm the result later.
 */
object TitleSplitter {
    private val SEPARATORS = listOf(" - ", " – ", " — ", " -- ")

    /** "03. ", "03 - ", "3) ", "03 " at the start of a file name. */
    private val LEADING_NUMBER = Regex("^(\\d{1,3})\\s*[.)_-]?\\s+")

    /**
     * A right side like these is a version note, not a title: "Highway Star - Remastered
     * 2012" mustn't make "Highway Star" an artist.
     */
    private val VERSION_NOTE = Regex(
        "^(\\d{4}\\s+)?(remaster|live\\b|remix|mix\\b|edit\\b|version|mono\\b|stereo\\b|acoustic|demo\\b|" +
            "instrumental|radio|bonus|single\\b|original|extended|feat)",
        RegexOption.IGNORE_CASE,
    )

    /**
     * [knownArtists] are [TrackInfoMerge.norm]ed names already tagged in the library —
     * they decide which side is the artist.
     */
    fun suggest(title: String, fileName: String, knownArtists: Set<String>): SplitSuggestion? =
        split(title, FixSource.TITLE, knownArtists)
            ?: split(fileName.substringBeforeLast('.'), FixSource.FILE_NAME, knownArtists)
                ?.copy(readFrom = fileName)

    private fun split(raw: String, source: FixSource, knownArtists: Set<String>): SplitSuggestion? {
        val text = raw.replace('_', ' ').trim()
        val number = LEADING_NUMBER.find(text)
        val body = if (number != null) text.substring(number.range.last + 1).trim() else text
        val separator = SEPARATORS.firstOrNull { body.contains(it) } ?: return null
        val left = body.substringBefore(separator).trim()
        val right = body.substringAfter(separator).trim()
        if (left.isEmpty() || right.isEmpty() || VERSION_NOTE.containsMatchIn(right)) return null

        val trackNumber = number?.groupValues?.get(1)?.toIntOrNull()
        val leftKnown = TrackInfoMerge.norm(left) in knownArtists
        val rightKnown = TrackInfoMerge.norm(right) in knownArtists
        return when {
            leftKnown -> SplitSuggestion(left, right, trackNumber, source, sure = true, readFrom = raw)
            rightKnown -> SplitSuggestion(right, left, trackNumber, source, sure = true, readFrom = raw)
            else -> SplitSuggestion(left, right, trackNumber, source, sure = false, readFrom = raw)
        }
    }
}
