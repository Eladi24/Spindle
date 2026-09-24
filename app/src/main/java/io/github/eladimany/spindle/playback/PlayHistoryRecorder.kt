package io.github.eladimany.spindle.playback

import io.github.eladimany.spindle.core.model.PlaybackState
import io.github.eladimany.spindle.core.model.QueueItem
import io.github.eladimany.spindle.data.history.ListenSession
import io.github.eladimany.spindle.data.history.PlayEndReason
import io.github.eladimany.spindle.data.history.PlayEventDao
import io.github.eladimany.spindle.data.history.PlayEventEntity
import io.github.eladimany.spindle.data.history.trackKey
import io.github.eladimany.spindle.data.prefs.SettingsRepository
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Records one [PlayEventEntity] per listen for smart shuffle / smart playlists.
 * Driven by [PlaybackController] on the main thread; only the insert goes to IO,
 * so playback never waits on it. A listen still in progress when the process dies
 * is lost — acceptable for statistics.
 */
@Singleton
class PlayHistoryRecorder @Inject constructor(
    private val dao: PlayEventDao,
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: ListenSession? = null

    init {
        // Bounded storage: raw events for a year (~0.6 MB at 30 plays/day).
        scope.launch { dao.deleteOlderThan(System.currentTimeMillis() - RETENTION_MS) }
    }

    /** A queue item is about to play. Closes any listen still open as [PlayEndReason.REPLACED]. */
    fun onItemStarted(item: QueueItem) {
        endCurrent(PlayEndReason.REPLACED)
        session = ListenSession(item, System.currentTimeMillis())
    }

    fun onState(state: PlaybackState) {
        val s = session ?: return
        val now = System.currentTimeMillis()
        when (state) {
            is PlaybackState.Playing -> if (state.item.id == s.item.id) s.onPlaying(now) else s.onNotPlaying(now)
            is PlaybackState.Error -> endCurrent(PlayEndReason.ERROR)
            else -> s.onNotPlaying(now)
        }
    }

    fun endCurrent(reason: PlayEndReason) {
        val s = session ?: return
        session = null
        val listenedMs = s.listenedMs(System.currentTimeMillis())
        // Nothing was heard (e.g. tapped through while still buffering) — not a listen.
        if (listenedMs <= 0 && reason != PlayEndReason.COMPLETED) return
        val track = s.item.track
        val event = PlayEventEntity(
            trackId = track.id,
            trackKey = trackKey(track),
            startedAtMs = s.startedAtMs,
            listenedMs = listenedMs,
            trackDurationMs = track.durationMs,
            endReason = reason,
        )
        scope.launch {
            dao.insert(event)
            // A boost lasts one play: used up once the track has really been listened to —
            // not by skipping past it (seen on the A73: a 1 s skip-through spent the boost).
            if (listenedMs >= minOf(BOOST_SPENT_AFTER_MS, track.durationMs / 2)) {
                settings.setBoosted(event.trackKey, boosted = false)
            }
        }
    }

    private companion object {
        val RETENTION_MS = TimeUnit.DAYS.toMillis(365)
        const val BOOST_SPENT_AFTER_MS = 30_000L
    }
}
