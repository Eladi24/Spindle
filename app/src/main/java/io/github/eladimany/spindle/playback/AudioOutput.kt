package io.github.eladimany.spindle.playback

import io.github.eladimany.spindle.core.model.OutputCapabilities
import io.github.eladimany.spindle.core.model.PlaybackState
import io.github.eladimany.spindle.core.model.QueueItem
import kotlinx.coroutines.flow.StateFlow

/** Where sound comes out — the phone's speakers, or a BluOS Node. */
interface AudioOutput {
    val state: StateFlow<PlaybackState>
    val capabilities: OutputCapabilities

    suspend fun play(item: QueueItem)
    suspend fun pause()
    suspend fun resume()
    suspend fun stop()
    suspend fun seek(seconds: Int)
    suspend fun setVolume(percent: Int)
}
