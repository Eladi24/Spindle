package io.github.eladimany.spindle.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp

/** A soft coloured halo behind the element — the same shadow-layer trick as the nav bar's active-tab line. */
fun Modifier.glow(color: Color, radius: Dp, cornerRadius: Dp) =
    drawBehind {
        drawIntoCanvas { canvas ->
            val paint = Paint()
            paint.asFrameworkPaint().apply {
                this.color = android.graphics.Color.TRANSPARENT
                setShadowLayer(radius.toPx(), 0f, 0f, color.toArgb())
            }
            canvas.drawRoundRect(
                0f, 0f, size.width, size.height,
                cornerRadius.toPx(), cornerRadius.toPx(),
                paint,
            )
        }
    }
