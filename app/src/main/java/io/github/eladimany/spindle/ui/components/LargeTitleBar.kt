package io.github.eladimany.spindle.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat

/**
 * The top of every tab: a 34sp bold title, a count line under it, and round glass
 * action buttons. Replaces the stock TopAppBar so all tabs share one style.
 */
@Composable
fun LargeTitleBar(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 34.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-1).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Always takes its line, so the bar doesn't grow when the count arrives.
            Text(
                subtitle.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** Round translucent button with a hairline edge — the glass look used across the app. */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 44.dp,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(size)
            .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape),
        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.White.copy(alpha = 0.06f)),
        content = content,
    )
}

/** "1,284 tracks", "1 album". */
fun countLabel(count: Int, singular: String, plural: String = singular + "s"): String =
    "${NumberFormat.getIntegerInstance().format(count)} ${if (count == 1) singular else plural}"

/** "86 h" for a library, "1 h 14 min" / "38 min" for an album or playlist. */
fun durationLabel(totalMs: Long, hoursOnly: Boolean = false): String {
    val totalMin = totalMs / 60_000
    return when {
        totalMin < 60 -> "$totalMin min"
        hoursOnly -> "${NumberFormat.getIntegerInstance().format(totalMin / 60)} h"
        totalMin % 60 == 0L -> "${totalMin / 60} h"
        else -> "${totalMin / 60} h ${totalMin % 60} min"
    }
}
