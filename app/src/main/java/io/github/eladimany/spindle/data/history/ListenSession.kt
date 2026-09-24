package io.github.eladimany.spindle.data.history

import io.github.eladimany.spindle.core.model.QueueItem

/**
 * Accumulates how long one queue item was actually heard. Playing emissions can
 * repeat (position updates, Node long-poll), so time is counted from the first
 * Playing to the next non-Playing, not per emission.
 */
class ListenSession(val item: QueueItem, val startedAtMs: Long) {
    private var accumulatedMs = 0L
    private var playingSinceMs: Long? = null

    fun onPlaying(nowMs: Long) {
        if (playingSinceMs == null) playingSinceMs = nowMs
    }

    fun onNotPlaying(nowMs: Long) {
        playingSinceMs?.let { accumulatedMs += (nowMs - it).coerceAtLeast(0) }
        playingSinceMs = null
    }

    fun listenedMs(nowMs: Long): Long =
        accumulatedMs + (playingSinceMs?.let { (nowMs - it).coerceAtLeast(0) } ?: 0)
}
