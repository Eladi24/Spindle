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
    enum class ShuffleMode { OFF, SHUFFLE, SMART }

    /**
     * Builds a play order (indices into [items]) — [pinnedFirst], if given, must come
     * first. Plain shuffle is [RANDOM]; smart shuffle's orderer is built by
     * PlaybackController from listening history (see SmartShuffle).
     */
    fun interface ShuffleOrderer {
        fun order(items: List<QueueItem>, pinnedFirst: Int?): List<Int>

        companion object {
            val RANDOM = ShuffleOrderer { items, pinnedFirst ->
                val rest = items.indices.filter { it != pinnedFirst }.shuffled()
                listOfNotNull(pinnedFirst) + rest
            }
        }
    }

    private val items = mutableListOf<QueueItem>()
    private var order = mutableListOf<Int>()
    private var position = -1

    var repeatMode: RepeatMode = RepeatMode.OFF
        private set

    var shuffleMode = ShuffleMode.OFF
        private set

    val isShuffled: Boolean get() = shuffleMode != ShuffleMode.OFF

    val size: Int get() = order.size
    val currentIndex: Int get() = position
    val currentItem: QueueItem? get() = order.getOrNull(position)?.let { items[it] }
    val queue: List<QueueItem> get() = order.map { items[it] }

    fun setQueue(newItems: List<QueueItem>, startIndex: Int = 0) {
        items.clear()
        items.addAll(newItems)
        order = items.indices.toMutableList()
        shuffleMode = ShuffleMode.OFF
        position = if (items.isEmpty()) -1 else startIndex.coerceIn(0, items.size - 1)
    }

    /** Like [setQueue], but the play order is shuffled up front and playback starts
     * at the first slot of that shuffled order — for "Shuffle All", where there's no
     * "currently playing track" yet to preserve, unlike [setShuffled]. */
    fun setQueueShuffled(
        newItems: List<QueueItem>,
        mode: ShuffleMode = ShuffleMode.SHUFFLE,
        orderer: ShuffleOrderer = ShuffleOrderer.RANDOM,
    ) {
        items.clear()
        items.addAll(newItems)
        order = orderer.order(items.toList(), null).toMutableList()
        shuffleMode = mode
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
    fun setShuffled(enabled: Boolean) =
        setShuffleMode(if (enabled) ShuffleMode.SHUFFLE else ShuffleMode.OFF)

    /** Same as [setShuffled], for all three modes. Shuffle ↔ smart reorders again,
     * still keeping the current track first. */
    fun setShuffleMode(mode: ShuffleMode, orderer: ShuffleOrderer = ShuffleOrderer.RANDOM) {
        if (mode == shuffleMode) return
        shuffleMode = mode
        if (mode != ShuffleMode.OFF) {
            val currentIndex = order.getOrNull(position)
            order = orderer.order(items.toList(), currentIndex).toMutableList()
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

    /**
     * Back to the top of the queue after it has played out. A shuffled queue gets a
     * fresh order from [orderer] (a new shuffle, or a new smart shuffle); an unshuffled
     * one keeps its order, including any moves the user made.
     */
    fun restart(orderer: ShuffleOrderer = ShuffleOrderer.RANDOM) {
        if (order.isEmpty()) return
        if (shuffleMode != ShuffleMode.OFF) {
            val slots = order.toList()
            order = orderer.order(slots.map { items[it] }, null).map { slots[it] }.toMutableList()
        }
        position = 0
    }

    /**
     * Undo for [remove]: puts a removed item back at [index] in the play order.
     * [remove] only drops it from the order, so the item itself is still here.
     */
    fun restore(queueItemId: String, index: Int) {
        val itemIndex = items.indexOfFirst { it.id == queueItemId }
        if (itemIndex == -1 || itemIndex in order) return
        val at = index.coerceIn(0, order.size)
        order.add(at, itemIndex)
        position = when {
            position == -1 -> 0
            at <= position -> position + 1
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
