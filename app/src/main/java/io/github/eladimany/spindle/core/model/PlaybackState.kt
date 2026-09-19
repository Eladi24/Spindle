package io.github.eladimany.spindle.core.model

sealed interface PlaybackState {
    data object Idle : PlaybackState
    data class Buffering(val item: QueueItem) : PlaybackState
    data class Playing(val item: QueueItem, val positionMs: Long, val durationMs: Long) : PlaybackState
    data class Paused(val item: QueueItem, val positionMs: Long, val durationMs: Long) : PlaybackState
    data class Error(val item: QueueItem?, val message: String) : PlaybackState
}
