package io.github.eladimany.spindle.core.model

sealed interface PlaybackState {
    data object Idle : PlaybackState
    data class Buffering(val item: QueueItem) : PlaybackState
    /**
     * [positionMs] is a snapshot as of [capturedAtMs] (wall-clock millis), not a live
     * value — consumers interpolate locally (`positionMs + (now - capturedAtMs)`)
     * rather than expecting a push every second. This is required for Phase 2's
     * BluOS long-poll, which doesn't push `secs`; building the habit now.
     */
    data class Playing(
        val item: QueueItem,
        val positionMs: Long,
        val durationMs: Long,
        val capturedAtMs: Long = System.currentTimeMillis(),
    ) : PlaybackState
    data class Paused(val item: QueueItem, val positionMs: Long, val durationMs: Long) : PlaybackState
    /** The track finished naturally — distinct from [Idle] so auto-advance has a clean signal. */
    data class Ended(val item: QueueItem) : PlaybackState
    data class Error(val item: QueueItem?, val message: String) : PlaybackState
}

/**
 * How far into the track playback is right now: [PlaybackState.Playing]'s snapshot moved
 * on by the time since it was captured, [PlaybackState.Paused]'s as is; null otherwise.
 */
fun PlaybackState.elapsedMs(nowMs: Long = System.currentTimeMillis()): Long? = when (this) {
    is PlaybackState.Playing -> (positionMs + (nowMs - capturedAtMs)).coerceIn(0, durationMs.coerceAtLeast(0))
    is PlaybackState.Paused -> positionMs
    else -> null
}

/** "Previous" past this far into a track restarts it instead of going back a track. */
const val RESTART_ON_PREVIOUS_AFTER_MS = 5_000L

fun PlaybackState.previousRestartsTrack(): Boolean = (elapsedMs() ?: 0L) > RESTART_ON_PREVIOUS_AFTER_MS
