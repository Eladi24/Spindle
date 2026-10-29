package io.github.eladimany.spindle.data.smartplaylists

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The engine the rest of the app sees: Gemini Nano whenever Android offers it (v2 or
 * v3 — including "downloadable" and "downloading"), the user's own Gemini key only
 * when Nano is unavailable. A saved key is never used on a phone where Nano works.
 */
@Singleton
class AiEngineRouter @Inject constructor(
    private val nano: GeminiNanoEngine,
    private val cloud: GeminiKeyEngine,
) : AiPlaylistEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val status: StateFlow<AiEngineStatus> = combine(nano.status, cloud.status) { n, c -> pick(n, c) }
        .stateIn(scope, SharingStarted.Eagerly, pick(nano.status.value, cloud.status.value))

    override suspend fun refreshStatus() = nano.refreshStatus()

    override fun startDownload() = nano.startDownload()

    override fun stopDownload() = nano.stopDownload()

    override suspend fun interpret(request: String, facets: LibraryFacets, default: PlaylistCriteria): InterpretResult =
        active().interpret(request, facets, default)

    override val onDevice: Boolean get() = active().onDevice

    override val maxSongsPerRequest: Int get() = active().maxSongsPerRequest

    override suspend fun ask(system: String, user: String): AskResult = active().ask(system, user)

    private fun active(): AiPlaylistEngine = if (nano.status.value is AiEngineStatus.Unavailable) cloud else nano

    internal companion object {
        fun pick(nano: AiEngineStatus, cloud: AiEngineStatus): AiEngineStatus =
            if (nano is AiEngineStatus.Unavailable && cloud == AiEngineStatus.Ready) AiEngineStatus.Ready else nano
    }
}
