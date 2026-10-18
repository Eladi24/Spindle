package io.github.eladimany.spindle.data.trackinfo

/** A file's own tags, with "missing" already turned into null (title is never missing). */
data class FileTags(
    val title: String,
    val artist: String?,
    val album: String?,
    val year: Int?,
    val genre: String?,
)

/** What the library shows: the file's tags with a saved fix applied. */
data class EffectiveTags(
    val title: String,
    val artist: String?,
    val album: String?,
    val year: Int?,
    val genre: String?,
    /** True when anything differs from the file. */
    val fixed: Boolean,
)

/**
 * How a [TrackInfoFixEntity] combines with a file's [FileTags]. Pure, unit tested.
 *
 * - A fix the user typed ([TrackInfoFixEntity.manual]) wins over the file, field by field.
 * - An automatic fix (split from the title, or MusicBrainz) only fills what the file
 *   lacks — real tags in the file always win. A split title applies only while the file
 *   still has no artist and its title is the one the split was made from.
 */
object TrackInfoMerge {
    /** MediaStore's word for "no tag". */
    private const val UNKNOWN = "<unknown>"

    fun isMissing(value: String?): Boolean = value.isNullOrBlank() || value.trim() == UNKNOWN

    /** For comparing names: lower case, no "the ", punctuation and extra spaces dropped. */
    fun norm(value: String): String = value.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .removePrefix("the ")
        .trim()

    fun merge(file: FileTags, fix: TrackInfoFixEntity?): EffectiveTags {
        if (fix == null) return file.unfixed()
        val result = if (fix.manual) {
            EffectiveTags(
                title = fix.title ?: file.title,
                artist = fix.artist ?: file.artist,
                album = fix.album ?: file.album,
                year = fix.year ?: file.year,
                genre = fix.genre ?: file.genre,
                fixed = false,
            )
        } else {
            val splitStillApplies = file.artist == null && (fix.splitFrom == null || fix.splitFrom == file.title)
            EffectiveTags(
                title = if (splitStillApplies && fix.title != null) fix.title else file.title,
                artist = file.artist ?: fix.artist.takeIf { splitStillApplies || fix.splitFrom == null },
                album = file.album ?: fix.album,
                year = file.year ?: fix.year,
                genre = file.genre ?: fix.genre,
                fixed = false,
            )
        }
        val changed = result.title != file.title || result.artist != file.artist || result.album != file.album ||
            result.year != file.year || result.genre != file.genre
        return result.copy(fixed = changed)
    }

    private fun FileTags.unfixed() = EffectiveTags(title, artist, album, year, genre, fixed = false)
}
