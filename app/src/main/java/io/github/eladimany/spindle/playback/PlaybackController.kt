package io.github.eladimany.spindle.playback

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.eladimany.spindle.core.model.PlaybackState
import io.github.eladimany.spindle.core.model.QueueItem
import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.core.model.previousRestartsTrack
import io.github.eladimany.spindle.data.history.PlayEndReason
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class QueueState(
    val items: List<QueueItem> = emptyList(),
    val currentIndex: Int = -1,
    val repeatMode: QueueManager.RepeatMode = QueueManager.RepeatMode.OFF,
    val shuffleMode: QueueManager.ShuffleMode = QueueManager.ShuffleMode.OFF,
    /** The last track ended (or was skipped past) with repeat off. The queue is kept;
     * play starts it again from the top — see [PlaybackController.replay]. */
    val finished: Boolean = false,
) {
    val isShuffled: Boolean get() = shuffleMode != QueueManager.ShuffleMode.OFF
}

/**
 * Coordinates a [QueueManager] and one active [AudioOutput]. Deliberately doesn't know
 * which [AudioOutput] implementation it holds — see CLAUDE.md's architecture section.
 */
@Singleton
class PlaybackController @Inject constructor(
    private val output: AudioOutput,
    private val history: PlayHistoryRecorder,
    private val smartShuffler: SmartShuffler,
    @ApplicationContext private val context: Context,
) {
    private val queueManager = QueueManager()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var serviceStarted = false
    private var nextQueueItemSeq = 0L
    private var finished = false

    val playbackState: StateFlow<PlaybackState> = output.state

    // Single source of truth for "is this track the one currently loaded" — every
    // list screen (Tracks, Album/Folder/Playlist detail) needs this to highlight the
    // current row; deriving it once here instead of per-ViewModel avoids the class
    // of bug where a screen just hardcodes isCurrent = false and nobody notices.
    val currentTrackId: StateFlow<Long?> = playbackState
        .map { state ->
            when (state) {
                is PlaybackState.Playing -> state.item.track.id
                is PlaybackState.Paused -> state.item.track.id
                is PlaybackState.Buffering -> state.item.track.id
                else -> null
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Drives the animated-vs-static equalizer glyph on a list row's current track. */
    val isPlaying: StateFlow<Boolean> = playbackState
        .map { it is PlaybackState.Playing }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val _queueState = MutableStateFlow(QueueState())
    val queueState: StateFlow<QueueState> = _queueState.asStateFlow()

    val volume: StateFlow<Int> = output.volume

    init {
        scope.launch {
            output.state.collect { state ->
                history.onState(state)
                if (state is PlaybackState.Ended) advance()
            }
        }
    }

    fun playQueue(items: List<QueueItem>, startIndex: Int = 0) {
        queueManager.setQueue(items, startIndex)
        refreshQueueState()
        queueManager.currentItem?.let { playItem(it) }
    }

    /** Convenience for screens that just have a list of tracks to play, in order. */
    fun playTracks(tracks: List<Track>, startIndex: Int = 0) {
        playQueue(tracks.map { QueueItem(id = "q${it.id}", track = it) }, startIndex)
    }

    /** "Shuffle All": shuffles [tracks] before playing, so playback starts on track 1
     * of a genuinely fresh shuffled order instead of the sorted list's first track. */
    fun playTracksShuffled(tracks: List<Track>) {
        queueManager.setQueueShuffled(tracks.map { QueueItem(id = "q${it.id}", track = it) })
        refreshQueueState()
        queueManager.currentItem?.let { playItem(it) }
    }

    /** "Smart shuffle" on the Tracks tab: like [playTracksShuffled], ordered from
     * listening history (see SmartShuffle). The history read takes a few ms on IO. */
    fun playTracksSmartShuffled(tracks: List<Track>) {
        scope.launch {
            val orderer = smartShuffler.orderer()
            queueManager.setQueueShuffled(
                tracks.map { QueueItem(id = "q${it.id}", track = it) },
                QueueManager.ShuffleMode.SMART,
                orderer,
            )
            refreshQueueState()
            queueManager.currentItem?.let { playItem(it) }
        }
    }

    /** Appends [tracks] to the end of the current queue without interrupting playback.
     * Each slot gets a fresh id — [tracks] may already include the track(s) playing now. */
    fun addToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        queueManager.addAll(tracks.map { QueueItem(id = "q${it.id}-${nextQueueItemSeq++}", track = it) })
        refreshQueueState()
    }

    fun togglePlayPause() {
        when (playbackState.value) {
            is PlaybackState.Playing -> scope.launch { output.pause() }
            is PlaybackState.Paused -> scope.launch { output.resume() }
            else -> if (finished) replay() else queueManager.currentItem?.let { playItem(it) }
        }
    }

    /** Plays a finished queue again from the top, reshuffled if a shuffle mode is on. */
    fun replay() {
        when (queueManager.shuffleMode) {
            QueueManager.ShuffleMode.SMART -> scope.launch {
                queueManager.restart(smartShuffler.orderer())
                queueManager.currentItem?.let { playItem(it) }
            }
            else -> {
                queueManager.restart()
                queueManager.currentItem?.let { playItem(it) }
            }
        }
    }

    fun next() {
        val hadItems = queueManager.size > 0
        history.endCurrent(PlayEndReason.SKIPPED)
        val next = queueManager.next()
        when {
            next != null -> playItem(next)
            hadItems -> finishQueue()
        }
    }

    /**
     * More than [RESTART_ON_PREVIOUS_AFTER_MS] into the track: back to its start (a seek,
     * not a new listen). Otherwise the previous track — the usual music-player rule, for
     * every "previous": the button, the mini-player swipe, lock screen and headset keys.
     */
    fun previous() {
        if (playbackState.value.previousRestartsTrack()) {
            seekTo(0)
            return
        }
        history.endCurrent(PlayEndReason.PREVIOUS)
        queueManager.previous()?.let { playItem(it) }
    }

    fun jumpTo(queueItemId: String) {
        queueManager.jumpTo(queueItemId)?.let { playItem(it) }
    }

    fun move(from: Int, to: Int) {
        queueManager.move(from, to)
        refreshQueueState()
    }

    fun remove(queueItemId: String) {
        queueManager.remove(queueItemId)
        refreshQueueState()
    }

    fun restore(queueItemId: String, index: Int) {
        queueManager.restore(queueItemId, index)
        refreshQueueState()
    }

    fun setShuffleMode(mode: QueueManager.ShuffleMode) {
        if (mode != QueueManager.ShuffleMode.SMART) {
            queueManager.setShuffleMode(mode)
            refreshQueueState()
            return
        }
        scope.launch {
            val orderer = smartShuffler.orderer()
            queueManager.setShuffleMode(mode, orderer)
            refreshQueueState()
        }
    }

    fun setRepeatMode(mode: QueueManager.RepeatMode) {
        queueManager.setRepeatMode(mode)
        refreshQueueState()
    }

    fun seekTo(seconds: Int) {
        scope.launch { output.seek(seconds) }
    }

    fun setVolume(percent: Int) {
        scope.launch { output.setVolume(percent.coerceIn(0, 100)) }
    }

    private fun advance() {
        history.endCurrent(PlayEndReason.COMPLETED)
        val next = queueManager.onTrackEnded()
        if (next != null) {
            refreshQueueState()
            playItem(next)
        } else {
            finishQueue()
        }
    }

    /**
     * Queue exhausted with repeat off — same for a natural end-of-track as for skipping
     * forward past the last track. Stops the output but keeps the queue: the player shows
     * "Queue finished" and play starts it again (it used to clear the queue, which left
     * Now Playing with nothing to show — a black screen).
     */
    private fun finishQueue() {
        scope.launch { output.stop() }
        finished = true
        refreshQueueState()
    }

    private fun playItem(item: QueueItem) {
        finished = false
        refreshQueueState()
        ensureServiceStarted()
        history.onItemStarted(item)
        scope.launch { output.play(item) }
    }

    private fun refreshQueueState() {
        _queueState.value = QueueState(
            items = queueManager.queue,
            currentIndex = queueManager.currentIndex,
            repeatMode = queueManager.repeatMode,
            shuffleMode = queueManager.shuffleMode,
            finished = finished,
        )
    }

    private fun ensureServiceStarted() {
        if (serviceStarted) return
        serviceStarted = true
        ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
    }
}
