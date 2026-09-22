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

    /** Like [setQueue], but the play order is shuffled up front and playback starts
     * at the first slot of that shuffled order — for "Shuffle All", where there's no
     * "currently playing track" yet to preserve, unlike [setShuffled]. */
    fun setQueueShuffled(newItems: List<QueueItem>) {
        items.clear()
        items.addAll(newItems)
        order = items.indices.toMutableList().apply { shuffle() }
        isShuffled = true
        position = if (items.isEmpty()) -1 else 0
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

    /** Appends [newItems] to the end of the play order, keeping playback where it is. */
    fun addAll(newItems: List<QueueItem>) {
        if (newItems.isEmpty()) return
        val start = items.size
        items.addAll(newItems)
        newItems.indices.forEach { order.add(start + it) }
        if (position == -1) position = 0
    }

    fun setRepeatMode(mode: RepeatMode) {
        repeatMode = mode
    }

    /**
     * Turning shuffle on keeps the currently playing track in place as track 1 of the
     * new order — only the *rest* get shuffled behind it — rather than shuffling
     * everything including the current track and leaving it wherever it lands. Turning
     * shuffle off restores the original order and finds where the current track sits in it.
     */
    fun setShuffled(enabled: Boolean) {
        if (enabled == isShuffled) return
        isShuffled = enabled
        if (enabled) {
            val currentIndex = order.getOrNull(position)
            val rest = items.indices.filter { it != currentIndex }.toMutableList().apply { shuffle() }
            order = if (currentIndex != null) (listOf(currentIndex) + rest).toMutableList() else rest
            position = if (order.isEmpty()) -1 else 0
        } else {
            val currentId = currentItem?.id
            order = items.indices.toMutableList()
            position = currentId?.let { id -> order.indexOfFirst { items[it].id == id } } ?: -1
            if (position == -1 && order.isNotEmpty()) position = 0
        }
    }

    fun jumpTo(queueItemId: String): QueueItem? {
        val idx = order.indexOfFirst { items[it].id == queueItemId }
        if (idx == -1) return null
        position = idx
        return currentItem
    }

    /**
     * User-initiated skip. Ignores [RepeatMode.ONE] — that only affects [onTrackEnded].
     * Returns null when already on the last track and repeat isn't ALL — the caller
     * should stop and clear, same as [onTrackEnded] running out — not replay the
     * current track, which is what returning [currentItem] here used to do.
     */
    fun next(): QueueItem? {
        if (order.isEmpty()) return null
        val nextPos = position + 1
        return when {
            nextPos < order.size -> { position = nextPos; currentItem }
            repeatMode == RepeatMode.ALL -> { position = 0; currentItem }
            else -> null
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
