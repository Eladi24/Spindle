package io.github.eladimany.spindle.ui.smartplaylists

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.eladimany.spindle.data.smartplaylists.PlaylistLength
import io.github.eladimany.spindle.ui.components.glow

/** For `rememberSaveable`: a [PlaylistLength] survives rotation as two plain values. */
val PlaylistLengthSaver = Saver<PlaylistLength, List<Int>>(
    save = { listOf(if (it.byTime) 1 else 0, it.amount) },
    restore = { PlaylistLength(byTime = it[0] == 1, amount = it[1]) },
)

/**
 * LENGTH on the describe and builder sheets (mockup 7A): Tracks | Time, the value in
 * large type with an estimate in the other unit, and a slider over
 * [PlaylistLength.steps]. Switching units keeps about the same length.
 */
@Composable
internal fun LengthPicker(length: PlaylistLength, onChange: (PlaylistLength) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val steps = PlaylistLength.steps(length.byTime)
    val index = steps.indexOf(length.amount).coerceAtLeast(0)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "LENGTH",
                style = MaterialTheme.typography.labelMedium,
                letterSpacing = 0.6.sp,
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            UnitToggle(byTime = length.byTime, onSelect = { byTime -> if (byTime != length.byTime) onChange(length.switched()) })
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (length.byTime) PlaylistLength.minutesLabel(length.amount) else "${length.amount} songs",
                fontSize = 34.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-1).sp,
            )
            Text(
                if (length.byTime) "about ${length.amount / PlaylistLength.TYPICAL_TRACK_MINUTES} songs"
                else "about ${PlaylistLength.minutesLabel(length.amount * PlaylistLength.TYPICAL_TRACK_MINUTES)}",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Slider(
            value = index.toFloat(),
            onValueChange = { onChange(length.copy(amount = steps[it.toInt().coerceIn(0, steps.lastIndex)])) },
            valueRange = 0f..steps.lastIndex.toFloat(),
            steps = steps.size - 2,
            modifier = Modifier.semantics { stateDescription = length.label },
            thumb = {
                Box(
                    Modifier
                        .size(24.dp)
                        .background(Color.White, CircleShape),
                )
            },
            track = {
                val fraction = index.toFloat() / steps.lastIndex
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(colors.primary.copy(alpha = 0.16f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .glow(colors.primary.copy(alpha = 0.6f), radius = 6.dp, cornerRadius = 3.dp)
                            .background(colors.primary),
                    )
                }
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val marks = if (length.byTime) listOf("15 min", "1 h", "2 h", "3 h", "4 h") else listOf("10", "25", "50", "75", "100")
            marks.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant) }
        }
    }
}

@Composable
private fun UnitToggle(byTime: Boolean, onSelect: (byTime: Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = 0.3f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .padding(3.dp)
            .semantics { contentDescription = "Measure length in" },
    ) {
        listOf(false to "Tracks", true to "Time").forEach { (value, label) ->
            val selected = value == byTime
            Box(
                modifier = Modifier
                    .height(34.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(if (selected) colors.primary else Color.Transparent)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(value) })
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) colors.onPrimary else colors.onSurfaceVariant,
                )
            }
        }
    }
}
