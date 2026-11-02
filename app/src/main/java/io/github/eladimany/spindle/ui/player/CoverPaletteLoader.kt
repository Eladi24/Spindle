package io.github.eladimany.spindle.ui.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import io.github.eladimany.spindle.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decodes a cached cover small (24 px) and hands its pixels to [CoverPalette.from]. One per album, cached. */
object CoverPaletteLoader {
    private const val SAMPLE_PX = 24
    private val cache = LruCache<String, CoverPalette>(64)

    suspend fun load(artworkUri: String): CoverPalette? {
        cache.get(artworkUri)?.let { return it }
        val palette = withContext(Dispatchers.IO) {
            runCatching {
                // Covers are cached as plain files by ArtworkExtractor.
                val path = Uri.parse(artworkUri).path ?: return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= SAMPLE_PX * 4) sample *= 2
                val full = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: return@runCatching null
                val small = Bitmap.createScaledBitmap(full, SAMPLE_PX, SAMPLE_PX, true)
                val pixels = IntArray(SAMPLE_PX * SAMPLE_PX)
                small.getPixels(pixels, 0, SAMPLE_PX, 0, 0, SAMPLE_PX, SAMPLE_PX)
                if (small !== full) small.recycle()
                full.recycle()
                CoverPalette.from(pixels)
            }.getOrNull()
        }
        palette?.let { cache.put(artworkUri, it) }
        return palette
    }
}

/** The palette of [track]'s cover, or null while loading / without art. */
@Composable
fun rememberCoverPalette(track: Track, fetchArtworkUri: suspend (Track) -> String?): CoverPalette? {
    val palette by produceState<CoverPalette?>(initialValue = null, track.albumId) {
        value = fetchArtworkUri(track)?.let { CoverPaletteLoader.load(it) }
    }
    return palette
}
