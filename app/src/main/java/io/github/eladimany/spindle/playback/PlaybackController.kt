package io.github.eladimany.spindle.playback

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.eladimany.spindle.core.model.PlaybackState
import io.github.eladimany.spindle.core.model.QueueItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

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

    val playbackState: StateFlow<PlaybackState> = output.state

    val queue: List<QueueItem> get() = queueManager.queue
    val currentIndex: Int get() = queueManager.currentIndex
    val repeatMode: QueueManager.RepeatMode get() = queueManager.repeatMode
    val isShuffled: Boolean get() = queueManager.isShuffled

    init {
        scope.launch {
            output.state.collect { state ->
                if (state is PlaybackState.Ended) advance()
            }
        }
    }

    fun playQueue(items: List<QueueItem>, startIndex: Int = 0) {
        queueManager.setQueue(items, startIndex)
        queueManager.currentItem?.let { playItem(it) }
    }

    fun togglePlayPause() {
        when (playbackState.value) {
            is PlaybackState.Playing -> scope.launch { output.pause() }
            is PlaybackState.Paused -> scope.launch { output.resume() }
            else -> queueManager.currentItem?.let { playItem(it) }
        }
    }

    fun next() {
        queueManager.next()?.let { playItem(it) }
    }

    fun previous() {
        queueManager.previous()?.let { playItem(it) }
    }

    fun setShuffled(enabled: Boolean) = queueManager.setShuffled(enabled)

    fun setRepeatMode(mode: QueueManager.RepeatMode) = queueManager.setRepeatMode(mode)

    fun seekTo(seconds: Int) {
        scope.launch { output.seek(seconds) }
    }

    fun setVolume(percent: Int) {
        scope.launch { output.setVolume(percent) }
    }

    private fun advance() {
        val next = queueManager.onTrackEnded()
        if (next != null) {
            playItem(next)
        } else {
            scope.launch { output.stop() }
        }
    }

    private fun playItem(item: QueueItem) {
        ensureServiceStarted()
        scope.launch { output.play(item) }
    }

    private fun ensureServiceStarted() {
        if (serviceStarted) return
        serviceStarted = true
        ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
    }
}
