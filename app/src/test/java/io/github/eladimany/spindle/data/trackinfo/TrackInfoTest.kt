package io.github.eladimany.spindle.data.trackinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleSplitterTest {
    private val known = setOf("deep purple", "gary moore", "dio")

    @Test
    fun `artist on the left of the title`() {
        val s = TitleSplitter.suggest("Deep Purple - Highway Star", "x.mp3", known)!!
        assertEquals("Deep Purple", s.artist)
        assertEquals("Highway Star", s.title)
        assertEquals(FixSource.TITLE, s.source)
        assertTrue(s.sure)
    }

    @Test
    fun `a known artist on the right turns it around`() {
        val s = TitleSplitter.suggest("Still Got the Blues – Gary Moore", "x.mp3", known)!!
        assertEquals("Gary Moore", s.artist)
        assertEquals("Still Got the Blues", s.title)
        assertTrue(s.sure)
    }

    @Test
    fun `unknown on both sides is a guess the user checks`() {
        val s = TitleSplitter.suggest("The Lonely Hearts - Midnight Ride", "x.mp3", known)!!
        assertEquals("The Lonely Hearts", s.artist)
        assertFalse(s.sure)
    }

    @Test
    fun `falls back to the file name, dropping the track number`() {
        val s = TitleSplitter.suggest("Track 03", "03. Dio - Holy Diver.mp3", known)!!
        assertEquals("Dio", s.artist)
        assertEquals("Holy Diver", s.title)
        assertEquals(3, s.trackNumber)
        assertEquals(FixSource.FILE_NAME, s.source)
        assertEquals("03. Dio - Holy Diver.mp3", s.readFrom)
    }

    @Test
    fun `version notes and plain titles are not splits`() {
        assertNull(TitleSplitter.suggest("Highway Star - Remastered 2012", "a.mp3", known))
        assertNull(TitleSplitter.suggest("Smoke on the Water - Live", "b.mp3", known))
        assertNull(TitleSplitter.suggest("Track 07", "Track 07.mp3", known))
        assertNull(TitleSplitter.suggest("Re-Make/Re-Model", "c.mp3", known))
    }
}

class TrackInfoMergeTest {
    private val untagged = FileTags("Deep Purple - Highway Star", artist = null, album = null, year = null, genre = null)
    private val split = TrackInfoFixEntity(
        fileKey = "k", title = "Highway Star", artist = "Deep Purple", album = "Machine Head", year = 1972,
        genre = "Hard Rock", source = FixSource.MUSICBRAINZ.name, splitFrom = untagged.title, updatedAtMs = 0,
    )

    @Test
    fun `an automatic fix fills what the file lacks`() {
        val t = TrackInfoMerge.merge(untagged, split)
        assertEquals("Highway Star", t.title)
        assertEquals("Deep Purple", t.artist)
        assertEquals("Machine Head", t.album)
        assertEquals(1972, t.year)
        assertTrue(t.fixed)
    }

    @Test
    fun `file tags win over an automatic fix`() {
        val tagged = FileTags("Highway Star", "Deep Purple", "Made in Japan", 1973, "Rock")
        val t = TrackInfoMerge.merge(tagged, split.copy(splitFrom = null))
        assertEquals("Made in Japan", t.album)
        assertEquals(1973, t.year)
        assertEquals("Rock", t.genre)
        assertFalse(t.fixed)
    }

    @Test
    fun `a split stops applying once the file is retagged`() {
        val retitled = untagged.copy(title = "Highway Star (2012 mix)")
        val t = TrackInfoMerge.merge(retitled, split)
        assertEquals("Highway Star (2012 mix)", t.title)
        assertNull(t.artist)
    }

    @Test
    fun `a fill-in only adds year and genre`() {
        val tagged = FileTags("Holy Diver", "Dio", "Holy Diver", null, null)
        val fill = TrackInfoFixEntity(fileKey = "k", year = 1983, genre = "Heavy Metal", source = FixSource.MUSICBRAINZ.name, updatedAtMs = 0)
        val t = TrackInfoMerge.merge(tagged, fill)
        assertEquals(1983, t.year)
        assertEquals("Heavy Metal", t.genre)
        assertEquals("Holy Diver", t.title)
    }

