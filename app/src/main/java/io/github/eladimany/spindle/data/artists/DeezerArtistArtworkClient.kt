package io.github.eladimany.spindle.data.artists

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deezer's public artist search — no API key needed for this endpoint. Only
 * ever called on an explicit user tap (the "get artist photos" flow), never
 * automatically: it sends the library's artist names to a third-party
 * service, which the user has to opt into per-fetch, not just once for the app.
 */
@Singleton
class DeezerArtistArtworkClient @Inject constructor() {
    private val httpClient = HttpClient(CIO) {
        expectSuccess = true
        install(HttpTimeout)
    }

    suspend fun searchArtistImageUrl(artistName: String): String? {
        val response = httpClient.get("https://api.deezer.com/search/artist") {
            parameter("q", artistName)
            timeout { requestTimeoutMillis = 10_000L }
        }
        return DeezerArtistArt.firstImageUrl(response.bodyAsText())
    }
}
