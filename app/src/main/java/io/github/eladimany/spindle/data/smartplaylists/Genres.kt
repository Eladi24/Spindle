package io.github.eladimany.spindle.data.smartplaylists

/**
 * Genre tags as they come out of MediaStore: free text, sometimes several genres in
 * one tag ("Rock; Blues", "Trip-hop/Electronic") and sometimes the same one twice
 * ("Rock, Rock" — seen on the A73). Everything that compares genres goes through here.
 */
object Genres {
    private val separators = Regex("\\s*[;,/|]\\s*")

    /** The distinct genres in one tag, first spelling kept, blanks dropped. */
    fun split(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(separators)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { key(it) }
    }

    /** Case- and space-insensitive identity: "Hip Hop" and "hip hop " are one genre. */
    fun key(name: String): String = name.trim().lowercase()
}