    @Test
    fun `what the user typed wins`() {
        val tagged = FileTags("Highway Star", "Deep Purple", "Machine Head", 1972, "Rock")
        val manual = TrackInfoFixEntity(fileKey = "k", genre = "Hard Rock", manual = true, source = FixSource.MANUAL.name, updatedAtMs = 0)
        val t = TrackInfoMerge.merge(tagged, manual)
        assertEquals("Hard Rock", t.genre)
        assertEquals("Machine Head", t.album)
        assertTrue(t.fixed)
    }

    @Test
    fun `MediaStore unknown counts as missing`() {
        assertTrue(TrackInfoMerge.isMissing("<unknown>"))
        assertTrue(TrackInfoMerge.isMissing(" "))
        assertFalse(TrackInfoMerge.isMissing("Dio"))
        assertEquals("beatles", TrackInfoMerge.norm("The Beatles"))
        assertEquals("ac dc", TrackInfoMerge.norm("AC/DC"))
    }
}

class MusicBrainzApiTest {
    // Shaped like the real reply for "Highway Star" (2026-10-18): the top hit is a 2024
    // remix; the original 1972 recording and the studio album are further down.
    private val reply = """
        {"recordings":[
          {"id":"remix","score":100,"title":"Highway Star","disambiguation":"2024 remix","first-release-date":"2024-03-28",
           "artist-credit":[{"name":"Deep Purple","artist":{"id":"dp"}}],
           "releases":[{"title":"Machine Head","status":"Official","date":"2024","release-group":{"primary-type":"Album"}}]},
          {"id":"live","score":100,"title":"Highway Star","first-release-date":"1994-03",
           "artist-credit":[{"name":"Deep Purple","artist":{"id":"dp"}}],
           "releases":[{"title":"In Concert","status":"Official","date":"1994-03","release-group":{"primary-type":"Album","secondary-types":["Live"]}}]},
          {"id":"orig","score":95,"title":"Highway Star (remastered)","first-release-date":"1972-03-25",
           "artist-credit":[{"name":"Deep Purple","artist":{"id":"dp"}}],
           "releases":[{"title":"Machine Head","status":"Official","date":"1972-03-25","release-group":{"primary-type":"Album","secondary-types":[]}},
                       {"title":"Very Best of","status":"Official","date":"1971","release-group":{"primary-type":"Album","secondary-types":["Compilation"]}}]},
          {"id":"other","score":100,"title":"Highway Star","first-release-date":"1960",
           "artist-credit":[{"name":"Someone Else","artist":{"id":"x"}}],"releases":[]},
          {"id":"weak","score":40,"title":"Highway Star","first-release-date":"1950",
           "artist-credit":[{"name":"Deep Purple","artist":{"id":"dp"}}],"releases":[]}
        ]}
    """.trimIndent()

    @Test
    fun `year and album come from the original, not the top hit`() {
        val m = MusicBrainzApi.bestMatch(reply, "deep purple", "Highway Star")!!
        assertEquals("orig", m.recordingId)
        assertEquals("Highway Star", m.title)
        assertEquals("Deep Purple", m.artist)
        assertEquals("dp", m.artistId)
        assertEquals("Machine Head", m.album)
        assertEquals(1972, m.year)
    }

    @Test
    fun `no match for another artist or a different song`() {
        assertNull(MusicBrainzApi.bestMatch(reply, "Rainbow", "Highway Star"))
        assertNull(MusicBrainzApi.bestMatch(reply, "Deep Purple", "Smoke on the Water"))
        assertNull(MusicBrainzApi.bestMatch("not json", "Deep Purple", "Highway Star"))
    }

    @Test
    fun `genre is the artist's most voted`() {
        val artist = """{"genres":[{"name":"rock","count":14},{"name":"hard rock","count":28}],"tags":[{"name":"british","count":50}]}"""
        assertEquals("Hard Rock", MusicBrainzApi.artistGenre(artist))
        assertEquals("British", MusicBrainzApi.artistGenre("""{"genres":[],"tags":[{"name":"british","count":5}]}"""))
        assertNull(MusicBrainzApi.artistGenre("{}"))
    }

    @Test
    fun `the search URL quotes and escapes`() {
        val url = MusicBrainzApi.searchUrl("AC/DC", "Let There Be \"Rock\"")
        assertTrue(url.startsWith("https://musicbrainz.org/ws/2/recording?query="))
        assertTrue(url.contains("%5C%22Rock%5C%22"))
        assertTrue(url.endsWith("&fmt=json&limit=100"))
    }
}
