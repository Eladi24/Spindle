package io.github.eladimany.spindle.ui.components

import android.os.SystemClock
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.drop

/**
 * Drops row taps that land while a list is scrolling or just after it stopped.
 *
 * Recorded on the A73: fast back-to-back flicks produce stray zero-movement touches
 * (8–36ms, no motion at all) as the finger bounces off the glass between strokes.
 * With no movement they're indistinguishable from a real tap, so they opened
 * artists/played tracks mid-scroll. A deliberate tap comes after the list has
 * settled and you've found the row, so ignoring taps within [COOLDOWN_MS] of the
 * last scroll costs nothing real.
 */
@Stable
class ScrollTapGuard internal constructor(private val state: ScrollableState) {
    internal var lastScrollEndMs = 0L

    fun allowsTap(): Boolean =
        !state.isScrollInProgress && SystemClock.uptimeMillis() - lastScrollEndMs > COOLDOWN_MS

    /** Wraps [onClick] so it only fires when [allowsTap]. */
    fun guard(onClick: () -> Unit): () -> Unit = { if (allowsTap()) onClick() }

    private companion object {
        const val COOLDOWN_MS = 400L
    }
}

@Composable
fun rememberScrollTapGuard(state: ScrollableState): ScrollTapGuard {
    val guard = remember(state) { ScrollTapGuard(state) }
    LaunchedEffect(guard) {
        // drop(1): the initial "not scrolling" isn't a scroll ending, and would block taps
        // for the first moments after the screen opens.
        snapshotFlow { state.isScrollInProgress }.drop(1).collect { scrolling ->
            if (!scrolling) guard.lastScrollEndMs = SystemClock.uptimeMillis()
        }
    }
    return guard
}
