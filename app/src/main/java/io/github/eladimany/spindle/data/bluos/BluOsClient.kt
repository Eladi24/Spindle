package io.github.eladimany.spindle.data.bluos

import io.github.eladimany.spindle.core.model.BluOsPlayer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import javax.inject.Inject
import javax.inject.Singleton

private const val COMMAND_TIMEOUT_MS = 10_000L

/**
 * Talks to a BluOS Node's REST API. Every call shape here was measured
 * against real hardware — see `docs/bluos-api.md` — not assumed from the
 * published spec. Stateless: every method takes the target [BluOsPlayer]
 * explicitly rather than binding to one player at construction, since
 * discovery can re-resolve a player's address at any time.
 *
 * `syncStatus()` (volume, player identity) was added once verified against a
 * real Node — see [BluOsSyncStatus]'s doc comment for the exact XML shape.
 */
@Singleton
class BluOsClient @Inject constructor() {
    private val httpClient = HttpClient(CIO) {
        expectSuccess = true
        install(HttpTimeout)
    }

    /**
     * Long-polls `/Status`. [timeoutSeconds] is BluOS's own `timeout=` query
     * param; the request's own socket timeout is always set comfortably
     * above it (bluos-api.md: "socket timeout must exceed the timeout=
     * value" — this is a real Node requirement, not caution for its own sake).
     */
    suspend fun status(player: BluOsPlayer, timeoutSeconds: Int? = null, etag: String? = null): BluOsStatus {
        val response = httpClient.get(baseUrl(player) + "/Status") {
            timeoutSeconds?.let { parameter("timeout", it) }
            etag?.let { parameter("etag", it) }
            timeout { requestTimeoutMillis = ((timeoutSeconds ?: 0) + 15) * 1000L }
        }
        return BluOsXmlParser.parseStatus(response.bodyAsText())
    }

    /** Long-polls `/SyncStatus` — same shape/rules as [status], but for volume and player identity. */
    suspend fun syncStatus(player: BluOsPlayer, timeoutSeconds: Int? = null, etag: String? = null): BluOsSyncStatus {
        val response = httpClient.get(baseUrl(player) + "/SyncStatus") {
            timeoutSeconds?.let { parameter("timeout", it) }
            etag?.let { parameter("etag", it) }
            timeout { requestTimeoutMillis = ((timeoutSeconds ?: 0) + 15) * 1000L }
        }
        return BluOsXmlParser.parseSyncStatus(response.bodyAsText())
    }

    /** Resumes from pause. Does **not** work from a `stop` state (bluos-api.md). */
    suspend fun resume(player: BluOsPlayer) = command(player, "Play")

    /** Plays a custom stream URL — the core mechanism this whole output depends on. */
    suspend fun playUrl(player: BluOsPlayer, url: String) {
        httpClient.get(baseUrl(player) + "/Play") {
            parameter("url", url)
            shortTimeout()
        }
    }

    /** Only takes effect when the current `/Status` reports `canSeek=1`. */
    suspend fun seek(player: BluOsPlayer, seconds: Int) {
        httpClient.get(baseUrl(player) + "/Play") {
            parameter("seek", seconds)
            shortTimeout()
        }
    }

    suspend fun pause(player: BluOsPlayer) = command(player, "Pause")

    suspend fun stop(player: BluOsPlayer) = command(player, "Stop")

    suspend fun setVolume(player: BluOsPlayer, percent: Int) {
        httpClient.get(baseUrl(player) + "/Volume") {
            parameter("level", percent.coerceIn(0, 100))
            shortTimeout()
        }
    }

    private suspend fun command(player: BluOsPlayer, path: String) {
        httpClient.get(baseUrl(player) + "/$path") { shortTimeout() }
    }

    private fun HttpRequestBuilder.shortTimeout() {
        timeout { requestTimeoutMillis = COMMAND_TIMEOUT_MS }
    }

    private fun baseUrl(player: BluOsPlayer) = "http://${player.host}:${player.port}"
}
