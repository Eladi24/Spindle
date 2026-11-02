package io.github.eladimany.spindle.ui.player

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The two colours the ambient player glows with, picked from the album cover
 * (mockup 5C, 2026-11-02). ARGB ints; [accent] is [primary] lifted so a progress line
 * or glow in it reads on the near-black backdrop. Pure, so it's unit tested — the
 * Android half (decoding the cover) is in [CoverPaletteLoader].
 *
 * Not androidx.palette: that would be a new dependency for two colours.
 */
data class CoverPalette(val primary: Int, val secondary: Int, val accent: Int) {
    companion object {
        private const val HUE_BINS = 12
        private const val MIN_ALPHA = 128

        /**
         * Hue histogram weighted by saturation × brightness, so the vivid parts of a cover
         * win over large dull areas. The second colour must differ in hue by 2+ bins and
         * carry a quarter of the first's weight, else it's a darker shade of the first.
         * A grey cover gets its average colour; [accent] then stays neutral.
         */
        fun from(pixels: IntArray): CoverPalette? {
            if (pixels.isEmpty()) return null
            val weight = DoubleArray(HUE_BINS)
            val red = DoubleArray(HUE_BINS)
            val green = DoubleArray(HUE_BINS)
            val blue = DoubleArray(HUE_BINS)
            var sumR = 0.0
            var sumG = 0.0
            var sumB = 0.0
            var counted = 0
            for (p in pixels) {
                if ((p ushr 24) < MIN_ALPHA) continue
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                sumR += r; sumG += g; sumB += b; counted++
                val hsv = hsv(r, g, b)
                val w = hsv[1] * hsv[2]
                if (w < 0.08f) continue
                val bin = ((hsv[0] / 360f) * HUE_BINS).toInt().coerceIn(0, HUE_BINS - 1)
                weight[bin] += w
                red[bin] += r * w; green[bin] += g * w; blue[bin] += b * w
            }
            if (counted == 0) return null
            val average = rgb((sumR / counted).toInt(), (sumG / counted).toInt(), (sumB / counted).toInt())
            val best = weight.indices.maxBy { weight[it] }
            if (weight[best] / counted < 0.04) return CoverPalette(average, average, accentOf(average))
            fun binColor(i: Int) = rgb((red[i] / weight[i]).roundToInt(), (green[i] / weight[i]).roundToInt(), (blue[i] / weight[i]).roundToInt())
            val primary = binColor(best)
            val second = weight.indices
                .filter { i -> hueDistance(i, best) >= 2 && weight[i] >= weight[best] * 0.25 }
                .maxByOrNull { weight[it] }
            val secondary = second?.let(::binColor) ?: shade(primary, 0.6f)
            return CoverPalette(primary, secondary, accentOf(primary))
        }

        /** Bright enough to glow on black; a near-grey colour stays near-white rather than turning a hue. */
        fun accentOf(color: Int): Int {
            val hsv = hsv((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)
            val s = if (hsv[1] < 0.15f) hsv[1] * 0.5f else hsv[1].coerceIn(0.35f, 0.75f)
            return fromHsv(hsv[0], s, max(hsv[2], 0.92f))
        }

        private fun hueDistance(a: Int, b: Int): Int {
            val d = abs(a - b)
            return min(d, HUE_BINS - d)
        }

        private fun shade(color: Int, factor: Float): Int = rgb(
            (((color shr 16) and 0xFF) * factor).toInt(),
            (((color shr 8) and 0xFF) * factor).toInt(),
            ((color and 0xFF) * factor).toInt(),
        )

        private fun rgb(r: Int, g: Int, b: Int): Int =
            (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

        /** [hue 0–360, saturation 0–1, value 0–1]. */
        internal fun hsv(r: Int, g: Int, b: Int): FloatArray {
            val rf = r / 255f
            val gf = g / 255f
            val bf = b / 255f
            val maxC = max(rf, max(gf, bf))
            val minC = min(rf, min(gf, bf))
            val delta = maxC - minC
            val hue = when {
                delta == 0f -> 0f
                maxC == rf -> 60f * (((gf - bf) / delta).mod(6f))
                maxC == gf -> 60f * ((bf - rf) / delta + 2f)
                else -> 60f * ((rf - gf) / delta + 4f)
            }
            return floatArrayOf(hue, if (maxC == 0f) 0f else delta / maxC, maxC)
        }

        internal fun fromHsv(h: Float, s: Float, v: Float): Int {
            val c = v * s
            val x = c * (1 - abs((h / 60f).mod(2f) - 1))
            val m = v - c
            val (r, g, b) = when ((h / 60f).toInt().coerceIn(0, 5)) {
                0 -> Triple(c, x, 0f)
                1 -> Triple(x, c, 0f)
                2 -> Triple(0f, c, x)
                3 -> Triple(0f, x, c)
                4 -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }
            return rgb(((r + m) * 255).toInt(), ((g + m) * 255).toInt(), ((b + m) * 255).toInt())
        }
    }
}
