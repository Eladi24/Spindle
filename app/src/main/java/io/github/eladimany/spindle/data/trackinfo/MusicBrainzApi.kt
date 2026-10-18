package io.github.eladimany.spindle.data.trackinfo

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URLEncoder

/** What MusicBrainz knows about one song. Null fields: it didn't say. */
data class MusicBrainzMatch(
    val recordingId: String,
    val title: String,
    val artist: String,
    /** For [MusicBrainzApi.artistUrl] — the genre comes from the artist. */
    val artistId: String?,
    val album: String?,
    val year: Int?,
)

/**
 * MusicBrainz (musicbrainz.org/ws/2, JSON) — URLs and reading the replies. Pure, unit
 * tested; [MusicBrainzClient] does the HTTP and the 1-per-second pace.
 *
 * Measured against the live service (2026-10-18): a song has dozens of recordings —
 * remasters, remixes, live takes — and the search's own order puts the original
 * anywhere (for "Highway Star" the top hit was a 2024 remix). So no single result is
 * trusted for album/year: the year is the earliest first release among all recordings
 * of that title by that artist, the album the earliest official studio album among
 * their releases. Search results carry no genre; the artist's genres do.
 */
object MusicBrainzApi {
    private const val BASE_URL = "https://musicbrainz.org/ws/2"

    /** Below this search score a result isn't trusted, even if the names look alike. */
    private const val MIN_SCORE = 85

    fun searchUrl(artist: String, title: String): String {
        val query = "recording:\"${escape(title)}\" AND artist:\"${escape(artist)}\""
        return "$BASE_URL/recording?query=${URLEncoder.encode(query, "UTF-8")}&fmt=json&limit=100"
    }

    fun artistUrl(artistId: String) = "$BASE_URL/artist/$artistId?inc=genres&fmt=json"

    /** Inside a quoted Lucene phrase only `\` and `"` need escaping. */
    private fun escape(text: String) = text.replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * [wantTitle] by [wantArtist] in a search reply — names compared loosely
     * ([TrackInfoMerge.norm], bracketed notes like "(Remastered)" ignored). Null when no
     * recording fits.
     */
    fun bestMatch(json: String, wantArtist: String, wantTitle: String): MusicBrainzMatch? {
        val recordings = try {
            JSONObject(json).optJSONArray("recordings") ?: return null
        } catch (e: JSONException) {
            return null
        }
        val artistKey = TrackInfoMerge.norm(wantArtist)
        val titleKey = TrackInfoMerge.norm(stripNotes(wantTitle))
        val candidates = recordings.objects().filter { rec ->
            rec.optInt("score") >= MIN_SCORE &&
                TrackInfoMerge.norm(stripNotes(rec.optString("title"))) == titleKey &&
                TrackInfoMerge.norm(creditedArtist(rec)).let { it == artistKey || it.startsWith("$artistKey ") }
        }
        if (candidates.isEmpty()) return null

        val earliest = candidates.minByOrNull { it.optString("first-release-date").ifEmpty { "9999" } }!!
        val album = candidates
            .flatMap { it.optJSONArray("releases").objects() }
            .filter { release ->
                val group = release.optJSONObject("release-group")
                release.optString("status") == "Official" && release.optString("date").isNotEmpty() &&
                    group?.optString("primary-type") == "Album" &&
                    (group.optJSONArray("secondary-types")?.length() ?: 0) == 0
            }
            .minByOrNull { it.optString("date") }
        return MusicBrainzMatch(
            recordingId = earliest.optString("id"),
            title = stripNotes(earliest.optString("title")),
            artist = creditedArtist(earliest),
            artistId = earliest.optJSONArray("artist-credit")?.optJSONObject(0)?.optJSONObject("artist")
                ?.optString("id")?.ifEmpty { null },
            album = album?.optString("title")?.ifEmpty { null },
            year = candidates.mapNotNull { yearOf(it.optString("first-release-date")) }.minOrNull(),
        )
    }

    /** The artist's most-voted genre ("hard rock" → "Hard Rock"); its top tag if it has none. */
    fun artistGenre(json: String): String? {
        val artist = try {
            JSONObject(json)
        } catch (e: JSONException) {
            return null
        }
        val pick = { list: JSONArray? ->
            list.objects().filter { it.optString("name").isNotBlank() }.maxByOrNull { it.optInt("count") }
        }
        return (pick(artist.optJSONArray("genres")) ?: pick(artist.optJSONArray("tags")))
            ?.optString("name")
            ?.split(' ')
            ?.joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }
    }

    /** "Deep Purple", or "Artist feat. Other" from the credit's names and join phrases. */
    private fun creditedArtist(recording: JSONObject): String =
        recording.optJSONArray("artist-credit").objects()
            .joinToString("") { it.optString("name") + it.optString("joinphrase") }
            .trim()

    private fun yearOf(date: String?): Int? = date?.take(4)?.toIntOrNull()?.takeIf { it in 1000..2999 }

    /** "Highway Star (Remastered 2012)" → "Highway Star". */
    private fun stripNotes(title: String) = title.replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), "").trim()

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
