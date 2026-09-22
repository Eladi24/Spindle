package io.github.eladimany.spindle.data.bluos

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses BluOS `/Status` XML. Pure `javax.xml` (no Android import), so this
 * can be unit tested directly against the exact response shapes recorded in
 * `docs/bluos-api.md` — never against a guessed schema.
 */
object BluOsXmlParser {
    fun parseStatus(xml: String): BluOsStatus {
        val root = parseRoot(xml)
        return BluOsStatus(
            etag = root.getAttribute("etag").ifBlank { null },
            state = root.text("state") ?: "stop",
            secs = root.text("secs")?.toIntOrNull() ?: 0,
            totalSeconds = root.text("totlen")?.toIntOrNull(),
            canSeek = root.text("canSeek") == "1",
            streamUrl = root.text("streamUrl"),
            title1 = root.text("title1"),
            quality = root.text("quality"),
            streamFormat = root.text("streamFormat"),
            serviceType = root.text("serviceType"),
        )
    }

    private fun parseRoot(xml: String): Element {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val document = builder.parse(InputSource(StringReader(xml)))
        return document.documentElement
    }

    private fun Element.text(tag: String): String? {
        val nodes = getElementsByTagName(tag)
        if (nodes.length == 0) return null
        return nodes.item(0).textContent?.trim()?.ifBlank { null }
    }
}
