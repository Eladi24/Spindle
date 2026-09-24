package io.github.eladimany.spindle.data.bluos

private const val MAX_LENGTH = 200

/**
 * Builds the single `icy-name` header line the Node's display shows
 * (bluos-api.md: only `icy-name` is read; `icy-description` etc. are
 * ignored, so everything has to fit on one line). Strips control characters
 * — track tags are user/library data, and a stray `\r`/`\n` in a title must
 * not be able to inject a second header into the response.
 */
object IcyName {
    fun forTrack(artist: String, title: String): String {
        val raw = "$artist - $title".replace(Regex("\\p{Cntrl}+"), " ").trim()
        return raw.take(MAX_LENGTH)
    }
}
