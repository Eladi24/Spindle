package io.github.eladimany.spindle.data.smartplaylists

import kotlinx.coroutines.flow.StateFlow

sealed interface AiEngineStatus {
    data object Ready : AiEngineStatus

    /** Supported, but the model isn't on the phone yet — [AiPlaylistEngine.startDownload]. */
    data object Downloadable : AiEngineStatus

    /** Android is fetching the model; [progress] 0–1 when the size is known. */
    data class Downloading(val progress: Float?) : AiEngineStatus

    /** No usable AI on this phone — the UI falls back to the chip builder. */
    data object Unavailable : AiEngineStatus
}

sealed interface InterpretResult {
    data class Success(val interpretation: Interpretation) : InterpretResult

    /** [message] is shown to the user as is. */
    data class Failure(val message: String) : InterpretResult
}

/**
 * Turns a free-text request into [PlaylistCriteria] ([CriteriaPrompt] does the wording).
 * Gemini Nano on the phone first ([GeminiNanoEngine]); a bring-your-own-key cloud
 * engine may follow. Track picking never moves into the engine — it stays
 * [PlaylistMatcher], over the user's own library.
 */
interface AiPlaylistEngine {
    val status: StateFlow<AiEngineStatus>

    /** Re-reads availability (e.g. when the settings screen opens). */
    suspend fun refreshStatus()

    /** Starts the model download when [status] is [AiEngineStatus.Downloadable]; otherwise no-op. */
    fun startDownload()

    suspend fun interpret(request: String, facets: LibraryFacets, default: PlaylistCriteria): InterpretResult
}
