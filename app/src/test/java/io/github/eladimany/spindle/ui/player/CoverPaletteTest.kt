package io.github.eladimany.spindle.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverPaletteTest {

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun hue(color: Int) = CoverPalette.hsv((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)[0]
    private fun channels(color: Int) = Triple((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)

    @Test
    fun `vivid colour wins over a larger dull area`() {
        val pixels = IntArray(100) { if (it < 30) argb(220, 70, 20) else argb(60, 60, 64) }
        val palette = CoverPalette.from(pixels)!!
        assertEquals(argb(220, 70, 20), palette.primary)
    }

    @Test
    fun `second colour is a different hue when the cover has one`() {
        val pixels = IntArray(100) { if (it < 60) argb(200, 60, 20) else argb(30, 80, 200) }
        val palette = CoverPalette.from(pixels)!!
        assertTrue(hue(palette.primary) < 30f)
        assertTrue(hue(palette.secondary) in 200f..240f)
    }

    @Test
    fun `single-colour cover uses a darker shade as the second colour`() {
        val palette = CoverPalette.from(IntArray(50) { argb(200, 40, 40) })!!
        val (r, _, _) = channels(palette.secondary)
        assertEquals(120, r)
    }

    @Test
    fun `grey cover falls back to its average and a near-white accent`() {
        val palette = CoverPalette.from(IntArray(50) { if (it % 2 == 0) argb(40, 40, 40) else argb(120, 120, 120) })!!
        assertEquals(argb(80, 80, 80), palette.primary)
        val (r, g, b) = channels(palette.accent)
        assertTrue(r > 220 && g > 220 && b > 220)
    }

    @Test
    fun `accent is bright enough to glow on black`() {
        val accent = CoverPalette.accentOf(argb(90, 20, 10))
        val (r, g, b) = channels(accent)
        assertTrue(maxOf(r, g, b) >= 230)
    }

    @Test
    fun `transparent or empty input gives nothing`() {
        assertNull(CoverPalette.from(IntArray(0)))
        assertNull(CoverPalette.from(IntArray(10) { 0x00FF0000 }))
    }

    @Test
    fun `hsv round trip`() {
        val hsv = CoverPalette.hsv(30, 160, 90)
        val back = channels(CoverPalette.fromHsv(hsv[0], hsv[1], hsv[2]))
        assertTrue(kotlin.math.abs(back.first - 30) <= 1 && kotlin.math.abs(back.second - 160) <= 1 && kotlin.math.abs(back.third - 90) <= 1)
    }
}
