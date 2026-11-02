package io.github.eladimany.spindle.data.covers

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deezer's public album search (no key) and the cover download. Only called on a tap —
 * "Find cover" on one album, or "Find covers" for the library — since it sends album
 * and artist names to a third party. Every failure comes out as [IOException]
 * (offline, Ktor CIO throws UnresolvedAddressException, which isn't one).
 */
@Singleton
class AlbumCoverClient @Inject constructor() {
    private val httpClient = HttpClient(CIO) {
        expectSuccess = true
        install(HttpTimeout)
    }

    suspend fun search(album: String, artist: String): List<CoverCandidate> = wrap {
        // Field search first; a plain one when that finds nothing (Deezer's fields are strict).
        val strict = query("artist:\"$artist\" album:\"$album\"")
        DeezerAlbumArt.candidates(strict, album, artist).ifEmpty {
            DeezerAlbumArt.candidates(query("$artist $album"), album, artist)
        }
    }

    suspend fun download(url: String): ByteArray = wrap {
        httpClient.get(url) { timeout { requestTimeoutMillis = 20_000L } }.bodyAsBytes()
    }

    private suspend fun query(q: String): String =
        httpClient.get("https://api.deezer.com/search/album") {
            parameter("q", q)
            parameter("limit", 10)
            timeout { requestTimeoutMillis = 10_000L }
        }.bodyAsText()

    private inline fun <T> wrap(block: () -> T): T =
        try {
            block()
        } catch (e: IOException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e.message ?: "Couldn't reach Deezer", e)
        }
}
