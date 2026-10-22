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
    /**
     * A stop this close to the end still counts as the track ending when the phone had
     * already served the whole file: the Node had every byte, so nobody cut it short.
     * User report (2026-10-22): the player stuck ~15 s before the end (6:00 of 6:15) with
     * nothing playing — a natural end judged "stopped mid-track".
     */
    const val SERVED_TO_END_TOLERANCE_SECONDS = 45

    fun isNaturalEnd(
        lastKnownSecs: Int,
        lastKnownTotalSeconds: Int?,
        servedToEnd: Boolean,
        fileDurationSeconds: Int? = null,
    ): Boolean {
        // The Node's totlen and the file's own length can disagree (VBR estimates); the
        // shorter one is where the audio really runs out.
        val total = listOfNotNull(lastKnownTotalSeconds, fileDurationSeconds).filter { it > 0 }.minOrNull()
        return isNaturalEnd(lastKnownSecs, total) ||
            (servedToEnd && isNaturalEnd(lastKnownSecs, total, SERVED_TO_END_TOLERANCE_SECONDS))
    }

    fun isNaturalEnd(lastKnownSecs: Int, lastKnownTotalSeconds: Int?, toleranceSeconds: Int = 2): Boolean {
        val total = lastKnownTotalSeconds ?: return false
        if (total <= 0) return false
        return lastKnownSecs >= total - toleranceSeconds
    }
}
