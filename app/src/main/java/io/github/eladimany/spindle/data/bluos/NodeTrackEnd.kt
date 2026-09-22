package io.github.eladimany.spindle.data.bluos

/**
 * Resolves the `state == "stop"` ambiguity documented in bluos-api.md:
 * that value means both "the track finished" and "someone pressed stop in
 * the BluOS app", and `/Status` gives no other signal to tell them apart.
 * The last known `secs`/`totlen` *before* the collapse (both disappear from
 * the document entirely once state becomes "stop") are the only evidence —
 * near the end means a natural finish, mid-track means a real stop.
 */
object NodeTrackEnd {
    /**
     * [toleranceSeconds] covers `secs` not ticking the literal final second
     * before the Node collapses to "stop" — bluos-api.md doesn't pin an
     * exact number here (this wasn't a Phase 0 measurement), so this is a
     * starting heuristic to tune once verified against the real Node.
     */
    fun isNaturalEnd(lastKnownSecs: Int, lastKnownTotalSeconds: Int?, toleranceSeconds: Int = 2): Boolean {
        val total = lastKnownTotalSeconds ?: return false
        if (total <= 0) return false
        return lastKnownSecs >= total - toleranceSeconds
    }
}
