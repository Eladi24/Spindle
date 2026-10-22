package io.github.eladimany.spindle.playback

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.eladimany.spindle.core.model.QueueItem
import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.data.db.dao.TrackDao
import io.github.eladimany.spindle.data.library.toDomain
import io.github.eladimany.spindle.di.IoDispatcher
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber

/**
 * The play queue as saved to disk: track ids rather than tracks, so it stays small
 * (~30 KB for a 1,000-track shuffle) and picks up any tag fixes on the way back.
 */
data class SavedQueue(
    val itemIds: List<String>,
    val trackIds: List<Long>,
    val order: List<Int>,
    val position: Int,
    val repeatMode: QueueManager.RepeatMode,
    val shuffleMode: QueueManager.ShuffleMode,
    val finished: Boolean,
    /** Where the current track was, so it comes back paused at the same spot. */
    val positionMs: Long,
) {
    /**
     * Back to a queue, given the tracks that still exist. Tracks gone from the library
     * (deleted, folder excluded) drop out; if the current one is gone, the next one in
     * the order becomes current, from its start. Null when nothing is left.
     */
    fun resolve(tracks: Map<Long, Track>): Pair<QueueManager.Snapshot, Long>? {
        val newIndex = IntArray(trackIds.size) { -1 }
        val items = mutableListOf<QueueItem>()
        trackIds.forEachIndexed { i, id ->
            val track = tracks[id] ?: return@forEachIndexed
            newIndex[i] = items.size
            items += QueueItem(itemIds[i], track)
        }
        val keptSlots = order.indices.filter { slot -> order[slot] in newIndex.indices && newIndex[order[slot]] >= 0 }
        if (keptSlots.isEmpty()) return null
        val newOrder = keptSlots.map { newIndex[order[it]] }
        val currentKept = keptSlots.indexOf(position)
        val newPosition = if (currentKept >= 0) currentKept else keptSlots.count { it < position }.coerceAtMost(newOrder.size - 1)
        val snapshot = QueueManager.Snapshot(items, newOrder, newPosition, repeatMode, shuffleMode)
        return snapshot to (if (currentKept >= 0) positionMs else 0L)
    }

    fun toJson(): String = JSONObject()
        .put("version", 1)
        .put("itemIds", JSONArray(itemIds))
        .put("trackIds", JSONArray(trackIds))
        .put("order", JSONArray(order))
        .put("position", position)
        .put("repeat", repeatMode.name)
        .put("shuffle", shuffleMode.name)
        .put("finished", finished)
        .put("positionMs", positionMs)
        .toString()

    companion object {
        fun from(snapshot: QueueManager.Snapshot, finished: Boolean, positionMs: Long) = SavedQueue(
            itemIds = snapshot.items.map { it.id },
            trackIds = snapshot.items.map { it.track.id },
            order = snapshot.order,
            position = snapshot.position,
            repeatMode = snapshot.repeatMode,
            shuffleMode = snapshot.shuffleMode,
            finished = finished,
            positionMs = positionMs,
        )

        /** Null for anything unreadable — a bad file just means no saved queue. */
        fun fromJson(json: String): SavedQueue? = try {
            val o = JSONObject(json)
            val itemIds = o.getJSONArray("itemIds").let { a -> List(a.length()) { a.getString(it) } }
            val trackIds = o.getJSONArray("trackIds").let { a -> List(a.length()) { a.getLong(it) } }
            if (itemIds.size != trackIds.size) {
                null
            } else {
                SavedQueue(
                    itemIds = itemIds,
                    trackIds = trackIds,
                    order = o.getJSONArray("order").let { a -> List(a.length()) { a.getInt(it) } },
                    position = o.getInt("position"),
                    repeatMode = QueueManager.RepeatMode.valueOf(o.getString("repeat")),
                    shuffleMode = QueueManager.ShuffleMode.valueOf(o.getString("shuffle")),
                    finished = o.optBoolean("finished"),
                    positionMs = o.optLong("positionMs"),
                )
            }
        } catch (e: JSONException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

/**
 * Keeps the play queue across app restarts — Android kills a paused music app's
 * process within hours, and the queue used to live only in memory (user report,
 * 2026-10-22: 25 of 1,025 shuffled tracks in, the next day it was gone).
 * One small file in the app's own storage, written atomically.
 */
@Singleton
class QueueStore @Inject constructor(
    @ApplicationContext context: Context,
    private val trackDao: TrackDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val file = AtomicFile(File(context.filesDir, "queue.json"))

    suspend fun save(queue: SavedQueue?) = withContext(io) {
        if (queue == null || queue.order.isEmpty()) {
            file.delete()
            return@withContext
        }
        val out = try {
            file.startWrite()
        } catch (e: IOException) {
            Timber.w(e, "Couldn't save the queue")
            return@withContext
        }
        try {
            out.write(queue.toJson().toByteArray())
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            Timber.w(e, "Couldn't save the queue")
        }
    }

    /** The saved queue with its tracks looked up again, and where the current one was. */
    suspend fun load(): Pair<QueueManager.Snapshot, SavedQueue>? = withContext(io) {
        val saved = try {
            SavedQueue.fromJson(file.readFully().decodeToString())
        } catch (e: IOException) {
            null
        } ?: return@withContext null
        // Chunked: SQLite before Android 11 allows at most 999 bound parameters.
        val tracks = saved.trackIds.distinct().chunked(900)
            .flatMap { trackDao.getByIds(it) }
            .associate { it.id to it.toDomain() }
        val (snapshot, positionMs) = saved.resolve(tracks) ?: return@withContext null
        snapshot to saved.copy(positionMs = positionMs)
    }
}
