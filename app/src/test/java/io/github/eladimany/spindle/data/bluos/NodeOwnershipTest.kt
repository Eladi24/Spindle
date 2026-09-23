package io.github.eladimany.spindle.data.bluos

import io.github.eladimany.spindle.data.bluos.NodeOwnership.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class NodeOwnershipTest {

    private val token = "3f1c9a2e-0000-4000-8000-000000000001"

    private fun status(state: String, streamUrl: String?, service: String? = null) = BluOsStatus(
        etag = "1", state = state, secs = 10, totalSeconds = 200, canSeek = true,
        streamUrl = streamUrl, title1 = null, quality = null, streamFormat = null, serviceType = service,
    )

    private fun classify(s: BluOsStatus, seenOurs: Boolean = true, msSincePlay: Long = 60_000) =
        NodeOwnership.classify(s, token, seenOurs, msSincePlay)

    @Test
    fun ourUrlIsOurs() {
        assertEquals(Verdict.OURS, classify(status("stream", "http://10.0.0.11:8080/t/$token")))
    }

    @Test
    fun previousTrackTokenIsTakenOver() {
        // Same server, stale token — e.g. another controller replayed an old URL.
        assertEquals(Verdict.TAKEN_OVER, classify(status("stream", "http://10.0.0.11:8080/t/other-token")))
    }

    @Test
    fun radioStreamIsTakenOver() {
        assertEquals(Verdict.TAKEN_OVER, classify(status("stream", "http://radio.example/live.mp3", "TuneIn")))
    }

    @Test
    fun bluOsQueuePlaybackWithoutUrlIsTakenOver() {
        assertEquals(Verdict.TAKEN_OVER, classify(status("play", null, "Tidal")))
    }

    @Test
    fun physicalInputIsTakenOver() {
        assertEquals(Verdict.TAKEN_OVER, classify(status("stream", null, "AudioInputs")))
    }

    @Test
    fun stopWithoutUrlIsUnknown() {
        assertEquals(Verdict.UNKNOWN, classify(status("stop", null)))
    }

    @Test
    fun pauseWithoutUrlIsUnknown() {
        assertEquals(Verdict.UNKNOWN, classify(status("pause", null)))
    }

    @Test
    fun foreignStatusRightAfterPlayIsUnknownUntilGraceOrConfirmation() {
        val foreign = status("stream", "http://radio.example/live.mp3")
        assertEquals(Verdict.UNKNOWN, classify(foreign, seenOurs = false, msSincePlay = 2_000))
        assertEquals(Verdict.TAKEN_OVER, classify(foreign, seenOurs = false, msSincePlay = 10_000))
        assertEquals(Verdict.TAKEN_OVER, classify(foreign, seenOurs = true, msSincePlay = 2_000))
    }
}
