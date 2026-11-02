package io.github.eladimany.spindle.data.covers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeezerAlbumArtTest {

    private fun item(title: String, artist: String, cover: String) =
        """{"title":"$title","artist":{"name":"$artist"},"cover_xl":"$cover","cover_big":"$cover-big"}"""

    private fun response(vararg items: String) = """{"data":[${items.joinToString(",")}],"total":${items.size}}"""

    @Test
    fun `exact match ranks first`() {
        val json = response(
            item("After the War (Remastered)", "Gary Moore", "https://x/1"),
            item("After the War", "Gary Moore", "https://x/2"),
        )
        val found = DeezerAlbumArt.candidates(json, "After the War", "Gary Moore")
        assertEquals(listOf("https://x/2", "https://x/1"), found.map { it.coverUrl })
    }

    @Test
    fun `another artist's album of the same name is dropped`() {
        val json = response(item("Born Again", "The Notorious B.I.G.", "https://x/1"), item("Born Again", "Black Sabbath", "https://x/2"))
        val found = DeezerAlbumArt.candidates(json, "Born Again", "Black Sabbath")
        assertEquals(listOf("https://x/2"), found.map { it.coverUrl })
    }

    @Test
    fun `case, punctuation, the and ampersand are ignored`() {
        val json = response(item("Brothers In Arms", "Dire Straits", "https://x/1"), item("Green Onions", "Booker T. & The M.G.'s", "https://x/2"))
        assertEquals(1, DeezerAlbumArt.candidates(json, "brothers in arms", "The Dire Straits").size)
        assertEquals(1, DeezerAlbumArt.candidates(json, "Green Onions", "Booker T. and the M.G.s").size)
    }

    @Test
    fun `placeholder covers and empty results give nothing`() {
        val json = response(item("Holy Diver", "Dio", "https://e-cdns-images.dzcdn.net/images/cover/d41d8cd98f00b204e9800998ecf8427e/1000x1000.jpg"))
        assertTrue(DeezerAlbumArt.candidates(json, "Holy Diver", "Dio").isEmpty())
        assertTrue(DeezerAlbumArt.candidates("""{"data":[]}""", "Holy Diver", "Dio").isEmpty())
        assertTrue(DeezerAlbumArt.candidates("""{"error":{}}""", "Holy Diver", "Dio").isEmpty())
    }

    @Test
    fun `bracketed notes are ignored when matching`() {
        assertEquals("all right now the best of free", DeezerAlbumArt.normalize("All Right Now, The Best Of Free"))
        assertEquals("machine head", DeezerAlbumArt.normalize("Machine Head (25th Anniversary Edition)"))
    }
}
