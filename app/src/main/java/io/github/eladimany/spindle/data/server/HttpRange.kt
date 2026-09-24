package io.github.eladimany.spindle.data.server

/**
 * Parses a single `Range: bytes=start-end` header, pure logic with no
 * Android dependency so it can be unit tested directly.
 *
 * Always clamps `end` to `fileSize - 1`, never returning a range past EOF —
 * the Node computes a byte range from bitrate and overshoots past EOF when
 * seeking near a track's end, and an unclamped 416 there kills playback
 * (hard constraint #4 in CLAUDE.md).
 */
object HttpRange {
    fun parse(header: String, fileSize: Long): LongRange? {
        if (fileSize <= 0 || !header.startsWith("bytes=")) return null
        val spec = header.removePrefix("bytes=").substringBefore(',').trim()
        val parts = spec.split('-', limit = 2)
        if (parts.size != 2) return null
        val startPart = parts[0].trim()
        val endPart = parts[1].trim()

        val start: Long
        val end: Long
        if (startPart.isEmpty()) {
            // Suffix range: last N bytes, e.g. "bytes=-500".
            val suffixLength = endPart.toLongOrNull() ?: return null
            start = (fileSize - suffixLength).coerceAtLeast(0)
            end = fileSize - 1
        } else {
            start = startPart.toLongOrNull() ?: return null
            end = if (endPart.isEmpty()) {
                fileSize - 1
            } else {
                (endPart.toLongOrNull() ?: (fileSize - 1)).coerceAtMost(fileSize - 1)
            }
        }
        if (start < 0 || start >= fileSize || start > end) return null
        return start..end
    }
}
