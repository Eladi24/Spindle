package io.github.eladimany.spindle.ui.smartplaylists

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.eladimany.spindle.data.smartplaylists.AiEngineStatus
import io.github.eladimany.spindle.data.smartplaylists.AiRequestState
import io.github.eladimany.spindle.ui.components.TwinkleSparkle
import io.github.eladimany.spindle.ui.components.aiGlowBehind
import io.github.eladimany.spindle.ui.components.edgeGlint
import io.github.eladimany.spindle.ui.components.glow
import io.github.eladimany.spindle.ui.components.rememberDriftPhase
import io.github.eladimany.spindle.ui.components.rememberGlintAngle
import kotlin.math.roundToInt

private val CardShape = RoundedCornerShape(28.dp)

/** Quick requests on the card when on-device AI is ready — one tap makes a playlist. */
private val CardSuggestions = listOf("Late-night focus", "Upbeat and energetic", "Sunday morning")

/**
 * The top of the Playlists tab (mockup screen A). With on-device AI ready: a prompt pill
 * and one-tap ideas. Otherwise: the chip builder, plus the download state if the model
 * can be fetched.
 */
@Composable
fun MakePlaylistCard(
    status: AiEngineStatus,
    onDescribe: () -> Unit,
    onSuggestion: (String) -> Unit,
    onBuild: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val angle by rememberGlintAngle()
    val drift by rememberDriftPhase()
    val aiReady = status == AiEngineStatus.Ready
    Box(modifier.aiGlowBehind({ drift })) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .background(colors.surfaceContainerLow.copy(alpha = 0.88f))
                .edgeGlint({ angle }, cornerRadius = 28.dp, glint = colors.primary, glintTail = colors.tertiary)
                .padding(start = 18.dp, end = 6.dp, top = 12.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.primary.copy(alpha = 0.14f))
                        .border(1.dp, colors.primary.copy(alpha = 0.3f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) { TwinkleSparkle(size = 26.dp) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Make a playlist", style = MaterialTheme.typography.titleLarge, letterSpacing = (-0.3).sp)
                    Text(
                        if (aiReady) "Say the vibe. Spindle picks from your music."
                        else "Pick an era, genres and a length. Spindle picks from your music.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Outlined.Settings, contentDescription = "AI playlist settings", tint = colors.onSurfaceVariant)
                }
            }

            Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (aiReady) {
                    PromptPill(onClick = onDescribe)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CardSuggestions.forEach { idea ->
                            FilterPill(label = idea, selected = false, onClick = { onSuggestion(idea) })
                        }
                    }
                    TextButton(onClick = onBuild, modifier = Modifier.offset(x = (-12).dp)) {
                        Text("Or pick era and genres yourself")
                    }
                } else {
                    Button(
                        onClick = onBuild,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .glow(colors.primary.copy(alpha = 0.35f), radius = 16.dp, cornerRadius = 25.dp),
                        shape = RoundedCornerShape(25.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
                    ) { Text("Build a playlist", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                    when (status) {
                        AiEngineStatus.Downloadable -> TextButton(onClick = onOpenSettings, modifier = Modifier.offset(x = (-12).dp)) {
                            Text("Set up on-device AI to describe playlists in words")
                        }
                        is AiEngineStatus.Downloading -> Text(
                            buildString {
                                append("Getting the on-device AI ready")
                                status.progress?.let { append(" · ${(it * 100).roundToInt()}%") }
                                append("…")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.primary,
                        )
                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun PromptPill(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(26.dp))
            .clickable(onClickLabel = "Describe a playlist", onClick = onClick)
            .padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "mellow 90s for a rainy evening…",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            modifier = Modifier
                .size(40.dp)
                .glow(colors.primary.copy(alpha = 0.5f), radius = 12.dp, cornerRadius = 20.dp)
                .clip(CircleShape)
                .background(colors.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(20.dp))
        }
    }
}

/** The row under the card while an AI request is working, failed, or ready to open. */
@Composable
fun AiRequestRow(
    state: AiRequestState,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val angle by rememberGlintAngle(periodMs = 1_800)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (state is AiRequestState.Ready) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surfaceContainerHigh)
                .then(
                    if (state is AiRequestState.Working) {
                        Modifier.edgeGlint({ angle }, cornerRadius = 16.dp, glint = colors.primary, glintTail = colors.tertiary)
                    } else {
                        Modifier.border(1.dp, colors.primary.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                    },
                ),
            contentAlignment = Alignment.Center,
        ) { TwinkleSparkle(size = 26.dp, animate = state is AiRequestState.Working) }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                when (state) {
                    is AiRequestState.Ready -> state.name
                    else -> "“${state.request}”"
                },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when (state) {
                is AiRequestState.Working -> {
                    Text("Picking tracks… keep listening meanwhile", style = MaterialTheme.typography.bodySmall, color = colors.primary)
                    ShimmerBar()
                }
                is AiRequestState.Failed -> Text(state.message, style = MaterialTheme.typography.bodySmall, color = colors.error)
                is AiRequestState.Ready -> Text("Draft ready · tap to open", style = MaterialTheme.typography.bodySmall, color = colors.primary)
            }
        }
        if (state is AiRequestState.Failed) {
            TextButton(onClick = onRetry) { Text("Retry") }
        }
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.Default.Close,
                contentDescription = if (state is AiRequestState.Working) "Cancel" else "Dismiss",
                tint = colors.onSurfaceVariant,
            )
        }
    }
}

/** An indeterminate bar: a short glowing segment sweeping across. */
@Composable
private fun ShimmerBar() {
    val colors = MaterialTheme.colorScheme
    val sweep by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = -0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_400, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerX",
    )
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White.copy(alpha = 0.08f)),
    ) {
        Box(
            Modifier
                .offset(x = maxWidth * sweep)
                .width(maxWidth * 0.35f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(colors.primary),
        )
    }
}
