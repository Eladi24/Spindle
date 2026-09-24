package io.github.eladimany.spindle.playback

import io.github.eladimany.spindle.data.history.PlayEventDao
import io.github.eladimany.spindle.data.history.trackKey
import io.github.eladimany.spindle.data.prefs.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a smart-shuffle [QueueManager.ShuffleOrderer] from the current listening
 * history and the user's rules. The one suspend step is the history summary (one
 * GROUP BY over ~a year of listens, on IO); the ordering itself is [SmartShuffle].
 */
@Singleton
class SmartShuffler @Inject constructor(
    private val dao: PlayEventDao,
    private val settings: SettingsRepository,
) {
    /** For the sheet's "learning from N listens" line. */
    suspend fun listenCount(): Int = withContext(Dispatchers.IO) { dao.count() }

    suspend fun orderer(): QueueManager.ShuffleOrderer {
        val rules = settings.smartShuffleRules.first()
        val stats = withContext(Dispatchers.IO) {
            dao.statsByTrackKey().associate {
                it.trackKey to TrackStats(it.plays, it.fullListens, it.skips, it.lastPlayedMs)
            }
        }
        val now = System.currentTimeMillis()
        return QueueManager.ShuffleOrderer { items, pinnedFirst ->
            SmartShuffle.order(items, stats, { trackKey(it.track) }, rules, now, pinnedFirst)
        }
    }
}
