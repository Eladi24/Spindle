package io.github.eladimany.spindle.ui.trackinfo

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.eladimany.spindle.ui.components.edgeGlint
import io.github.eladimany.spindle.ui.components.rememberGlintAngle

/** Mockup screen 1: the quiet notice above the Tracks list. Dismissed, it stays away until more appear. */
@Composable
fun TrackInfoNotice(missing: Int, onReview: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val angle by rememberGlintAngle(periodMs = 7_000)
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(shape)
            .background(colors.surfaceContainerLow)
            .edgeGlint({ angle }, cornerRadius = 22.dp, glint = colors.primary, glintTail = colors.tertiary)
            .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.primary.copy(alpha = 0.14f))
                .border(1.dp, colors.primary.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.EditNote, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (missing == 1) "1 track has no artist" else "$missing tracks have no artist",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Spindle can often read it from the title. Review the fixes — your files aren't changed.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            Button(
                onClick = onReview,
                modifier = Modifier.padding(top = 4.dp).height(36.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
            ) { Text("Review", fontWeight = FontWeight.Bold) }
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = colors.onSurfaceVariant)
        }
    }
}
