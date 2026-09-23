package io.github.eladimany.spindle.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * How much of the bottom of the screen the floating mini-player + nav island cover,
 * system nav inset included. Content draws *behind* them (that's the see-through
 * effect), so every scrolling list adds this as bottom `contentPadding` to keep its
 * last row reachable. 0 on screens without the overlay (Now Playing, Queue).
 */
val LocalBottomOverlayPadding = compositionLocalOf { 0.dp }

/**
 * Frosted glass for the floating bottom elements. The real blur needs API 31+
 * (RenderEffect); below that Haze falls back to [HazeStyle.fallbackTint], which is
 * deliberately near-opaque so text over busy artwork stays readable without the blur.
 */
@Composable
fun spindleGlassStyle(): HazeStyle {
    val colors = MaterialTheme.colorScheme
    return HazeStyle(
        backgroundColor = colors.background,
        tint = HazeTint(colors.surfaceContainer.copy(alpha = 0.55f)),
        blurRadius = 20.dp,
        noiseFactor = 0f,
        fallbackTint = HazeTint(colors.surfaceContainer.copy(alpha = 0.92f)),
    )
}

/** Clip + blur-behind + hairline edge — the shared "glass card" treatment. */
fun Modifier.glassSurface(hazeState: HazeState, style: HazeStyle, shape: Shape): Modifier =
    this
        .clip(shape)
        .hazeEffect(state = hazeState, style = style)
        .border(1.dp, Color.White.copy(alpha = 0.08f), shape)

data class NavTab(val route: String, val label: String, val icon: ImageVector)

/**
 * The floating "island" nav bar. Only the active tab shows its label, inside a tinted
 * pill; a short glowing line on the island's top edge marks it as well. The glass
 * itself comes from the caller's [modifier] (see [glassSurface]).
 */
@Composable
fun FloatingNavBar(
    tabs: List<NavTab>,
    selectedRoute: String?,
    onSelect: (NavTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(64.dp)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            NavIslandItem(tab = tab, selected = tab.route == selectedRoute, onClick = { onSelect(tab) })
        }
    }
}

@Composable
private fun NavIslandItem(tab: NavTab, selected: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val contentColor by animateColorAsState(
        if (selected) primary else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "navContent",
    )
    val pillColor by animateColorAsState(
        if (selected) primary.copy(alpha = 0.15f) else Color.Transparent,
        label = "navPill",
    )
    val glowAlpha by animateFloatAsState(if (selected) 1f else 0f, label = "navGlow")

    Box(modifier = Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(width = 20.dp, height = 3.dp)
                .graphicsLayer { alpha = glowAlpha }
                .drawBehind {
                    // Shadow-layer glow: a blurred halo in the line's own color. Hardware-
                    // accelerated shadow layers on non-text draws need API 28+; on 26/27
                    // this is just the plain line, which is fine.
                    drawIntoCanvas { canvas ->
                        val paint = Paint().asFrameworkPaint().apply {
                            isAntiAlias = true
                            color = primary.toArgb()
                            setShadowLayer(8.dp.toPx(), 0f, 0f, primary.copy(alpha = 0.8f).toArgb())
                        }
                        val r = size.height / 2
                        canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, paint)
                    }
                },
        )
        Row(
            modifier = Modifier
                .height(44.dp)
                .clip(CircleShape)
                .background(pillColor)
                .selectable(selected = selected, onClick = onClick, role = Role.Tab)
                .padding(horizontal = if (selected) 14.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                tab.icon,
                // The label is hidden on inactive tabs, so the icon has to carry the name.
                contentDescription = if (selected) null else tab.label,
                tint = contentColor,
                modifier = Modifier.size(24.dp),
            )
            // Shown instantly, not animated: an expand/shrink width animation relayouts the
            // island every frame and measurably janked tab switches (A73, debug build).
            if (selected) {
                Text(
                    tab.label,
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = contentColor,
                    maxLines = 1,
                )
            }
        }
    }
}
