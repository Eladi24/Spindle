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

/**
 * Turns a free-text request into [PlaylistCriteria] ([CriteriaPrompt] does the wording).
 * Gemini Nano on the phone first ([GeminiNanoEngine]), the user's own Gemini key when
 * Nano is unavailable ([GeminiKeyEngine]) — [AiEngineRouter] picks. Track picking never
 * moves into the engine — it stays [PlaylistMatcher], over the user's own library.
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
}
