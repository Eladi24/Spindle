package io.github.eladimany.spindle.data.artists

import org.json.JSONObject

/**
 * Parses Deezer's public `/search/artist` response — pure JSON parsing, no
 * network/Android imports, so it's unit tested directly against a captured
 * response shape.
 */
object DeezerArtistArt {
    private val IMAGE_KEYS_LARGEST_FIRST = listOf("picture_xl", "picture_big", "picture_medium", "picture")

    /**
     * The first result's largest available photo URL, or null if there's no match —
     * or if Deezer only has its grey silhouette placeholder for that artist.
     */
    fun firstImageUrl(json: String): String? {
        val results = JSONObject(json).optJSONArray("data") ?: return null
        if (results.length() == 0) return null
        val first = results.optJSONObject(0) ?: return null
        return IMAGE_KEYS_LARGEST_FIRST.firstNotNullOfOrNull { key ->
            first.optString(key).takeIf { it.isNotBlank() }
        }?.takeUnless { isPlaceholder(it) }
    }

    /**
     * Deezer's "no photo" silhouette. Its picture id is the MD5 of an empty string
     * (`.../images/artist/d41d8cd98f00b204e9800998ecf8427e/...`, seen on the A73 for
     * Dio); an empty id (`.../artist//...`) is treated the same.
     */
    fun isPlaceholder(url: String): Boolean =
        "/images/artist/$EMPTY_PICTURE_ID/" in url || "/images/artist//" in url

    private const val EMPTY_PICTURE_ID = "d41d8cd98f00b204e9800998ecf8427e"
}
