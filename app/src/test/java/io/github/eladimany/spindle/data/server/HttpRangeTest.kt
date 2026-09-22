package io.github.eladimany.spindle.data.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpRangeTest {

    private val fileSize = 1000L

    @Test
    fun `bounded range parses exactly`() {
        assertEquals(100L..499L, HttpRange.parse("bytes=100-499", fileSize))
    }

    @Test
    fun `open-ended range runs to end of file`() {
        assertEquals(500L..999L, HttpRange.parse("bytes=500-", fileSize))
    }

    @Test
    fun `suffix range returns last N bytes`() {
        assertEquals(900L..999L, HttpRange.parse("bytes=-100", fileSize))
    }

    @Test
    fun `suffix range longer than the file clamps to byte 0`() {
        assertEquals(0L..999L, HttpRange.parse("bytes=-5000", fileSize))
    }

    @Test
    fun `end past EOF clamps to fileSize minus 1 rather than 416ing`() {
        // The Node computes a byte range from bitrate and overshoots past EOF
        // when seeking near a track's end — this must clamp, not fail
        // (hard constraint #4).
        assertEquals(999L..999L, HttpRange.parse("bytes=999-999999999", fileSize))
    }

    @Test
    fun `start at or past EOF is invalid`() {
        assertNull(HttpRange.parse("bytes=1000-1999", fileSize))
    }

    @Test
    fun `start after end is invalid`() {
        assertNull(HttpRange.parse("bytes=500-100", fileSize))
    }

    @Test
    fun `malformed suffix spec is invalid`() {
        assertNull(HttpRange.parse("bytes=-1-500", fileSize))
    }

    @Test
    fun `missing bytes prefix is invalid`() {
        assertNull(HttpRange.parse("100-499", fileSize))
    }

    @Test
    fun `malformed spec is invalid`() {
        assertNull(HttpRange.parse("bytes=abc-def", fileSize))
    }

    @Test
    fun `multi-range spec uses only the first range`() {
        assertEquals(0L..99L, HttpRange.parse("bytes=0-99,200-299", fileSize))
    }

    @Test
    fun `zero-length file never yields a range`() {
        assertNull(HttpRange.parse("bytes=0-0", 0L))
    }
}
