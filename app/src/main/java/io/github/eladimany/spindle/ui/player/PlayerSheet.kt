package io.github.eladimany.spindle.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

enum class PlayerSheetValue { Collapsed, Expanded }

/** Height of the mini-player — the sheet's collapsed size, and the slot AppNavHost leaves for it. */
val MiniPlayerHeight = 64.dp

/**
 * The player as one sliding sheet (instead of a separate Now Playing screen): collapsed
 * it's exactly the mini-player, sitting in the slot AppNavHost reserves above the nav
 * island; tap or drag it up and it grows into the full player, the mini-player fading
 * out along its top edge; drag down (or Back) and it slides back.
 *
 * The sheet itself carries the drag, not the mini-player — the mini-player is removed
 * once it has faded, and a gesture on a removed node would be cancelled mid-drag.
 */
@Composable
fun PlayerSheet(
    state: AnchoredDraggableState<PlayerSheetValue>,
    /** Where the mini-player slot's top is, in this sheet's coordinates. */
    collapsedTopPx: Float,
    screenHeightPx: Float,
    containerModifier: (Shape) -> Modifier,
    miniPlayer: @Composable (onExpand: () -> Unit) -> Unit,
    player: @Composable (onCollapse: () -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    LaunchedEffect(collapsedTopPx) {
        if (collapsedTopPx <= 0f) return@LaunchedEffect
        // Keep the value the sheet is heading to. The default picks the anchor closest to
        // the current offset — and before the slot was measured both anchors sat at 0, so
        // the sheet popped open by itself the moment playback started (seen on the A73).
        state.updateAnchors(
            DraggableAnchors {
                PlayerSheetValue.Collapsed at collapsedTopPx
                PlayerSheetValue.Expanded at 0f
            },
            state.targetValue,
        )
    }
    val offset = state.offset.takeUnless { it.isNaN() } ?: collapsedTopPx
    val fraction = if (collapsedTopPx > 0f) (1f - offset / collapsedTopPx).coerceIn(0f, 1f) else 0f
    val expand: () -> Unit = { scope.launch { state.animateTo(PlayerSheetValue.Expanded) } }
    val collapse: () -> Unit = { scope.launch { state.animateTo(PlayerSheetValue.Collapsed) } }

    BackHandler(enabled = state.targetValue == PlayerSheetValue.Expanded) { collapse() }

    val velocityThresholdPx = with(density) { 400.dp.toPx() }
    val nestedScroll = remember(state) { SheetNestedScroll(state, velocityThresholdPx) }
    val fling = remember(state) { SheetFling(state, velocityThresholdPx) }

    // Dim what's behind as the player rises.
    if (fraction > 0f) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f * fraction)))
    }

    val collapsedHeightPx = with(density) { MiniPlayerHeight.toPx() }
    val heightPx = collapsedHeightPx + (screenHeightPx - collapsedHeightPx) * fraction
    val inset = lerp(12.dp, 0.dp, fraction)
    val corner = lerp(20.dp, 0.dp, fraction)
    val shape = RoundedCornerShape(topStart = corner, topEnd = corner, bottomStart = lerp(20.dp, 0.dp, fraction), bottomEnd = lerp(20.dp, 0.dp, fraction))
    Box(
        modifier = Modifier
            .offset { IntOffset(0, offset.roundToInt()) }
            .padding(horizontal = inset)
            .fillMaxWidth()
            .height(with(density) { heightPx.toDp() })
            .then(containerModifier(shape))
            .clip(shape)
            .nestedScroll(nestedScroll)
            .anchoredDraggable(state = state, orientation = Orientation.Vertical, flingBehavior = fling),
    ) {
        // The full player is laid out at full screen size from the start; the growing
        // sheet reveals it. Fades in over the first part of the slide.
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth()
                    // Pinned to the sheet's top: a child taller than its constraints is
                    // centred by default, which showed the middle of the player mid-slide.
                    .wrapContentHeight(align = Alignment.Top, unbounded = true)
                    .requiredHeight(with(density) { screenHeightPx.toDp() })
                    .graphicsLayer { alpha = ((fraction - 0.05f) / 0.35f).coerceIn(0f, 1f) },
            ) { player(collapse) }
        }
        if (fraction < 0.35f) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(MiniPlayerHeight)
                    .graphicsLayer { alpha = 1f - fraction / 0.35f },
            ) { miniPlayer(expand) }
        }
    }
}

/**
 * Lets a drag that starts on the player's scrollable content still move the sheet:
 * pulling down once the content is at its top collapses it, and a fling settles it.
 */
private class SheetNestedScroll(
    private val state: AnchoredDraggableState<PlayerSheetValue>,
    private val velocityThresholdPx: Float,
) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        // Sheet part-way open and the finger moves up: open the sheet before scrolling content.
        val delta = available.y
        return if (delta < 0f && source == NestedScrollSource.UserInput && state.offset > 0f) {
            Offset(0f, state.dispatchRawDelta(delta))
        } else {
            Offset.Zero
        }
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput && available.y != 0f) {
            Offset(0f, state.dispatchRawDelta(available.y))
        } else {
            Offset.Zero
        }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (state.offset > 0f && state.offset < (state.anchors.positionOf(PlayerSheetValue.Collapsed))) {
            settle(available.y)
            return available
        }
        return Velocity.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        settle(available.y)
        return available
    }

    private suspend fun settle(velocity: Float) {
        state.animateTo(settleTarget(state, velocity, velocityThresholdPx))
    }
}

/**
 * Where a released sheet goes: once it has moved [COMMIT_FRACTION] of the way from where it
 * rested, it finishes the move; a fast flick decides on its own. Direction, not the 50%
 * midpoint — and not velocity alone: the sheet moves with the finger, so a flick measured
 * on it reads as almost no velocity (seen on the A73: a quick flick up snapped back).
 */
private fun settleTarget(
    state: AnchoredDraggableState<PlayerSheetValue>,
    velocity: Float,
    velocityThresholdPx: Float,
): PlayerSheetValue {
    val collapsedPx = state.anchors.positionOf(PlayerSheetValue.Collapsed)
    val openFraction = if (collapsedPx > 0f) 1f - state.offset / collapsedPx else 1f
    return when {
        velocity < -velocityThresholdPx -> PlayerSheetValue.Expanded
        velocity > velocityThresholdPx -> PlayerSheetValue.Collapsed
        state.settledValue == PlayerSheetValue.Collapsed ->
            if (openFraction > COMMIT_FRACTION) PlayerSheetValue.Expanded else PlayerSheetValue.Collapsed
        else -> if (openFraction < 1f - COMMIT_FRACTION) PlayerSheetValue.Collapsed else PlayerSheetValue.Expanded
    }
}

private const val COMMIT_FRACTION = 0.12f

/** The sheet's own drag release: animates to [settleTarget] through the drag's scroll scope. */
private class SheetFling(
    private val state: AnchoredDraggableState<PlayerSheetValue>,
    private val velocityThresholdPx: Float,
) : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        val target = settleTarget(state, initialVelocity, velocityThresholdPx)
        var previous = state.offset
        animate(previous, state.anchors.positionOf(target), animationSpec = tween(280)) { value, _ ->
            scrollBy(value - previous)
            previous = value
        }
        return 0f
    }
}
