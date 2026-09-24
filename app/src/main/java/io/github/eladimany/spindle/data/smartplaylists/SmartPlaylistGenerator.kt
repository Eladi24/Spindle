package io.github.eladimany.spindle.data.smartplaylists

import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.data.db.dao.TrackDao
import io.github.eladimany.spindle.data.history.PlayEventDao
import io.github.eladimany.spindle.data.history.trackKey
import io.github.eladimany.spindle.data.library.toDomain
import io.github.eladimany.spindle.di.IoDispatcher
import io.github.eladimany.spindle.playback.TrackStats
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** A generated playlist before it's saved — lives in memory only; lost with the process. */
data class PlaylistDraft(
    val criteria: PlaylistCriteria,
    val name: String,
    val tracks: List<Track>,
    /** The sentence the AI interpreted; null for a draft from the chip builder. */
    val request: String? = null,
)

/** A free-text request on its way to a draft — the in-progress row on Playlists. */
sealed interface AiRequestState {
    val request: String

    data class Working(override val request: String) : AiRequestState

    data class Failed(override val request: String, val message: String) : AiRequestState

    /** The draft is in [SmartPlaylistGenerator.draft]; [name] labels the row. */
    data class Ready(override val request: String, val name: String) : AiRequestState
}

/**
 * Runs [PlaylistMatcher] against the library and holds the resulting draft, so the
 * builder sheet (which makes it) and the draft screen (which shows it) share one copy.
 *
 * Generation loads every track once (~3900 rows, a one-off on IO) — the matcher needs
 * genre, year, duration and the history key of each. Milliseconds, so no progress UI.
 */
@Singleton
class SmartPlaylistGenerator @Inject constructor(
    private val trackDao: TrackDao,
    private val playEventDao: PlayEventDao,
    private val engine: AiPlaylistEngine,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    /** AI requests outlive the screen that started them — the user may keep browsing. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var aiJob: Job? = null
    /** The sheet's length/history choices for the last request, so Retry keeps them. */
    private var lastDefault = PlaylistCriteria()

    private val _aiRequest = MutableStateFlow<AiRequestState?>(null)
    val aiRequest: StateFlow<AiRequestState?> = _aiRequest.asStateFlow()

    private val _draft = MutableStateFlow<PlaylistDraft?>(null)
    val draft: StateFlow<PlaylistDraft?> = _draft.asStateFlow()

    /** Every track's genre and year — the builder derives its chips and live match count from these. */
    suspend fun tags(): List<TrackTags> = withContext(ioDispatcher) { trackDao.allTags() }

    /**
     * Asks the AI engine to read [request], then builds the draft from its filters.
     * [default] carries the length and history switch the user chose on the sheet.
     * A new request replaces one still running.
     */
    fun makeFromRequest(request: String, default: PlaylistCriteria) {
        aiJob?.cancel()
        lastDefault = default
        _aiRequest.value = AiRequestState.Working(request)
        aiJob = scope.launch {
            val facets = LibraryFacets.from(tags())
            _aiRequest.value = when (val result = engine.interpret(request, facets, default)) {
                is InterpretResult.Failure -> AiRequestState.Failed(request, result.message)
                is InterpretResult.Success -> {
                    val draft = generate(result.interpretation.criteria, result.interpretation.name, request)
                    AiRequestState.Ready(request, draft.name)
                }
            }
        }
    }

    fun retryAiRequest() {
        _aiRequest.value?.let { makeFromRequest(it.request, lastDefault) }
    }

    fun dismissAiRequest() {
        aiJob?.cancel()
        _aiRequest.value = null
    }

    /** Makes a new draft from [criteria]. [name] keeps a name the user already typed. */
    suspend fun generate(criteria: PlaylistCriteria, name: String? = null, request: String? = null): PlaylistDraft {
        val tracks = withContext(ioDispatcher) {
            val library = trackDao.getAllByTitle().map { it.toDomain() }
            val stats = if (criteria.leanOnHistory) {
                playEventDao.statsByTrackKey().associate {
                    it.trackKey to TrackStats(it.plays, it.fullListens, it.skips, it.lastPlayedMs)
                }
            } else {
                emptyMap()
            }
            PlaylistMatcher.pick(library, criteria, stats, ::trackKey, System.currentTimeMillis())
        }
        return PlaylistDraft(criteria, name ?: criteria.suggestedName(), tracks, request).also { _draft.value = it }
    }

    fun update(draft: PlaylistDraft) {
        _draft.value = draft
    }

    fun clear() {
        _draft.value = null
        if (_aiRequest.value is AiRequestState.Ready) _aiRequest.value = null
    }
}
