package io.github.eladimany.spindle.playback

import io.github.eladimany.spindle.core.model.QueueItem

/** Where audio comes from — a content URI directly, or a token URL served over HTTP. */
interface MediaSource {
    suspend fun resolveUrl(item: QueueItem): String
}
