package io.github.eladimany.spindle.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.eladimany.spindle.core.model.PlaybackState
import io.github.eladimany.spindle.core.model.Track
import kotlin.math.abs
import kotlin.math.sign

/**
 * Swipe left/right anywhere on the bar to skip next/previous — replaces dedicated
 * prev/next buttons entirely, so play/pause can live at the trailing edge, larger.
 */
@Composable
fun MiniPlayerBar(
    playbackState: PlaybackState,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    fetchArtworkUri: suspend (Track) -> String?,
    nextTrack: Track? = null,
    previousTrack: Track? = null,
) {
    val item = when (playbackState) {
        is PlaybackState.Playing -> playbackState.item
        is PlaybackState.Paused -> playbackState.item
        is PlaybackState.Buffering -> playbackState.item
        else -> null
    } ?: return

    val density = LocalDensity.current
    // Distance a swipe needs to travel before it commits — also doubles as the distance
    // over which the title/artist crossfade completes, so the fade finishes exactly when
    // the gesture would trigger, not partway through an arbitrarily longer drag.
    val thresholdPx = with(density) { 35.dp.toPx() }
    val flingVelocityPx = with(density) { 800.dp.toPx() }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    val hasNext = nextTrack != null
    val hasPrevious = previousTrack != null

    val dragState = rememberDraggableState { delta ->
        // Rubber-band resistance instead of a hard stop when there's nothing to skip
        // to in that direction — some give reads as natural, a dead stop reads as broken.
        val allowed = when {
            delta < 0f -> hasNext
            delta > 0f -> hasPrevious
            else -> true
        }
        dragOffsetPx += if (allowed) delta else delta * 0.25f
    }

    val progress = (dragOffsetPx / thresholdPx).coerceIn(-1f, 1f)
    val previewTrack = when {
        dragOffsetPx < 0f -> nextTrack
        dragOffsetPx > 0f -> previousTrack
        else -> null
    }
    val direction = sign(dragOffsetPx)

    Surface(tonalElevation = 4.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenNowPlaying)
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    onDragStopped = { velocity ->
                        val goDirection = when {
                            dragOffsetPx != 0f -> sign(dragOffsetPx)
                            velocity != 0f -> sign(velocity)
                            else -> 0f
                        }
                        val committed = abs(dragOffsetPx) >= thresholdPx || abs(velocity) >= flingVelocityPx
                        val canGo = (goDirection < 0f && hasNext) || (goDirection > 0f && hasPrevious)
                        if (committed && canGo) {
                            // Finish the slide visually before committing — a fast, short
                            // flick can trigger here before the crossfade has caught up.
                            animate(
                                initialValue = dragOffsetPx,
                                targetValue = goDirection * thresholdPx,
                                animationSpec = tween(120),
                            ) { value, _ -> dragOffsetPx = value }
                            if (goDirection < 0f) onNext() else onPrevious()
                            dragOffsetPx = 0f
                        } else {
                            animate(
                                initialValue = dragOffsetPx,
                                targetValue = 0f,
                                initialVelocity = velocity,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMedium,
                                ),
                            ) { value, _ -> dragOffsetPx = value }
                        }
                    },
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrackArtwork(
                track = item.track,
                fetchArtworkUri = fetchArtworkUri,
                modifier = Modifier.size(48.dp),
            )
            Box(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                previewTrack?.let { preview ->
                    Column(
                        modifier = Modifier.graphicsLayer {
                            alpha = abs(progress)
                            translationX = direction * thresholdPx * (1f - abs(progress))
                        },
                    ) {
                        Text(preview.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(preview.artistName, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    }
                }
                Column(
                    modifier = Modifier.graphicsLayer {
                        alpha = 1f - abs(progress)
                        translationX = dragOffsetPx.coerceIn(-thresholdPx, thresholdPx)
                    },
                ) {
                    Text(item.track.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(item.track.artistName, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                }
            }
            IconButton(onClick = onTogglePlayPause, modifier = Modifier.size(56.dp)) {
                val isPlaying = playbackState is PlaybackState.Playing
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(32.dp),
                )
            }
        }
    }
}
