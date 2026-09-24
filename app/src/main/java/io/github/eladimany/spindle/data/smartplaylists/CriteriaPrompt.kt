package io.github.eladimany.spindle.data.smartplaylists

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** What the AI made of a request: filters over the library, plus a suggested title. */
data class Interpretation(val criteria: PlaylistCriteria, val name: String?)

/**
 * The prompt that turns a free-text request into [PlaylistCriteria], and the parser for
 * the reply. Pure (no Android, no model) so both halves are unit tested.
 *
 * The model only ever sees the genre and decade names that exist in the library — never
 * tracks — and its answer is checked against them: anything it invents is dropped. So
 * the worst a bad answer can do is a wider playlist, never a track the user doesn't own.
 */
object CriteriaPrompt {
    /** Gemini Nano's input limit is ~4000 tokens; 80 genre names stay far below it. */
    const val MAX_GENRES_IN_PROMPT = 80

    val systemInstruction = """
        You turn a music request into filters for the user's own music library.
        Reply with ONE JSON object and nothing else:
        {"genres": [...], "decades": [...], "length": "...", "name": "..."}
        - genres: only exact names from GENRES that fit the request. [] means any genre.
        - decades: only numbers from DECADES (1990 means the 90s). [] means any year.
        - For a mood or activity (mellow, party, focus, rainy day), choose the genres
          and decades that usually fit it.
        - length: "20_tracks", "1_hour" or "2_hours". Use "1_hour" unless the request says otherwise.
        - name: a short playlist title, at most 4 words, in the language of the request.
    """.trimIndent()

    fun userText(request: String, facets: LibraryFacets): String = buildString {
        append("GENRES: ")
        append(facets.genres.take(MAX_GENRES_IN_PROMPT).joinToString(", ") { it.value }.ifEmpty { "(none)" })
        append("\nDECADES: ")
        append(facets.decades.joinToString(", ") { it.value.toString() }.ifEmpty { "(none)" })
        append("\nREQUEST: ")
        append(request.trim())
    }

    /**
     * Reads the model's reply. Tolerates a code fence or chatter around the object;
     * returns null when there's no usable JSON at all. [default] supplies the length and
     * history switch the user picked, used when the reply doesn't say otherwise.
     */
    fun parse(reply: String, facets: LibraryFacets, default: PlaylistCriteria): Interpretation? {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val json = try {
            JSONObject(reply.substring(start, end + 1))
        } catch (e: JSONException) {
            return null
        }

        val genresByKey = facets.genres.associate { Genres.key(it.value) to it.value }
        val genres = json.optJSONArray("genres").strings()
            .mapNotNullTo(LinkedHashSet()) { genresByKey[Genres.key(it)] }

        val known = facets.decades.mapTo(HashSet()) { it.value }
        val decades = json.optJSONArray("decades").strings()
            .mapNotNull { decadeFrom(it) }
            .filterTo(LinkedHashSet()) { it in known }

        val length = when (json.optString("length")) {
            "20_tracks" -> PlaylistLength.TWENTY_TRACKS
            "1_hour" -> PlaylistLength.ONE_HOUR
            "2_hours" -> PlaylistLength.TWO_HOURS
            else -> default.length
        }
        val name = json.optString("name").trim().take(40).ifEmpty { null }
        return Interpretation(default.copy(genres = genres, decades = decades, length = length), name)
    }

    /** "1990", 1990, "90s", "1990s" → 1990. */
    private fun decadeFrom(raw: String): Int? {
        val digits = raw.trim().removeSuffix("s").removePrefix("'")
        val n = digits.toIntOrNull() ?: return null
        val year = if (n in 0..99) (if (n >= 30) 1900 + n else 2000 + n) else n
        return PlaylistCriteria.decadeOf(year)
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { opt(it).toString() }
}
