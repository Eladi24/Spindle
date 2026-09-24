package io.github.eladimany.spindle.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SweepGradientShader
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// The AI surfaces' look, from the "Spindle AI playlists" mockup canvas: a light that
// runs around the edge, a soft drifting glow behind, a twinkling two-star sparkle.
// Everything animates only while composed, i.e. while the surface is on screen.

/** The glow colours behind AI surfaces — deeper than the theme's primary/tertiary so they read as light, not fill. */
private val GlowViolet = Color(0xFF5B4BE0)
private val GlowPink = Color(0xFFB0487A)

/** 0–360, one turn per [periodMs]. Drives [edgeGlint]. */
@Composable
fun rememberGlintAngle(periodMs: Int = 5_000): State<Float> =
    rememberInfiniteTransition(label = "glint").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(periodMs, easing = LinearEasing)),
        label = "glintAngle",
    )

/** 0–1 and back, slowly. Drives [aiGlowBehind]. */
@Composable
fun rememberDriftPhase(periodMs: Int = 14_000): State<Float> =
    rememberInfiniteTransition(label = "drift").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMs, easing = LinearEasing)),
        label = "driftPhase",
    )

/**
 * A hairline border with a violet-to-pink glint travelling around it. [angle] is read at
 * draw time, so the animation redraws without recomposing.
 */
fun Modifier.edgeGlint(
    angle: () -> Float,
    cornerRadius: Dp,
    glint: Color,
    glintTail: Color,
    hairline: Color = glint.copy(alpha = 0.22f),
    width: Dp = 1.dp,
): Modifier = drawWithContent {
    drawContent()
    val stroke = width.toPx()
    val inset = stroke / 2
    val topLeft = Offset(inset, inset)
    val rectSize = Size(size.width - stroke, size.height - stroke)
    val radius = CornerRadius(cornerRadius.toPx() - inset)
    drawRoundRect(hairline, topLeft, rectSize, radius, style = Stroke(stroke))

    val shader = SweepGradientShader(
        center = center,
        colors = listOf(Color.Transparent, Color.Transparent, glint, glintTail, Color.Transparent),
        colorStops = listOf(0f, 0.6f, 0.8f, 0.89f, 1f),
    )
    shader.setLocalMatrix(android.graphics.Matrix().apply { setRotate(angle(), center.x, center.y) })
    val brush = ShaderBrush(shader)
    // A wider, faint pass under the line reads as the light's glow.
    drawRoundRect(brush, topLeft, rectSize, radius, alpha = 0.35f, style = Stroke(stroke * 4))
    drawRoundRect(brush, topLeft, rectSize, radius, style = Stroke(stroke * 1.5f))
}

/**
 * Two soft blobs of light drifting behind the element, spilling past its sides and
 * bottom. Each blob's centre stays at least one radius below the top edge, so it has
 * faded out by there — a scrolling list clips whatever is drawn above its own top,
 * which showed as a hard line under the top bar. [strength] is the peak alpha.
 */
fun Modifier.aiGlowBehind(phase: () -> Float, strength: Float = 0.45f): Modifier = drawBehind {
    val t = phase() * 2 * PI
    val r1 = size.minDimension.coerceAtLeast(160.dp.toPx()) * 0.9f
    val r2 = r1 * 0.8f
    val c1 = Offset(
        size.width * 0.2f + 24.dp.toPx() * sin(t).toFloat(),
        maxOf(size.height * 0.35f, r1) - 16.dp.toPx() * (1 - cos(t).toFloat()) / 2,
    )
    val c2 = Offset(
        size.width * 0.9f - 20.dp.toPx() * cos(t).toFloat(),
        maxOf(size.height * 0.75f, r2) + 14.dp.toPx() * sin(t).toFloat().coerceAtLeast(0f),
    )
    drawCircle(Brush.radialGradient(listOf(GlowViolet.copy(alpha = strength), Color.Transparent), c1, r1), r1, c1)
    drawCircle(Brush.radialGradient(listOf(GlowPink.copy(alpha = strength * 0.7f), Color.Transparent), c2, r2), r2, c2)
}

/** The AI mark: the smart-shuffle sparkle plus a small pink star, gently twinkling. */
@Composable
fun TwinkleSparkle(size: Dp, modifier: Modifier = Modifier, animate: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    val pulse by rememberInfiniteTransition(label = "twinkle").animateFloat(
        initialValue = 1f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(1_200), RepeatMode.Reverse),
        label = "twinkleScale",
    )
    val scale = if (animate) pulse else 1f
    Box(modifier.size(size)) {
        Icon(
            SparkleIcon,
            contentDescription = null,
            tint = colors.primary,
            modifier = Modifier
                .size(size * 0.85f)
                .align(Alignment.BottomStart)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        )
        Icon(
            SparkleIcon,
            contentDescription = null,
            tint = colors.tertiary,
            modifier = Modifier
                .size(size * 0.36f)
                .align(Alignment.TopEnd)
                .offset(x = size * 0.02f)
                .graphicsLayer {
                    val inverse = 1.8f - scale
                    scaleX = inverse
                    scaleY = inverse
                    alpha = 0.6f + (1f - scale) * 2f
                },
        )
    }
}

