package io.github.eladimany.spindle.playback

import io.github.eladimany.spindle.core.model.QueueItem

/**
 * Owns the play queue. The Node can't hold a custom-URL queue (see docs/bluos-api.md),
 * so shuffle/repeat/next/previous live here regardless of which [AudioOutput] is active.
 *
 * Shuffle permutes [order], a list of indices into the stable [items] backing list —
 * never [items] itself — so turning shuffle off restores the original order exactly.
 */
class QueueManager {
    enum class RepeatMode { OFF, ALL, ONE }

    private val items = mutableListOf<QueueItem>()
    private var order = mutableListOf<Int>()
    private var position = -1

    var repeatMode: RepeatMode = RepeatMode.OFF
        private set

    var isShuffled = false
        private set

    val size: Int get() = order.size
    val currentIndex: Int get() = position
    val currentItem: QueueItem? get() = order.getOrNull(position)?.let { items[it] }
    val queue: List<QueueItem> get() = order.map { items[it] }

    fun setQueue(newItems: List<QueueItem>, startIndex: Int = 0) {
        items.clear()
        items.addAll(newItems)
        order = items.indices.toMutableList()
        isShuffled = false
        position = if (items.isEmpty()) -1 else startIndex.coerceIn(0, items.size - 1)
    }

    fun clear() {
        items.clear()
        order.clear()
        position = -1
    }

    fun add(item: QueueItem) {
        items.add(item)
        order.add(items.lastIndex)
        if (position == -1) position = 0
    }

    fun setRepeatMode(mode: RepeatMode) {
        repeatMode = mode
    }

    fun setShuffled(enabled: Boolean) {
        if (enabled == isShuffled) return
        val currentId = currentItem?.id
        order = if (enabled) {
            items.indices.toMutableList().apply { shuffle() }
        } else {
            items.indices.toMutableList()
        }
        isShuffled = enabled
        position = currentId?.let { id -> order.indexOfFirst { items[it].id == id } } ?: -1
        if (position == -1 && order.isNotEmpty()) position = 0
    }

    fun jumpTo(queueItemId: String): QueueItem? {
        val idx = order.indexOfFirst { items[it].id == queueItemId }
        if (idx == -1) return null
        position = idx
        return currentItem
    }

    /** User-initiated skip. Ignores [RepeatMode.ONE] — that only affects [onTrackEnded]. */
    fun next(): QueueItem? {
        if (order.isEmpty()) return null
        val nextPos = position + 1
        return when {
            nextPos < order.size -> { position = nextPos; currentItem }
            repeatMode == RepeatMode.ALL -> { position = 0; currentItem }
            else -> currentItem
        }
    }

    /** User-initiated skip back. */
    fun previous(): QueueItem? {
        if (order.isEmpty()) return null
        val prevPos = position - 1
        return when {
            prevPos >= 0 -> { position = prevPos; currentItem }
            repeatMode == RepeatMode.ALL -> { position = order.size - 1; currentItem }
            else -> currentItem
        }
    }

    /**
     * Called when the current track finishes naturally. Distinct from [next] because
     * [RepeatMode.ONE] replays the same track here but doesn't block a manual skip.
     * Returns null when the queue is exhausted and repeat is off — the caller should stop.
     */
    fun onTrackEnded(): QueueItem? {
        if (order.isEmpty()) return null
        if (repeatMode == RepeatMode.ONE) return currentItem
        val nextPos = position + 1
        return when {
            nextPos < order.size -> { position = nextPos; currentItem }
            repeatMode == RepeatMode.ALL -> { position = 0; currentItem }
            else -> null
        }
    }

    fun move(from: Int, to: Int) {
        if (from == to || from !in order.indices || to !in order.indices) return
        val value = order.removeAt(from)
        order.add(to, value)
        position = when {
            position == from -> to
            from < position && to >= position -> position - 1
            from > position && to <= position -> position + 1
            else -> position
        }
    }

    fun remove(queueItemId: String) {
        val idx = order.indexOfFirst { items[it].id == queueItemId }
        if (idx == -1) return
        order.removeAt(idx)
        position = when {
            order.isEmpty() -> -1
            idx < position -> position - 1
            idx == position -> position.coerceAtMost(order.size - 1)
            else -> position
        }
    }
}
