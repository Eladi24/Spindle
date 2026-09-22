package io.github.eladimany.spindle.data.artists

import org.json.JSONObject

/**
 * Parses Deezer's public `/search/artist` response — pure JSON parsing, no
 * network/Android imports, so it's unit tested directly against a captured
 * response shape.
 */
object DeezerArtistArt {
    private val IMAGE_KEYS_LARGEST_FIRST = listOf("picture_xl", "picture_big", "picture_medium", "picture")

    /** The first result's largest available photo URL, or null if there's no match. */
    fun firstImageUrl(json: String): String? {
        val results = JSONObject(json).optJSONArray("data") ?: return null
        if (results.length() == 0) return null
        val first = results.optJSONObject(0) ?: return null
        return IMAGE_KEYS_LARGEST_FIRST.firstNotNullOfOrNull { key ->
            first.optString(key).takeIf { it.isNotBlank() }
        }
    }
}
