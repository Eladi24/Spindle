package io.github.eladimany.spindle.data.smartplaylists

import kotlinx.coroutines.flow.StateFlow

sealed interface AiEngineStatus {
    data object Ready : AiEngineStatus

    /** Supported, but the model isn't on the phone yet — [AiPlaylistEngine.startDownload]. */
    data object Downloadable : AiEngineStatus

    /**
     * Android is fetching the model. [progress] 0–1 when the size is known; [bytesDone]
     * when Android reports progress at all (it may join a download already under way
     * without saying how big it is).
     */
    data class Downloading(val progress: Float?, val bytesDone: Long? = null, val bytesTotal: Long? = null) : AiEngineStatus

    /**
     * No usable AI on this phone — the UI falls back to the chip builder. [reason] is the
     * phone's own answer, shown in AI settings: release builds have no log, and "not
     * available" alone couldn't tell an unsupported phone from an AICore that isn't ready.
     */
    data class Unavailable(val reason: String? = null) : AiEngineStatus
}

sealed interface InterpretResult {
    data class Success(val interpretation: Interpretation) : InterpretResult

    /** [message] is shown to the user as is. */
    data class Failure(val message: String) : InterpretResult
}

/** A plain answer from the model, for prompts other than [CriteriaPrompt]. */
sealed interface AskResult {
    data class Success(val text: String) : AskResult

    /** [message] is shown to the user as is. */
    data class Failure(val message: String) : AskResult
}

/**
 * Turns a free-text request into [PlaylistCriteria] ([CriteriaPrompt] does the wording),
 * then picks from a shortlist of the user's songs ([SongPickPrompt], through [ask]).
 * Gemini Nano on the phone first ([GeminiNanoEngine]), the user's own Gemini key when
 * Nano is unavailable ([GeminiKeyEngine]) — [AiEngineRouter] picks. The engine only
 * ever sees songs [PlaylistMatcher] listed from the user's own library.
 */
interface AiPlaylistEngine {
    val status: StateFlow<AiEngineStatus>

    /** Re-reads availability (e.g. when the settings screen opens). */
    suspend fun refreshStatus()

    /** Starts the model download when [status] is [AiEngineStatus.Downloadable]; otherwise no-op. */
    fun startDownload()

    /**
     * Stops Spindle's download request. The model belongs to Android (AICore, shared by
     * all apps): there is no API to delete it, and Android may keep what it already fetched.
     */
    fun stopDownload()

    suspend fun interpret(request: String, facets: LibraryFacets, default: PlaylistCriteria): InterpretResult

    /** True when the model runs on the phone — nothing about the request leaves it. */
    val onDevice: Boolean

    /** How many songs one [SongPickPrompt] request may list — Gemini Nano's input is small. */
    val maxSongsPerRequest: Int

    /** [system] + [user] in, the model's text out. Never throws. */
    suspend fun ask(system: String, user: String): AskResult
}
