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
}
