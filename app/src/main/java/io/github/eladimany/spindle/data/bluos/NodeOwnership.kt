package io.github.eladimany.spindle.data.bluos

/**
 * Decides whether a `/Status` still describes *our* stream, or whether someone
 * else (BluOS app, another controller, a physical input) has taken the Node over.
 * The only reliable proof of ownership is `streamUrl` containing the token we
 * served — `title1` etc. can coincide.
 */
object NodeOwnership {
    enum class Verdict {
        /** Our stream. */
        OURS,
        /** Something else is playing — step back, don't auto-advance or fight it. */
        TAKEN_OVER,
        /** Can't tell from this status alone ("stop"/"pause" without a URL, or too soon after /Play). */
        UNKNOWN,
    }

    /** States in which the Node is actively producing sound from some source. */
    private val ACTIVE_STATES = setOf("stream", "play")

    /**
     * @param ourToken the token of the track we last asked the Node to play.
     * @param seenOurs whether any status since that /Play has been [Verdict.OURS].
     * @param msSincePlay time since that /Play. Until we've seen our stream or
     *   [graceMs] passes, a foreign status may just be the Node's pre-/Play state.
     */
    fun classify(
        status: BluOsStatus,
        ourToken: String,
        seenOurs: Boolean,
        msSincePlay: Long,
        graceMs: Long = 10_000,
    ): Verdict {
        if (status.isHijacked) return Verdict.TAKEN_OVER
        val url = status.streamUrl
        if (url != null && url.contains("/t/$ourToken")) return Verdict.OURS
        // "stop" and "pause" can drop streamUrl even for our own stream (bluos-api.md:
        // it disappears on stop; pause is unverified) — never read those as foreign.
        val foreign = url != null || status.state in ACTIVE_STATES
        if (!foreign) return Verdict.UNKNOWN
        return if (seenOurs || msSincePlay >= graceMs) Verdict.TAKEN_OVER else Verdict.UNKNOWN
    }
}
