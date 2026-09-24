package io.github.eladimany.spindle.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Bumped up from Material3's defaults (4/8/12/16/28) — every component that reads
 * `MaterialTheme.shapes.*` (Card, Slider, NavigationBar's selected pill, dialogs,
 * bottom sheets, buttons) picks this up automatically, no per-screen changes needed.
 */
val SpindleShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
