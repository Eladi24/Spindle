package io.github.eladimany.spindle.data.artists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeezerArtistArtTest {

    @Test
    fun `picks the largest available photo from the first result`() {
        val json = """
            {
              "data": [
                {
                  "id": 412,
                  "name": "Black Sabbath",
                  "picture": "https://api.deezer.com/artist/412/image",
                  "picture_small": "https://e-cdns-images.dzcdn.net/images/artist/x/56x56-000000-80-0-0.jpg",
                  "picture_medium": "https://e-cdns-images.dzcdn.net/images/artist/x/250x250-000000-80-0-0.jpg",
                  "picture_big": "https://e-cdns-images.dzcdn.net/images/artist/x/500x500-000000-80-0-0.jpg",
                  "picture_xl": "https://e-cdns-images.dzcdn.net/images/artist/x/1000x1000-000000-80-0-0.jpg",
                  "nb_album": 26,
                  "nb_fan": 2691439,
                  "radio": true,
                  "tracklist": "https://api.deezer.com/artist/412/top?limit=50",
                  "type": "artist"
                }
              ],
              "total": 1
            }
        """.trimIndent()

        assertEquals(
            "https://e-cdns-images.dzcdn.net/images/artist/x/1000x1000-000000-80-0-0.jpg",
            DeezerArtistArt.firstImageUrl(json),
        )
    }

    @Test
    fun `falls back to a smaller size when xl and big are missing`() {
        val json = """{"data":[{"name":"X","picture_medium":"https://example.com/m.jpg"}]}"""

        assertEquals("https://example.com/m.jpg", DeezerArtistArt.firstImageUrl(json))
    }

    @Test
    fun `no results returns null`() {
        assertNull(DeezerArtistArt.firstImageUrl("""{"data":[],"total":0}"""))
    }

    @Test
    fun `missing data array returns null rather than throwing`() {
        assertNull(DeezerArtistArt.firstImageUrl("""{"error":{"message":"bad request"}}"""))
    }

    @Test
    fun `blank picture urls are skipped in favor of a populated smaller size`() {
        val json = """{"data":[{"name":"X","picture_xl":"","picture_big":"","picture_medium":"https://example.com/m.jpg"}]}"""

        assertEquals("https://example.com/m.jpg", DeezerArtistArt.firstImageUrl(json))
    }

    @Test
    fun `treats Deezer's silhouette placeholder as no photo`() {
        val json = """
            {
              "data": [
                {
                  "id": 1234,
                  "name": "Dio",
                  "picture_big": "https://cdn-images.dzcdn.net/images/artist/d41d8cd98f00b204e9800998ecf8427e/500x500-000000-80-0-0.jpg",
                  "picture_xl": "https://cdn-images.dzcdn.net/images/artist/d41d8cd98f00b204e9800998ecf8427e/1000x1000-000000-80-0-0.jpg"
                }
              ]
            }
        """.trimIndent()

        assertNull(DeezerArtistArt.firstImageUrl(json))
    }
}
