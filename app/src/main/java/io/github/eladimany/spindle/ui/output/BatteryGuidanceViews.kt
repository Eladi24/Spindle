package io.github.eladimany.spindle.ui.output

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.eladimany.spindle.ui.components.glow

// A caution tone the Material scheme doesn't have — amber, not error red: nothing
// is broken yet, it just might stop later. Darker in light theme for contrast.
private data class CautionColors(val accent: Color, val text: Color)

@Composable
private fun cautionColors(): CautionColors =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        CautionColors(accent = Color(0xFFF2C57C), text = Color(0xFFF4E3C4))
    } else {
        CautionColors(accent = Color(0xFF8A5A00), text = Color(0xFF4A3200))
    }

/** Concept A: under the active Node in Play on, until battery use is Unrestricted. */
@Composable
fun BatteryWarningCard(
    onOpenSettings: () -> Unit,
    onNotNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val caution = cautionColors()
    val shape = MaterialTheme.shapes.large
    Column(
        modifier = modifier
            .fillMaxWidth()
            .glow(caution.accent.copy(alpha = 0.10f), radius = 18.dp, cornerRadius = 16.dp)
            .clip(shape)
            .background(caution.accent.copy(alpha = 0.06f))
            .border(1.dp, caution.accent.copy(alpha = 0.28f), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.BatteryAlert, contentDescription = null, tint = caution.accent, modifier = Modifier.size(20.dp))
            Text(
                "Keep playing with the screen off",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = caution.text,
            )
        }
        Text(
            buildAnnotatedString {
                append("Samsung can put Spindle to sleep in the background, and the Node stops with it. Set Spindle's battery use to ")
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)) {
                    append("Unrestricted")
                }
                append(".")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onOpenSettings,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, caution.accent.copy(alpha = 0.35f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = caution.accent.copy(alpha = 0.14f),
                    contentColor = caution.text,
                ),
            ) { Text("Open battery settings", fontWeight = FontWeight.SemiBold) }
            TextButton(onClick = onNotNow) {
                Text("Not now", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Concept B: shown once, in place of the picker, right after the first switch to a Node. */
@Composable
fun BatterySetupContent(
    nodeName: String,
    onOpenSettings: () -> Unit,
    onLater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Phone ── glowing link ── Node
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EndpointBubble(active = false) {
                Icon(Icons.Default.Smartphone, contentDescription = null, tint = colors.onSurfaceVariant)
            }
            Box(
                modifier = Modifier
                    .padding(horizontal = 14.dp)
                    .width(70.dp)
                    .height(1.dp)
                    .glow(colors.primary.copy(alpha = 0.9f), radius = 8.dp, cornerRadius = 0.dp)
                    .background(colors.primary),
            )
            EndpointBubble(active = true) {
                Icon(Icons.Default.Cast, contentDescription = null, tint = colors.onPrimaryContainer)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Text(
                "Now playing on $nodeName",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Your phone is the music server. One Samsung setting keeps it awake when the screen turns off.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(colors.surfaceContainer)
                .border(1.dp, Color.White.copy(alpha = 0.06f), MaterialTheme.shapes.large)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SetupStep(1) { StepText("Tap ", "Open settings", " below") }
            SetupStep(2) { StepText("Choose ", "Battery") }
            SetupStep(3) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StepText("Select ", "Unrestricted")
                    // A small picture of what they'll see on the next screen.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surfaceContainerLowest)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        RadioPreview("Unrestricted", selected = true)
                        RadioPreview("Optimized", selected = false)
                        RadioPreview("Restricted", selected = false)
                    }
                }
            }
        }

        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .glow(colors.primary.copy(alpha = 0.18f), radius = 14.dp, cornerRadius = 24.dp),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.45f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = colors.primary.copy(alpha = 0.14f),
                    contentColor = colors.onPrimaryContainer.takeIf { colors.surface.luminance() < 0.5f } ?: colors.primary,
                ),
            ) { Text("Open settings", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 4.dp)) }
            TextButton(onClick = onLater, modifier = Modifier.fillMaxWidth()) {
                Text("Later", color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EndpointBubble(active: Boolean, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (active) colors.primaryContainer else colors.surfaceContainerHigh)
            .border(
                1.dp,
                if (active) colors.primary.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.08f),
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun SetupStep(number: Int, content: @Composable () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .border(1.dp, primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = primary)
        }
        Box(modifier = Modifier.padding(top = 2.dp)) { content() }
    }
}

@Composable
private fun StepText(before: String, bold: String, after: String = "") {
    Text(
        buildAnnotatedString {
            append(before)
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold) }
            append(after)
        },
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun RadioPreview(label: String, selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .border(2.dp, if (selected) colors.primary else colors.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(6.dp).clip(CircleShape).background(colors.primary))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) colors.onSurface else colors.outline,
        )
    }
}
