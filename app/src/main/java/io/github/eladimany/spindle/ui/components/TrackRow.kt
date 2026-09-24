package io.github.eladimany.spindle.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.eladimany.spindle.core.model.Track

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    track: Track,
    isCurrent: Boolean,
    onClick: () -> Unit,
    fetchArtworkUri: suspend (Track) -> String?,
    modifier: Modifier = Modifier,
    // Long-press is the standard mobile affordance for a secondary "act on this row"
    // action — used for "add to playlist" wherever TrackRow appears, but TrackRow
    // itself stays decoupled from what that action actually does.
    onLongClick: () -> Unit = {},
    // Only meaningful when isCurrent — drives the equalizer glyph between its animated
    // (playing) and static (paused-but-loaded) look. Screens that don't wire real
    // playback state here just get the static glyph on the current row, never a stale
    // animation.
    isPlaying: Boolean = false,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(16.dp)),
        // Tonal elevation instead of a flat gray swap — the current-track row
        // reads as a raised card rather than a same-shape-different-color panel.
        // Other rows are clear, so a detail header's glow isn't cut off by an opaque row.
        color = if (isCurrent) MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp) else Color.Transparent,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrackArtwork(
                track = track,
                fetchArtworkUri = fetchArtworkUri,
                modifier = Modifier.size(56.dp),
            )
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Text(track.artistName, style = MaterialTheme.typography.bodySmall)
            }
            if (isCurrent) {
                EqualizerGlyph(
                    isPlaying = isPlaying,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/**
 * A 3-bar equalizer glyph — animated while [isPlaying], frozen at rest heights otherwise
 * (still visible: "this is the loaded track", just not implying live playback). Each bar
 * runs its own infinite tween out of phase with the others (via [startDelay]) so they
 * don't move in lockstep, which would read as a single pulsing block instead of the
 * usual "dancing bars" look.
 */
@Composable
private fun EqualizerGlyph(isPlaying: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.height(16.dp).width(16.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        EqualizerBar(isPlaying = isPlaying, durationMs = 480, startDelayMs = 0)
        EqualizerBar(isPlaying = isPlaying, durationMs = 620, startDelayMs = 120)
        EqualizerBar(isPlaying = isPlaying, durationMs = 540, startDelayMs = 260)
    }
}

@Composable
private fun EqualizerBar(isPlaying: Boolean, durationMs: Int, startDelayMs: Int) {
    val heightFraction: Float
    if (isPlaying) {
        val transition = rememberInfiniteTransition(label = "equalizer")
        val animated by transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMs, delayMillis = startDelayMs, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "equalizerBar",
        )
        heightFraction = animated
    } else {
        heightFraction = 0.4f
    }
    Surface(
        modifier = Modifier
            .width(3.dp)
            .height((16 * heightFraction).dp),
        shape = RoundedCornerShape(1.dp),
        color = MaterialTheme.colorScheme.primary,
        content = {},
    )
}
