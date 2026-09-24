package io.github.eladimany.spindle.ui.smartplaylists

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.eladimany.spindle.ui.components.TwinkleSparkle
import io.github.eladimany.spindle.ui.components.aiGlowBehind
import io.github.eladimany.spindle.ui.components.edgeGlint
import io.github.eladimany.spindle.ui.components.glow
import io.github.eladimany.spindle.ui.components.rememberDriftPhase
import io.github.eladimany.spindle.ui.components.rememberGlintAngle

private val CardShape = RoundedCornerShape(28.dp)

/**
 * The top of the Playlists tab (mockup screen A). Without on-device AI it opens the
 * chip builder; the free-text prompt joins it once an AI engine exists.
 */
@Composable
fun MakePlaylistCard(onBuild: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val angle by rememberGlintAngle()
    val drift by rememberDriftPhase()
    Box(modifier.aiGlowBehind({ drift })) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .background(colors.surfaceContainerLow.copy(alpha = 0.88f))
                .edgeGlint({ angle }, cornerRadius = 28.dp, glint = colors.primary, glintTail = colors.tertiary)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
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
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Make a playlist",
                        style = MaterialTheme.typography.titleLarge,
                        letterSpacing = (-0.3).sp,
                    )
                    Text(
                        "Pick an era, genres and a length. Spindle picks from your music.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            Button(
                onClick = onBuild,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .glow(colors.primary.copy(alpha = 0.35f), radius = 16.dp, cornerRadius = 25.dp),
                shape = RoundedCornerShape(25.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
            ) { Text("Build a playlist", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        }
    }
}
