package io.github.eladimany.spindle.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Swipe left to remove, swipe right to boost (or un-boost) for the next smart shuffle.
 * Used by the queue and playlist rows in place of a trash icon. A boost swipe springs
 * back — the row stays; a remove swipe leaves the row to the caller to drop from its list.
 * The caller shows the Undo snackbar for both. [content] gets `swiping = true` while
 * the row is off its resting place — a clear row must go opaque then, or the action
 * underneath shows through it.
 */
@Composable
fun SwipeToRemoveOrBoost(
    boosted: Boolean,
    onRemove: () -> Unit,
    onToggleBoost: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (swiping: Boolean) -> Unit,
) {
    // Plain remember, not rememberSwipeToDismissBoxState: that one is saveable, and a lazy
    // list keeps saved state per key — a row brought back by Undo came back already
    // swiped to "remove" and removed itself again.
    val state = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, positionalThreshold = { it * 0.35f }) }
    val currentOnRemove by rememberUpdatedState(onRemove)
    val currentOnToggleBoost by rememberUpdatedState(onToggleBoost)
    // Not SwipeToDismissBox's onDismiss: its effect is keyed on the lambda too, so every
    // recomposition while settled (toggling the boost causes one) fired it again — the
    // boost flipped back and forth and the row stuck half-open. Once per settle here.
    LaunchedEffect(state.settledValue) {
        when (state.settledValue) {
            SwipeToDismissBoxValue.EndToStart -> currentOnRemove()
            SwipeToDismissBoxValue.StartToEnd -> {
                currentOnToggleBoost()
                state.reset()
            }
            SwipeToDismissBoxValue.Settled -> Unit
        }
    }
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        backgroundContent = { SwipeBackground(state.dismissDirection, boosted) },
    ) { content(state.dismissDirection != SwipeToDismissBoxValue.Settled) }
}

@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue, boosted: Boolean) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    when (direction) {
        SwipeToDismissBoxValue.EndToStart -> Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .clip(shape)
                .background(RemoveBackground)
                .padding(end = 24.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Delete, contentDescription = null, tint = colors.error)
            Spacer(Modifier.width(8.dp))
            Text("Remove", color = colors.error, fontWeight = FontWeight.SemiBold)
        }
        SwipeToDismissBoxValue.StartToEnd -> Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .clip(shape)
                .background(colors.primary.copy(alpha = 0.18f))
                .border(1.dp, colors.primary.copy(alpha = 0.4f), shape)
                .padding(start = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(SparkleIcon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (boosted) "Un-boost" else "Boost", color = colors.primary, fontWeight = FontWeight.Bold)
        }
        SwipeToDismissBoxValue.Settled -> Unit
    }
}

/** Dark, muted red — a bright red fill would clash with the app's dark violet look. */
private val RemoveBackground = Color(0xFF4A1D24)

/** Small "✦ BOOST" tag after a boosted track's title. */
@Composable
fun BoostTag(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(colors.primary.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(SparkleIcon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(10.dp))
        Text("BOOST", color = colors.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)
    }
}
