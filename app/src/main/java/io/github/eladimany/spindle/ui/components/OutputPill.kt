package io.github.eladimany.spindle.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Where music plays, for the output pill on every tab ([LargeTitleBar]). */
data class OutputChipState(val name: String, val isNode: Boolean, val onClick: () -> Unit)

/**
 * Provided by AppNavHost, so each tab's title bar can show where music plays and open
 * the Play on sheet without a song loaded. Null: no pill (e.g. in previews).
 */
val LocalOutputChip = staticCompositionLocalOf<OutputChipState?> { null }

/**
 * Where it's playing — tap to switch, same as the "Playing on" chip on Now Playing.
 * [large] is the title-bar version: a little bigger, with ▾, and a glow while a Node plays.
 */
@Composable
fun OutputPill(name: String, isNode: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, large: Boolean = false) {
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(if (large) 17.dp else 15.dp)
    Row(
        modifier = modifier
            .widthIn(max = if (large) 180.dp else 118.dp)
            .height(if (large) 34.dp else 30.dp)
            .then(if (large && isNode) Modifier.glow(primary.copy(alpha = 0.35f), radius = 10.dp, cornerRadius = 17.dp) else Modifier)
            .clip(shape)
            .background(primary.copy(alpha = if (large && isNode) 0.18f else 0.12f))
            .border(1.dp, primary.copy(alpha = 0.35f), shape)
            .clickable(onClickLabel = "Change output", onClick = onClick)
            .semantics { contentDescription = "Playing on $name" }
            .padding(start = 10.dp, end = if (large) 8.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            if (isNode) Icons.Default.Speaker else Icons.Default.PhoneAndroid,
            contentDescription = null,
            tint = primary,
            modifier = Modifier.size(if (large) 16.dp else 14.dp),
        )
        Text(
            name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (large) Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = primary, modifier = Modifier.size(16.dp))
    }
}
