package io.github.eladimany.spindle.playback

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.eladimany.spindle.core.model.PlaybackState
import io.github.eladimany.spindle.core.model.QueueItem
import io.github.eladimany.spindle.core.model.Track
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
import javax.inject.Inject
import javax.inject.Singleton

data class QueueState(
    val items: List<QueueItem> = emptyList(),
    val currentIndex: Int = -1,
    val repeatMode: QueueManager.RepeatMode = QueueManager.RepeatMode.OFF,
    val isShuffled: Boolean = false,
)

/**
 * Coordinates a [QueueManager] and one active [AudioOutput]. Deliberately doesn't know
 * which [AudioOutput] implementation it holds — see CLAUDE.md's architecture section.
 */
@Singleton
class PlaybackController @Inject constructor(
    private val output: AudioOutput,
    @ApplicationContext private val context: Context,
) {
    private val queueManager = QueueManager()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var serviceStarted = false
    private var nextQueueItemSeq = 0L

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
            else -> queueManager.currentItem?.let { playItem(it) }
        }
    }

    fun next() {
        val hadItems = queueManager.size > 0
        val next = queueManager.next()
        when {
            next != null -> playItem(next)
            hadItems -> stopAndClear()
        }
    }

    fun previous() {
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

    fun setShuffled(enabled: Boolean) {
        queueManager.setShuffled(enabled)
        refreshQueueState()
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
        val next = queueManager.onTrackEnded()
        if (next != null) {
            refreshQueueState()
            playItem(next)
        } else {
            stopAndClear()
        }
    }

    /** Queue exhausted with repeat off — stop output and clear, same for a natural
     * end-of-track as for skipping forward past the last track. */
    private fun stopAndClear() {
        scope.launch { output.stop() }
        queueManager.clear()
        refreshQueueState()
    }

    private fun playItem(item: QueueItem) {
        refreshQueueState()
        ensureServiceStarted()
        scope.launch { output.play(item) }
    }

    private fun refreshQueueState() {
        _queueState.value = QueueState(
            items = queueManager.queue,
            currentIndex = queueManager.currentIndex,
            repeatMode = queueManager.repeatMode,
            isShuffled = queueManager.isShuffled,
        )
    }

    private fun ensureServiceStarted() {
        if (serviceStarted) return
        serviceStarted = true
        ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
    }
}
