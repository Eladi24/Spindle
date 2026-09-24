package io.github.eladimany.spindle.data.library

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.eladimany.spindle.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * Pulls embedded album art out of a track file and caches it to disk, one file per
 * album — extraction (MediaMetadataRetriever) is too slow to do for every track during
 * a scan, so it happens lazily on first display and is cached from then on.
 */
class ArtworkExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val cacheDir = File(context.cacheDir, "artwork")

    suspend fun extract(albumId: Long, sampleTrackUri: String): Uri? = withContext(ioDispatcher) {
        val cacheFile = File(cacheDir, "$albumId.jpg")
        if (cacheFile.exists()) return@withContext cacheFile.toUri()

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, sampleTrackUri.toUri())
            val art = retriever.embeddedPicture ?: return@withContext null
            cacheDir.mkdirs()
            cacheFile.writeBytes(art)
            cacheFile.toUri()
        } catch (e: Exception) {
            Timber.w(e, "Failed to extract artwork for album $albumId")
            null
        } finally {
            retriever.release()
        }
    }
}
