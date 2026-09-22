package io.github.eladimany.spindle.data.bluos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BluOsXmlParserTest {

    // Shapes below are copied from docs/bluos-api.md, measured against real
    // hardware during Phase 0 — not invented for the test.

    @Test
    fun `parses a playing stream status`() {
        val xml = """
            <status etag="abc123">
              <canSeek>1</canSeek>
              <quality>320000</quality>
              <secs>19</secs>
              <service>http</service>
              <state>stream</state>
              <streamFormat>MP3 320 kb/s</streamFormat>
              <streamUrl>http://10.0.0.11:8080/07 - Angel.mp3</streamUrl>
              <totlen>268</totlen>
              <title1>Jimi Hendrix - Angel</title1>
            </status>
        """.trimIndent()

        val status = BluOsXmlParser.parseStatus(xml)

        assertEquals("abc123", status.etag)
        assertEquals("stream", status.state)
        assertEquals(19, status.secs)
        assertEquals(268, status.totalSeconds)
        assertTrue(status.canSeek)
        assertEquals("http://10.0.0.11:8080/07 - Angel.mp3", status.streamUrl)
        assertEquals("Jimi Hendrix - Angel", status.title1)
        assertEquals("320000", status.quality)
        assertEquals("MP3 320 kb/s", status.streamFormat)
        assertFalse(status.isHijacked)
    }

    @Test
    fun `parses the collapsed stop document, fields disappear rather than being empty`() {
        val xml = """
            <status etag="def456">
              <canSeek>0</canSeek>
              <quality>0</quality>
              <secs>0</secs>
              <state>stop</state>
            </status>
        """.trimIndent()

        val status = BluOsXmlParser.parseStatus(xml)

        assertEquals("stop", status.state)
        assertNull(status.totalSeconds)
        assertNull(status.streamUrl)
        assertNull(status.streamFormat)
        assertFalse(status.canSeek)
    }

    @Test
    fun `recognizes a Bluetooth-hijacked Node`() {
        val xml = """
            <status etag="ghi789">
              <serviceType>AudioInputs</serviceType>
              <title1>Bluetooth</title1>
              <quality>APTX</quality>
              <state>stream</state>
            </status>
        """.trimIndent()

        val status = BluOsXmlParser.parseStatus(xml)

        assertTrue(status.isHijacked)
    }

    @Test
    fun `missing etag attribute yields null rather than an empty string`() {
        val xml = "<status><state>stop</state></status>"

        assertNull(BluOsXmlParser.parseStatus(xml).etag)
    }

    @Test
    fun `missing state element defaults to stop`() {
        val xml = """<status etag="x"></status>"""

        assertEquals("stop", BluOsXmlParser.parseStatus(xml).state)
    }
}
