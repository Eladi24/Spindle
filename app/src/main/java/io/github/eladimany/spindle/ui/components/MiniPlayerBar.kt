package io.github.eladimany.spindle.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.eladimany.spindle.R
import io.github.eladimany.spindle.core.model.PlaybackState
import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.core.model.previousRestartsTrack
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.delay

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
    outputName: String = "This phone",
    outputIsNode: Boolean = false,
    onOpenOutputPicker: () -> Unit = {},
    finished: Boolean = false,
    finishedSubtitle: String = "",
    modifier: Modifier = Modifier,
) {
    val item = when (playbackState) {
        is PlaybackState.Playing -> playbackState.item
        is PlaybackState.Paused -> playbackState.item
        is PlaybackState.Buffering -> playbackState.item
        else -> null
    }
    if (item == null) {
        if (finished) FinishedMiniPlayer(finishedSubtitle, onTogglePlayPause, onOpenNowPlaying, modifier)
        return
    }
    val trackProgress by rememberPlaybackProgress(playbackState)

    val density = LocalDensity.current
    // Distance a swipe needs to travel before it commits — also doubles as the distance
    // over which the title/artist crossfade completes, so the fade finishes exactly when
    // the gesture would trigger, not partway through an arbitrarily longer drag.
    val thresholdPx = with(density) { 35.dp.toPx() }
    val flingVelocityPx = with(density) { 800.dp.toPx() }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    val hasNext = nextTrack != null
    // Past 5 s, "previous" restarts this track (PlaybackController.previous), so a swipe
    // back previews the same song and works even on the first track.
    val restartsOnBack = playbackState.previousRestartsTrack()
    val hasPrevious = previousTrack != null || restartsOnBack

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
        dragOffsetPx > 0f -> if (restartsOnBack) item.track else previousTrack
        else -> null
    }
    val direction = sign(dragOffsetPx)

    // Transparent: the container (shape, blur, edge) comes from the caller's modifier —
    // AppNavHost floats this as a frosted-glass card over the content.
    Surface(
        modifier = modifier,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box {
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
                    .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
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
                OutputPill(name = outputName, isNode = outputIsNode, onClick = onOpenOutputPicker)
                IconButton(onClick = onTogglePlayPause, modifier = Modifier.size(56.dp)) {
                    val isPlaying = playbackState is PlaybackState.Playing
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
            ProgressLine(trackProgress, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** Where it's playing — tap to switch, same as the "Playing on" chip on Now Playing. */
@Composable
private fun OutputPill(name: String, isNode: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(15.dp)
    Row(
        modifier = Modifier
            .widthIn(max = 118.dp)
            .height(30.dp)
            .clip(shape)
            .background(primary.copy(alpha = 0.12f))
            .border(1.dp, primary.copy(alpha = 0.35f), shape)
            .clickable(onClickLabel = "Change output", onClick = onClick)
            .semantics { contentDescription = "Playing on $name" }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            if (isNode) Icons.Default.Speaker else Icons.Default.PhoneAndroid,
            contentDescription = null,
            tint = primary,
            modifier = Modifier.size(14.dp),
        )
        Text(
            name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The thin line along the bottom edge: how far into the song, with a soft glow on the played part. */
@Composable
private fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .height(2.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(Color.White.copy(alpha = 0.14f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .glow(MaterialTheme.colorScheme.primary.copy(alpha = 0.8f), radius = 4.dp, cornerRadius = 1.dp)
                .background(Color.White),
        )
    }
}

/**
 * Played fraction of the current track. The output only reports a position when its
 * state changes, so this interpolates from the last snapshot twice a second, like the
 * seek bar on Now Playing.
 */
@Composable
private fun rememberPlaybackProgress(state: PlaybackState): State<Float> =
    produceState(0f, state) {
        when (state) {
            is PlaybackState.Playing -> while (true) {
                val position = state.positionMs + (System.currentTimeMillis() - state.capturedAtMs)
                value = if (state.durationMs > 0) position.toFloat() / state.durationMs else 0f
                delay(500)
            }
            is PlaybackState.Paused -> value = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
            else -> Unit
        }
    }

/** After the last track: "Queue finished" and a play button that starts it again. */
@Composable
private fun FinishedMiniPlayer(
    subtitle: String,
    onPlay: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    modifier: Modifier,
) {
    Surface(modifier = modifier, color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenNowPlaying)
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_spindle_signal), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("Queue finished", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onPlay, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play again", modifier = Modifier.size(32.dp))
            }
        }
    }
}
