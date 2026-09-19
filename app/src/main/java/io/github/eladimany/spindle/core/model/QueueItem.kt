package io.github.eladimany.spindle.core.model

/**
 * A single slot in the play queue. [id] is distinct from [Track.id] because the
 * same track can be queued more than once; shuffle and reorder operate on [id].
 */
data class QueueItem(
    val id: String,
    val track: Track,
)
