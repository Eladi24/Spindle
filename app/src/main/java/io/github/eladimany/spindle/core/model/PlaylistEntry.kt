package io.github.eladimany.spindle.core.model

/** One track's membership in a playlist — [crossRefId] identifies the membership row
 * itself (not the track), so the same track can appear more than once and remove/reorder
 * still target the right occurrence. */
data class PlaylistEntry(
    val crossRefId: Long,
    val position: Int,
    val track: Track,
)
