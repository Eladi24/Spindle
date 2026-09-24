package io.github.eladimany.spindle.data.bluos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class IcyNameTest {

    @Test
    fun `combines artist and title on one line`() {
        assertEquals("Jimi Hendrix - Angel", IcyName.forTrack("Jimi Hendrix", "Angel"))
    }

    @Test
    fun `strips embedded newlines so a tag can't inject a second header`() {
        val result = IcyName.forTrack("Evil\r\nX-Injected: yes", "Title")
        assertFalse(result.contains("\r"))
        assertFalse(result.contains("\n"))
    }

    @Test
    fun `truncates very long combined titles`() {
        val result = IcyName.forTrack("A".repeat(500), "Title")
        assertEquals(200, result.length)
    }
}
