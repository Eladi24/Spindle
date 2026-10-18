package io.github.eladimany.spindle.data.trackinfo

import io.github.eladimany.spindle.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** A MusicBrainz match plus whether the artist turned out to be the other side of the split. */
data class LookupResult(val match: MusicBrainzMatch, val genre: String?, val swapped: Boolean)

/**
 * MusicBrainz over HTTP. Only ever runs after the user taps "Look up" — it sends artist
 * and title text, nothing else. No key; MusicBrainz asks for an identifying User-Agent
 * and at most one request per second, which [paced] enforces across all callers.
 */
@Singleton
class MusicBrainzClient @Inject constructor() {
    private val httpClient = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout)
    }
    private val pace = Mutex()
    private var lastRequestAt = 0L
    private val genreByArtistId = HashMap<String, String?>()

    /**
     * Looks up [title] by [artist]; when [maybeSwapped], also the other way round if the
     * first try finds nothing. Null: MusicBrainz doesn't know it. Throws [IOException]
     * when MusicBrainz can't be reached.
     */
    suspend fun lookup(artist: String, title: String, maybeSwapped: Boolean): LookupResult? {
        search(artist, title)?.let { return LookupResult(it, genreFor(it), swapped = false) }
        if (!maybeSwapped) return null
        return search(title, artist)?.let { LookupResult(it, genreFor(it), swapped = true) }
    }

    private suspend fun search(artist: String, title: String): MusicBrainzMatch? =
        MusicBrainzApi.bestMatch(get(MusicBrainzApi.searchUrl(artist, title)), artist, title)

    private suspend fun genreFor(match: MusicBrainzMatch): String? {
        val id = match.artistId ?: return null
        if (id in genreByArtistId) return genreByArtistId[id]
        return MusicBrainzApi.artistGenre(get(MusicBrainzApi.artistUrl(id))).also { genreByArtistId[id] = it }
    }

    /** One paced GET; on 503 (MusicBrainz busy / over the rate) waits and tries once more. */
    private suspend fun get(url: String): String {
        repeat(2) { attempt ->
            val response = try {
                paced {
                    httpClient.get(url) {
                        header("User-Agent", USER_AGENT)
                        header("Accept", "application/json")
                        timeout { requestTimeoutMillis = 20_000L }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Offline, Ktor CIO throws UnresolvedAddressException — not an IOException.
                // Callers handle one type: IOException means "couldn't reach MusicBrainz".
                throw IOException("Couldn't reach MusicBrainz", e)
            }
            when (response.status.value) {
                in 200..299 -> return response.bodyAsText()
                404 -> return "{}"
                503 -> if (attempt == 0) delay(BUSY_WAIT_MS)
                else -> throw IOException("MusicBrainz answered ${response.status.value}")
            }
        }
        throw IOException("MusicBrainz is busy")
    }

    private suspend fun <T> paced(block: suspend () -> T): T = pace.withLock {
        val wait = lastRequestAt + MIN_GAP_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        try {
            block()
        } finally {
            lastRequestAt = System.currentTimeMillis()
        }
    }

    private companion object {
        /** MusicBrainz's limit is 1/s per client; a little slack avoids 503s. */
        const val MIN_GAP_MS = 1_100L
        const val BUSY_WAIT_MS = 3_000L
        val USER_AGENT = "Spindle/${BuildConfig.VERSION_NAME} ( https://github.com/Eladi24/Spindle )"
    }
}
