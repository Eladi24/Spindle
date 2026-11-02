package io.github.eladimany.spindle.data.covers

import org.json.JSONObject

/** One album Deezer offered for a lookup: its title, artist and largest cover. */
data class CoverCandidate(val title: String, val artist: String, val coverUrl: String)

/**
 * Parses Deezer's public `/search/album` response and ranks it against the album we're
 * looking for (mockups 9A/9B, 2026-11-02). Pure, so it's unit tested
 * (`DeezerAlbumArtTest`), like [io.github.eladimany.spindle.data.artists.DeezerArtistArt].
 *
 * Both the title and the artist must match loosely — case, punctuation, a leading
 * "The", and bracketed notes like "(Remastered 2012)" are ignored — so a search never
 * hands back some other band's album.
 */
object DeezerAlbumArt {
    private val COVER_KEYS_LARGEST_FIRST = listOf("cover_xl", "cover_big", "cover_medium", "cover")
    private const val EMPTY_PICTURE_ID = "d41d8cd98f00b204e9800998ecf8427e"

    fun candidates(json: String, wantedAlbum: String, wantedArtist: String): List<CoverCandidate> {
        val results = JSONObject(json).optJSONArray("data") ?: return emptyList()
        val wantedTitle = normalize(wantedAlbum)
        val wantedBy = normalize(wantedArtist)
        val scored = mutableListOf<Pair<Int, CoverCandidate>>()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val title = item.optString("title")
            val artist = item.optJSONObject("artist")?.optString("name").orEmpty()
            val url = COVER_KEYS_LARGEST_FIRST.firstNotNullOfOrNull { key -> item.optString(key).takeIf { it.isNotBlank() } }
                ?.takeUnless { isPlaceholder(it) } ?: continue
            val titleScore = score(normalize(title), wantedTitle)
            val artistScore = score(normalize(artist), wantedBy)
            if (titleScore == 0 || artistScore == 0) continue
            // The exact title (with its brackets) wins a tie over a "(Remastered)" edition.
            val exact = if (title.trim().equals(wantedAlbum.trim(), ignoreCase = true)) 1 else 0
            scored += (titleScore * 2 + artistScore * 2 + exact) to CoverCandidate(title, artist, url)
        }
        // Best first; Deezer's own order (popularity) breaks ties. Same cover twice = one.
        return scored.sortedByDescending { it.first }.map { it.second }.distinctBy { it.coverUrl }
    }

    /** 2 = same, 1 = one contains the other, 0 = different. */
    private fun score(found: String, wanted: String): Int = when {
        found.isEmpty() || wanted.isEmpty() -> 0
        found == wanted -> 2
        found.contains(wanted) || wanted.contains(found) -> 1
        else -> 0
    }

    internal fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("""[(\[][^)\]]*[)\]]"""), " ")
            .replace("&", " and ")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()
            .removePrefix("the ")
            .trim()

    /** Deezer's grey "no cover" picture: the MD5 of an empty string as its id, or no id. */
    fun isPlaceholder(url: String): Boolean =
        "/images/cover/$EMPTY_PICTURE_ID/" in url || "/images/cover//" in url
}
