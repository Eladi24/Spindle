package io.github.eladimany.spindle.data.playlists

import io.github.eladimany.spindle.core.model.Track

/** One parsed M3U row — [location] is whatever the file put after the metadata line
 * (a content URI when the file came from Spindle, a file path/URL from anything else). */
data class M3uEntry(
    val location: String,
    val title: String?,
    val artist: String?,
)

/**
 * Reads/writes the extended M3U format. Spindle has no filesystem path for a [Track]
 * (see [Track.uri] — content URI only), so exported files carry the content URI as the
 * location line. That round-trips perfectly on re-import into Spindle; for interop with
 * other players the `#EXTINF` artist/title line is there as a human-readable fallback.
 */
object M3uFormat {
    fun export(tracks: List<Track>): String = buildString {
        appendLine("#EXTM3U")
        tracks.forEach { track ->
            val durationSec = track.durationMs / 1000
            appendLine("#EXTINF:$durationSec,${track.artistName} - ${track.title}")
            appendLine(track.uri)
        }
    }

    fun parse(content: String): List<M3uEntry> {
        val entries = mutableListOf<M3uEntry>()
        var pendingTitle: String? = null
        var pendingArtist: String? = null

        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF:") -> {
                    val display = line.substringAfter(',', missingDelimiterValue = "")
                    val dashIndex = display.indexOf(" - ")
                    if (dashIndex >= 0) {
                        pendingArtist = display.substring(0, dashIndex).trim()
                        pendingTitle = display.substring(dashIndex + 3).trim()
                    } else if (display.isNotBlank()) {
                        pendingTitle = display.trim()
                        pendingArtist = null
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    entries += M3uEntry(location = line, title = pendingTitle, artist = pendingArtist)
                    pendingTitle = null
                    pendingArtist = null
                }
            }
        }
        return entries
    }
}
