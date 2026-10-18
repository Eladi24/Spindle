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
 * AICore service, not in the APK. Offered to apps on the Galaxy S26 family, Pixel 9+ and
 * others — not on the Galaxy S25 family, which keeps Nano for its own features only;
 * there [status] settles on Unavailable and [AiEngineRouter] falls back to [GeminiKeyEngine].
 *
 * AICore limits: input under ~4000 tokens, a per-app inference quota, and no support
 * on phones with an unlocked bootloader. See CLAUDE.md "AI playlists — Gemini Nano".
 */
@Singleton
class GeminiNanoEngine @Inject constructor() : AiPlaylistEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _status = MutableStateFlow<AiEngineStatus>(AiEngineStatus.Unavailable())
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
            when (val code = client().checkStatus()) {
                FeatureStatus.AVAILABLE -> AiEngineStatus.Ready
                FeatureStatus.DOWNLOADABLE -> AiEngineStatus.Downloadable
                FeatureStatus.DOWNLOADING -> AiEngineStatus.Downloading(progress = null)
                else -> AiEngineStatus.Unavailable("Android answered: not available on this phone (status $code)")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.i(e, "Gemini Nano: status check failed — treating as unavailable")
            val code = (e as? GenAiException)?.let { " (code ${it.errorCode})" }.orEmpty()
            AiEngineStatus.Unavailable("Couldn't check: ${e.javaClass.simpleName}$code — ${e.message}")
        }
        Timber.i("Gemini Nano status: %s", _status.value)
        // A download already under way (started before an app restart, or by Android
        // itself): listen to it, or the UI would sit on "Downloading…" with no progress.
        if (_status.value is AiEngineStatus.Downloading) followDownload()
    }

    override fun startDownload() {
        if (_status.value != AiEngineStatus.Downloadable) return
        _status.value = AiEngineStatus.Downloading(progress = null)
        followDownload()
    }

    override fun stopDownload() {
        val job = downloadJob ?: return
        downloadJob = null
        job.cancel()
        // Whatever Android says now: Downloadable if it dropped the download, Downloading
        // if AICore carries on by itself (refreshStatus then follows it again).
        scope.launch {
            job.join()
            refreshStatus()
        }
    }

    /** Collects [GenerativeModel.download] — for a new download or one already running. */
    private fun followDownload() {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            var total = 0L
            try {
                client().download().collect { event ->
                    when (event) {
                        is DownloadStatus.DownloadStarted -> {
                            total = event.bytesToDownload
                            _status.value = AiEngineStatus.Downloading(progress = 0f, bytesDone = 0L, bytesTotal = total.takeIf { it > 0 })
                        }
                        is DownloadStatus.DownloadProgress -> {
                            val done = event.totalBytesDownloaded
                            _status.value = AiEngineStatus.Downloading(
                                progress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null,
                                bytesDone = done,
                                bytesTotal = total.takeIf { it > 0 },
                            )
                        }
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
