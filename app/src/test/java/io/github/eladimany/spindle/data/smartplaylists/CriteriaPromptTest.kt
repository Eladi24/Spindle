package io.github.eladimany.spindle.data.smartplaylists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CriteriaPromptTest {

    private val facets = LibraryFacets(
        decades = listOf(Facet(1970, 12), Facet(1990, 40), Facet(2000, 8)),
        genres = listOf(Facet("Trip-hop", 20), Facet("Indie Rock", 15), Facet("Jazz", 5)),
    )
    private val default = PlaylistCriteria(length = PlaylistLength.TWENTY_TRACKS, leanOnHistory = false)

    @Test
    fun `the prompt lists only what the library has`() {
        val text = CriteriaPrompt.userText("  mellow 90s  ", facets)
        assertEquals(
            "GENRES: Trip-hop, Indie Rock, Jazz\nDECADES: 1970, 1990, 2000\nREQUEST: mellow 90s",
            text,
        )
        assertTrue(CriteriaPrompt.userText("x", LibraryFacets(emptyList(), emptyList())).contains("GENRES: (none)"))
    }

    @Test
    fun `a clean reply becomes criteria`() {
        val result = CriteriaPrompt.parse(
            """{"genres":["Trip-hop","indie rock"],"decades":[1990],"length":"2_hours","name":"Rainy-day 90s"}""",
            facets,
            default,
        )!!
        assertEquals(setOf("Trip-hop", "Indie Rock"), result.criteria.genres)
        assertEquals(setOf(1990), result.criteria.decades)
        assertEquals(PlaylistLength.TWO_HOURS, result.criteria.length)
        assertEquals(false, result.criteria.leanOnHistory)
        assertEquals("Rainy-day 90s", result.name)
    }

    @Test
    fun `invented genres and decades are dropped`() {
        val result = CriteriaPrompt.parse(
            """{"genres":["Shoegaze","Jazz"],"decades":[1980,"1990"]}""",
            facets,
            default,
        )!!
        assertEquals(setOf("Jazz"), result.criteria.genres)
        assertEquals(setOf(1990), result.criteria.decades)
    }

    @Test
    fun `code fences, chatter and loose decade spellings are tolerated`() {
        val reply = "Sure! Here you go:\n```json\n{\"genres\": [], \"decades\": [\"90s\", \"'70s\", \"2000s\"], \"length\": \"forever\"}\n```"
        val result = CriteriaPrompt.parse(reply, facets, default)!!
        assertEquals(setOf(1990, 1970, 2000), result.criteria.decades)
        assertTrue(result.criteria.genres.isEmpty())
        assertEquals(PlaylistLength.TWENTY_TRACKS, result.criteria.length) // unknown → user's choice
        assertNull(result.name)
    }

    @Test
    fun `no JSON means no interpretation`() {
        assertNull(CriteriaPrompt.parse("I can't help with that.", facets, default))
        assertNull(CriteriaPrompt.parse("{not json}", facets, default))
    }

    @Test
    fun `long names are cut`() {
        val result = CriteriaPrompt.parse("""{"name":"${"x".repeat(80)}"}""", facets, default)!!
        assertEquals(40, result.name!!.length)
    }
}
