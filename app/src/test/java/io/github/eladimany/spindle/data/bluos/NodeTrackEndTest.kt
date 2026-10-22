package io.github.eladimany.spindle.data.bluos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeTrackEndTest {

    @Test
    fun `secs at the very end is a natural end`() {
        assertTrue(NodeTrackEnd.isNaturalEnd(lastKnownSecs = 268, lastKnownTotalSeconds = 268))
    }

    @Test
    fun `secs within tolerance of the end is a natural end`() {
        assertTrue(NodeTrackEnd.isNaturalEnd(lastKnownSecs = 266, lastKnownTotalSeconds = 268))
    }

    @Test
    fun `secs mid-track is a user-initiated stop, not a natural end`() {
        assertFalse(NodeTrackEnd.isNaturalEnd(lastKnownSecs = 19, lastKnownTotalSeconds = 268))
    }

    @Test
    fun `no known total (nothing ever loaded) is never a natural end`() {
        assertFalse(NodeTrackEnd.isNaturalEnd(lastKnownSecs = 0, lastKnownTotalSeconds = null))
    }

    @Test
    fun `zero total is never a natural end`() {
        assertFalse(NodeTrackEnd.isNaturalEnd(lastKnownSecs = 0, lastKnownTotalSeconds = 0))
    }

    @Test
    fun `tolerance is configurable`() {
        assertTrue(NodeTrackEnd.isNaturalEnd(lastKnownSecs = 250, lastKnownTotalSeconds = 268, toleranceSeconds = 20))
        assertFalse(NodeTrackEnd.isNaturalEnd(lastKnownSecs = 250, lastKnownTotalSeconds = 268, toleranceSeconds = 2))
    }

    @Test
    fun `stuck 15 s before the end counts as the end once the whole file was served`() {
        // User report: frozen at 6:00 of 6:15 with nothing playing.
        assertTrue(NodeTrackEnd.isNaturalEnd(360, 375, servedToEnd = true))
        assertFalse(NodeTrackEnd.isNaturalEnd(360, 375, servedToEnd = false))
        // Mid-track stays a stop even with the whole file served (the Node buffers ahead).
        assertFalse(NodeTrackEnd.isNaturalEnd(120, 375, servedToEnd = true))
    }

    @Test
    fun `the shorter of the Node's and the file's length is the end`() {
        assertTrue(NodeTrackEnd.isNaturalEnd(359, 375, servedToEnd = false, fileDurationSeconds = 360))
        assertTrue(NodeTrackEnd.isNaturalEnd(359, null, servedToEnd = false, fileDurationSeconds = 360))
        assertFalse(NodeTrackEnd.isNaturalEnd(359, null, servedToEnd = false, fileDurationSeconds = 0))
    }
}
