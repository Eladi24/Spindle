package io.github.eladimany.spindle.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Smart shuffle's mark: a four-point star. Tint it like any icon. */
val SparkleIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Sparkle",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 2f)
            lineTo(14.2f, 8.8f)
            lineTo(21f, 11f)
            lineTo(14.2f, 13.2f)
            lineTo(12f, 20f)
            lineTo(9.8f, 13.2f)
            lineTo(3f, 11f)
            lineTo(9.8f, 8.8f)
            close()
        }
    }.build()
}
