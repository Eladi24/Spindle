package io.github.eladimany.spindle.data.smartplaylists

import io.github.eladimany.spindle.core.model.Track
import org.json.JSONException
import org.json.JSONObject

/**
 * The second AI step: after [CriteriaPrompt] narrowed the library by genre and decade,
 * the AI sees a shortlist of real songs ("Artist – Title") and picks the ones that fit
 * the request. Tags can't say whether a rock song is a ballad or a sprint; the song's
 * name lets the model use what it knows about it. Pure, unit tested.
 *
 * Songs are numbered by position in the shortlist; the reply is those numbers, so the
 * worst a bad answer can do is pick an unfitting song the user owns — never a foreign one.
 */
object SongPickPrompt {
    fun systemInstruction(count: Int) = """
        You pick songs for a playlist from the user's own music library.
        Judge each song by what you know about it — tempo, energy, mood, style — not only
        by its artist or genre. If you don't know a song, judge by the artist's usual sound.
        Reply with ONE JSON object and nothing else: {"ids": [...]}
        - ids: numbers from SONGS, best fit first, about $count of them.
        - Leave out songs that don't fit the request, even if that means fewer.
    """.trimIndent()

    fun userText(request: String, songs: List<Track>): String = buildString {
        append("REQUEST: ")
        append(request.trim())
        append("\nSONGS (number | artist – title | year):")
        songs.forEachIndexed { i, track ->
            append('\n')
            append(i)
            append(" | ")
            append(line(track))
        }
    }

    /** One song, one line: the fewer tokens, the more songs fit Gemini Nano's small input. */
    internal fun line(track: Track): String = buildString {
        append(track.artistName.oneLine().ifEmpty { "Unknown artist" })
        append(" – ")
        append(track.title.oneLine())
        track.year?.takeIf { it > 0 }?.let { append(" | ").append(it) }
    }

    /**
     * The picked songs in the AI's order, from [songs]. Tolerates a code fence or chatter
     * around the object, numbers as strings, repeats and out-of-range numbers (dropped).
     * Null when there's no usable JSON at all.
     */
    fun parse(reply: String, songs: List<Track>): List<Track>? {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val ids = try {
            JSONObject(reply.substring(start, end + 1)).optJSONArray("ids") ?: return null
        } catch (e: JSONException) {
            return null
        }
        return (0 until ids.length())
            .mapNotNull { ids.opt(it)?.toString()?.trim()?.toIntOrNull() }
            .filter { it in songs.indices }
            .distinct()
            .map { songs[it] }
    }

    private fun String.oneLine() = replace(Regex("\\s+"), " ").trim()
}
