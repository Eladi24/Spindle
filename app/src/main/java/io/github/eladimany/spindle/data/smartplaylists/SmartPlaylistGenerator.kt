package io.github.eladimany.spindle.data.smartplaylists

import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.data.db.dao.TrackDao
import io.github.eladimany.spindle.data.history.PlayEventDao
import io.github.eladimany.spindle.data.history.trackKey
import io.github.eladimany.spindle.data.library.toDomain
import io.github.eladimany.spindle.di.IoDispatcher
import io.github.eladimany.spindle.playback.TrackStats
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** A generated playlist before it's saved — lives in memory only; lost with the process. */
data class PlaylistDraft(
    val criteria: PlaylistCriteria,
    val name: String,
    val tracks: List<Track>,
)

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
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val _draft = MutableStateFlow<PlaylistDraft?>(null)
    val draft: StateFlow<PlaylistDraft?> = _draft.asStateFlow()

    /** Every track's genre and year — the builder derives its chips and live match count from these. */
    suspend fun tags(): List<TrackTags> = withContext(ioDispatcher) { trackDao.allTags() }

    /** Makes a new draft from [criteria]. [name] keeps a name the user already typed. */
    suspend fun generate(criteria: PlaylistCriteria, name: String? = null): PlaylistDraft {
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
        return PlaylistDraft(criteria, name ?: criteria.suggestedName(), tracks).also { _draft.value = it }
    }

    fun update(draft: PlaylistDraft) {
        _draft.value = draft
    }

    fun clear() {
        _draft.value = null
    }
}
