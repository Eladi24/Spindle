package io.github.eladimany.spindle.data.history

import io.github.eladimany.spindle.core.model.Track

/**
 * Stable 64-bit identity for a track from its tags (FNV-1a over normalized
 * artist/album/title), so history still matches after MediaStore reassigns ids.
 */
fun trackKey(track: Track): Long = trackKey(track.artistName, track.albumName, track.title)

fun trackKey(artist: String, album: String, title: String): Long {
    val normalized = listOf(artist, album, title).joinToString("\u0000") { it.trim().lowercase() }
    var hash = -0x340d631b7bdddcdbL // FNV-1a 64-bit offset basis
    for (ch in normalized) {
        hash = hash xor ch.code.toLong()
        hash *= 0x100000001b3L
    }
    return hash
}
