package io.github.eladimany.spindle.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.eladimany.spindle.core.model.PlaybackState
import io.github.eladimany.spindle.playback.QueueManager
import io.github.eladimany.spindle.ui.components.TrackArtwork
import kotlinx.coroutines.delay

@Composable
fun NowPlayingScreen(
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val queueState by viewModel.queueState.collectAsStateWithLifecycle()

    val item = when (val s = playbackState) {
        is PlaybackState.Playing -> s.item
        is PlaybackState.Paused -> s.item
        is PlaybackState.Buffering -> s.item
        else -> null
    } ?: return

    val durationMs = when (val s = playbackState) {
        is PlaybackState.Playing -> s.durationMs
        is PlaybackState.Paused -> s.durationMs
        else -> 0L
    }

    var displayPositionMs by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    LaunchedEffect(playbackState) {
        val playing = playbackState as? PlaybackState.Playing
        if (playing != null) {
            while (true) {
                if (!isDragging) {
                    displayPositionMs = (playing.positionMs + (System.currentTimeMillis() - playing.capturedAtMs))
                        .coerceIn(0, playing.durationMs.coerceAtLeast(0))
                        .toFloat()
                }
                delay(200)
            }
        } else {
            val paused = playbackState as? PlaybackState.Paused
            if (paused != null) displayPositionMs = paused.positionMs.toFloat()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TrackArtwork(
            track = item.track,
            fetchArtworkUri = viewModel::artworkUriFor,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            cornerRadiusDp = 16,
        )

        Column {
            Text(item.track.title, style = MaterialTheme.typography.titleLarge)
            Text(
                "${item.track.artistName} — ${item.track.albumName}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Column {
            Slider(
                value = displayPositionMs,
                valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(),
                onValueChange = {
                    isDragging = true
                    displayPositionMs = it
                },
                onValueChangeFinished = {
                    isDragging = false
                    viewModel.seekTo((displayPositionMs / 1000).toInt())
                },
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration(displayPositionMs.toLong()), style = MaterialTheme.typography.labelSmall)
                Text(formatDuration(durationMs), style = MaterialTheme.typography.labelSmall)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { viewModel.setShuffled(!queueState.isShuffled) }) {
                Icon(
                    Icons.Default.Shuffle,
                    contentDescription = "Shuffle",
                    tint = if (queueState.isShuffled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = viewModel::previous) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous")
            }
            IconButton(onClick = viewModel::togglePlayPause) {
                val isPlaying = playbackState is PlaybackState.Playing
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.padding(4.dp),
                )
            }
            IconButton(onClick = viewModel::next) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next")
            }
            IconButton(onClick = viewModel::cycleRepeatMode) {
                val (icon, tint) = repeatIconFor(queueState.repeatMode)
                Icon(icon, contentDescription = "Repeat", tint = tint)
            }
        }
    }
}

@Composable
private fun repeatIconFor(mode: QueueManager.RepeatMode): Pair<ImageVector, androidx.compose.ui.graphics.Color> {
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.onSurface
    return when (mode) {
        QueueManager.RepeatMode.OFF -> Icons.Default.Repeat to inactive
        QueueManager.RepeatMode.ALL -> Icons.Default.Repeat to active
        QueueManager.RepeatMode.ONE -> Icons.Default.RepeatOne to active
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
