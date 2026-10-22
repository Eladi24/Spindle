package io.github.eladimany.spindle.data.smartplaylists

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

sealed interface KeyCheck {
    data object Works : KeyCheck

    /** [message] is shown to the user as is. */
    data class Failed(val message: String) : KeyCheck
}

/**
 * Gemini in Google's cloud, with the user's own free API key ([GeminiKeyStore]) — the
 * fallback for phones where Android doesn't open Gemini Nano to apps (the Galaxy S25
 * family). [AiEngineRouter] only uses it when Nano is unavailable.
 *
 * Sends what [CriteriaPrompt] sends Nano: the request plus the library's genre and
 * decade names — never tracks, audio or file paths. One call per request, no retries:
 * on the free plan's limit Google answers 429 and the user is told to try later.
 */
@Singleton
class GeminiKeyEngine @Inject constructor(private val keyStore: GeminiKeyStore) : AiPlaylistEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val httpClient = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout)
    }

    /** Index into [GeminiApi.MODELS] of the first one Google still serves. */
    @Volatile private var modelIndex = 0

    val key: StateFlow<String?> = keyStore.key.stateIn(scope, SharingStarted.Eagerly, null)

    override val status: StateFlow<AiEngineStatus> = key
        .map { if (it != null) AiEngineStatus.Ready else AiEngineStatus.Unavailable("No Gemini key added") }
        .stateIn(scope, SharingStarted.Eagerly, AiEngineStatus.Unavailable())

    override suspend fun refreshStatus() = Unit

    override fun startDownload() = Unit

    override fun stopDownload() = Unit

    suspend fun saveKey(key: String) = keyStore.save(key)

    suspend fun removeKey() = keyStore.clear()

    /** Asks Google to list one model with [key] — free, and fails on a bad key. */
    suspend fun checkKey(key: String): KeyCheck = try {
        val response = httpClient.get(GeminiApi.CHECK_URL) {
            header(KEY_HEADER, key)
            timeout { requestTimeoutMillis = 15_000L }
        }
        val code = response.status.value
        if (code in 200..299) {
            KeyCheck.Works
        } else {
            val error = GeminiApi.error(code, response.bodyAsText())
            Timber.i("Gemini key check failed: %s", error)
            KeyCheck.Failed(
                when (error) {
                    GeminiApi.Error.BadKey -> "Google says this key doesn't work. Copy it again from AI Studio."
                    GeminiApi.Error.QuotaUsedUp -> "The key works, but its free limit is used up for now. Try again later."
                    else -> "Google couldn't check the key (error $code). Try again in a moment."
                },
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.i(e, "Gemini key check: no connection")
        KeyCheck.Failed("Couldn't reach Google. Check the internet connection and try again.")
    }

    override suspend fun interpret(
        request: String,
        facets: LibraryFacets,
        default: PlaylistCriteria,
    ): InterpretResult {
        val key = key.value ?: keyStore.key.first()
            ?: return InterpretResult.Failure("Add a free Gemini key in AI settings first.")
        val body = GeminiApi.requestBody(CriteriaPrompt.systemInstruction, CriteriaPrompt.userText(request, facets))
        return try {
            // Google answers 503 when a model is overloaded — common on the free plan at
            // busy times. Wait and retry, then try the other free models before giving up.
            var busyRetries = 0
            var busyModels = 0
            while (modelIndex < GeminiApi.MODELS.size) {
                val model = GeminiApi.MODELS[(modelIndex + busyModels) % GeminiApi.MODELS.size]
                val response = httpClient.post(GeminiApi.generateUrl(model)) {
                    header(KEY_HEADER, key)
                    setBody(TextContent(body, ContentType.Application.Json))
                    timeout { requestTimeoutMillis = 45_000L }
                }
                val code = response.status.value
                val text = response.bodyAsText()
                if (code in 200..299) {
                    val reply = GeminiApi.replyText(text)
                    Timber.d("Gemini (%s) reply: %s", model, reply)
                    return reply
                        ?.let { CriteriaPrompt.parse(it, facets, default) }
                        ?.let { InterpretResult.Success(it) }
                        ?: InterpretResult.Failure("The AI's answer didn't make sense. Try rewording it.")
                }
                val error = GeminiApi.error(code, text)
                Timber.w("Gemini (%s) failed: %s", model, error)
                when (error) {
                    GeminiApi.Error.ModelGone -> if (busyModels == 0) modelIndex++ else busyModels++
                    GeminiApi.Error.Busy -> {
                        if (busyRetries < BUSY_WAITS_MS.size) {
                            delay(BUSY_WAITS_MS[busyRetries++])
                        } else if (++busyModels >= GeminiApi.MODELS.size - modelIndex) {
                            return InterpretResult.Failure("Gemini is overloaded right now (on Google's side). Try again in a minute.")
                        } else {
                            busyRetries = 0
                        }
                    }
                    GeminiApi.Error.BadKey ->
                        return InterpretResult.Failure("Your Gemini key stopped working. Check it in AI settings.")
                    GeminiApi.Error.QuotaUsedUp ->
                        return InterpretResult.Failure("Gemini's free limit is used up for now. Try again later — nothing is charged.")
                    is GeminiApi.Error.Other ->
                        return InterpretResult.Failure("Gemini couldn't answer (error $code). Try again in a moment.")
                }
            }
            modelIndex = 0
            InterpretResult.Failure("Google no longer offers the free Gemini model Spindle uses. An app update is needed.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Gemini request failed")
            InterpretResult.Failure("Couldn't reach Gemini. Check the internet connection.")
        }
    }

    private companion object {
        /** In a header rather than the URL, so it stays out of any logged URLs. */
        const val KEY_HEADER = "x-goog-api-key"

        /** Waits before retrying an overloaded model; after these, the next model is tried. */
        val BUSY_WAITS_MS = longArrayOf(2_000L, 5_000L)
    }
}
