package io.github.eladimany.spindle.data.smartplaylists

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gemini Nano through ML Kit's GenAI Prompt API (beta) — the model lives in Android's
 * AICore service, not in the APK. Supported on the Galaxy S25 family among others;
 * everywhere else [status] settles on Unavailable and the UI uses the chip builder.
 *
 * AICore limits: input under ~4000 tokens, a per-app inference quota, and no support
 * on phones with an unlocked bootloader. Not yet run on a supported phone — see
 * CLAUDE.md "AI playlists — Gemini Nano".
 */
@Singleton
class GeminiNanoEngine @Inject constructor() : AiPlaylistEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _status = MutableStateFlow<AiEngineStatus>(AiEngineStatus.Unavailable)
    override val status: StateFlow<AiEngineStatus> = _status.asStateFlow()

    /** Created on first use: on a phone without AICore even creating the client may fail. */
    private var model: GenerativeModel? = null
    private var downloadJob: Job? = null

    init {
        scope.launch { refreshStatus() }
    }

    override suspend fun refreshStatus() {
        if (downloadJob?.isActive == true) return
        _status.value = try {
            when (client().checkStatus()) {
                FeatureStatus.AVAILABLE -> AiEngineStatus.Ready
                FeatureStatus.DOWNLOADABLE -> AiEngineStatus.Downloadable
                FeatureStatus.DOWNLOADING -> AiEngineStatus.Downloading(progress = null)
                else -> AiEngineStatus.Unavailable
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.i(e, "Gemini Nano: status check failed — treating as unavailable")
            AiEngineStatus.Unavailable
        }
        Timber.i("Gemini Nano status: %s", _status.value)
    }

    override fun startDownload() {
        if (_status.value != AiEngineStatus.Downloadable || downloadJob?.isActive == true) return
        _status.value = AiEngineStatus.Downloading(progress = null)
        downloadJob = scope.launch {
            var total = 0L
            try {
                client().download().collect { event ->
                    when (event) {
                        is DownloadStatus.DownloadStarted -> {
                            total = event.bytesToDownload
                            _status.value = AiEngineStatus.Downloading(progress = 0f)
                        }
                        is DownloadStatus.DownloadProgress -> _status.value = AiEngineStatus.Downloading(
                            progress = if (total > 0) (event.totalBytesDownloaded.toFloat() / total).coerceIn(0f, 1f) else null,
                        )
                        is DownloadStatus.DownloadCompleted -> _status.value = AiEngineStatus.Ready
                        is DownloadStatus.DownloadFailed -> {
                            Timber.w(event.e, "Gemini Nano download failed")
                            _status.value = AiEngineStatus.Downloadable
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Gemini Nano download failed")
                _status.value = AiEngineStatus.Downloadable
            }
        }
    }

    override suspend fun interpret(
        request: String,
        facets: LibraryFacets,
        default: PlaylistCriteria,
    ): InterpretResult {
        if (_status.value != AiEngineStatus.Ready) {
            return InterpretResult.Failure("On-device AI isn't ready on this phone.")
        }
        return try {
            val response = client().generateContent(
                generateContentRequest(
                    SystemInstruction(CriteriaPrompt.systemInstruction),
                    TextPart(CriteriaPrompt.userText(request, facets)),
                ) {
                    // Low and fixed-ish: this is classification, not creative writing.
                    temperature = 0.2f
                    topK = 10
                    maxOutputTokens = 256
                },
            )
            val text = response.candidates.firstOrNull()?.text.orEmpty()
            Timber.d("Gemini Nano reply: %s", text)
            CriteriaPrompt.parse(text, facets, default)
                ?.let { InterpretResult.Success(it) }
                ?: InterpretResult.Failure("The AI's answer didn't make sense. Try rewording it.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: GenAiException) {
            Timber.w(e, "Gemini Nano failed (code %d)", e.errorCode)
            InterpretResult.Failure(
                if (e.retryDelay != null) "The on-device AI is busy. Try again in a moment."
                else "The on-device AI couldn't answer. Try again with Spindle open.",
            )
        } catch (e: Exception) {
            Timber.w(e, "Gemini Nano failed")
            InterpretResult.Failure("The on-device AI couldn't answer.")
        }
    }

    private fun client(): GenerativeModel = model ?: Generation.getClient().also { model = it }
}
