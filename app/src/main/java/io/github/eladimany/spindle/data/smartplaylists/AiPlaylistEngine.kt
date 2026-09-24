package io.github.eladimany.spindle.data.smartplaylists

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AiEngineStatus {
    data object Ready : AiEngineStatus

    /** Android is fetching the model; [progress] 0–1 when known. */
    data class Downloading(val progress: Float?) : AiEngineStatus

    /** No usable AI on this phone — the UI falls back to the chip builder. */
    data object Unavailable : AiEngineStatus
}

/**
 * Turns a spoken-style request into [PlaylistCriteria]. The first real implementation
 * will be Gemini Nano through ML Kit GenAI (pending the on-device check on the S25+),
 * then an optional bring-your-own-key cloud engine. Track picking never moves into the
 * engine — it stays [PlaylistMatcher], over the user's own library.
 */
interface AiPlaylistEngine {
    val status: StateFlow<AiEngineStatus>
}

/** Bound until a real engine exists: every phone gets the chip builder. */
@Singleton
class NoAiPlaylistEngine @Inject constructor() : AiPlaylistEngine {
    override val status: StateFlow<AiEngineStatus> = MutableStateFlow(AiEngineStatus.Unavailable)
}
