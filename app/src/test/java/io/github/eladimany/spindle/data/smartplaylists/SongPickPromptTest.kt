package io.github.eladimany.spindle.data.smartplaylists

import io.github.eladimany.spindle.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongPickPromptTest {

    private fun track(id: Long, artist: String = "Artist $id", title: String = "Song $id", year: Int? = null) = Track(
        id = id,
        uri = "content://media/external/audio/media/$id",
        title = title,
        artistId = id,
        artistName = artist,
        albumId = id,
        albumName = "Album $id",
        trackNumber = null,
        discNumber = null,
        durationMs = 240_000L,
        year = year,
        genre = null,
        dateAddedMs = 0L,
    )

    private val songs = (0L until 5L).map { track(it) }

    @Test
    fun `songs are numbered by position, one line each, year only when known`() {
        val text = SongPickPrompt.userText(
            " energetic run ",
            listOf(track(1, "Deep Purple", "Highway Star", 1972), track(2, "Massive  Attack", "Teardrop\nedit")),
        )
        assertEquals(
            "REQUEST: energetic run\nSONGS (number | artist – title | year):\n" +
                "0 | Deep Purple – Highway Star | 1972\n" +
                "1 | Massive Attack – Teardrop edit",
            text,
        )
    }

    @Test
    fun `the instruction asks for about the count`() {
        assertTrue(SongPickPrompt.systemInstruction(18).contains("about 18 of them"))
    }

    @Test
    fun `picks keep the AI's order`() {
        assertEquals(listOf(3L, 0L, 4L), SongPickPrompt.parse("""{"ids": [3, 0, 4]}""", songs)!!.map { it.id })
    }

    @Test
    fun `numbers as strings, repeats and out-of-range numbers are handled`() {
        val picked = SongPickPrompt.parse("""{"ids": ["2", 2, 9, -1, "x", 1]}""", songs)!!
        assertEquals(listOf(2L, 1L), picked.map { it.id })
    }

    @Test
    fun `a code fence and chatter around the object are tolerated`() {
        val reply = "Here you go:\n```json\n{\"ids\": [1]}\n```"
        assertEquals(listOf(1L), SongPickPrompt.parse(reply, songs)!!.map { it.id })
    }

    @Test
    fun `no usable JSON is null, an empty pick is empty`() {
        assertNull(SongPickPrompt.parse("I can't help with that", songs))
        assertNull(SongPickPrompt.parse("""{"songs": [1]}""", songs))
        assertEquals(emptyList<Track>(), SongPickPrompt.parse("""{"ids": []}""", songs))
    }
}
