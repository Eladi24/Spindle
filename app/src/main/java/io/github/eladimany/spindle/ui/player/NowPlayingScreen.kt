package io.github.eladimany.spindle.ui.player

import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.eladimany.spindle.core.model.PlaybackState
import io.github.eladimany.spindle.playback.QueueManager
import io.github.eladimany.spindle.ui.components.TrackArtwork
import io.github.eladimany.spindle.ui.playlists.AddToPlaylistSheet
import kotlinx.coroutines.delay

@Composable
fun NowPlayingScreen(
    onOpenQueue: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val queueState by viewModel.queueState.collectAsStateWithLifecycle()
    val volume by viewModel.volume.collectAsStateWithLifecycle()

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
    var showAddToPlaylist by remember { mutableStateOf(false) }

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
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = { showAddToPlaylist = true }) {
                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Add to playlist")
            }
            IconButton(onClick = onOpenQueue) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Queue")
            }
        }

        TrackArtwork(
            track = item.track,
            fetchArtworkUri = viewModel::artworkUriFor,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            cornerRadiusDp = 24,
        )

        Column {
            Text(item.track.title, style = MaterialTheme.typography.titleLarge)
            Text(
                "${item.track.artistName} — ${item.track.albumName}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (queueState.items.isNotEmpty() && queueState.currentIndex >= 0) {
                Text(
                    "Track ${queueState.currentIndex + 1} of ${queueState.items.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column {
            val seekTicker = rememberHapticTicker()
            Slider(
                value = displayPositionMs,
                valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(),
                onValueChange = {
                    isDragging = true
                    displayPositionMs = it
                    seekTicker(it, 0f, durationMs.coerceAtLeast(1).toFloat())
                },
                onValueChangeFinished = {
                    isDragging = false
                    viewModel.seekTo((displayPositionMs / 1000).toInt())
                },
                thumb = { CircleThumb() },
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
            FilledIconButton(
                onClick = viewModel::togglePlayPause,
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
            ) {
                val isPlaying = playbackState is PlaybackState.Playing
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(36.dp),
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

        VolumeRow(volume = volume, onVolumeChange = viewModel::setVolume)
    }

    if (showAddToPlaylist) {
        AddToPlaylistSheet(trackIds = listOf(item.track.id), onDismiss = { showAddToPlaylist = false })
    }
}

@Composable
private fun VolumeRow(volume: Int, onVolumeChange: (Int) -> Unit) {
    var lastNonZeroVolume by remember { mutableIntStateOf(if (volume > 0) volume else 100) }

    // STREAM_MUSIC only has ~15 real steps on most phones, so feeding the rounded
    // system readback straight back into the slider's position made it visibly snap
    // between those steps mid-drag instead of tracking the finger. Same fix as the
    // seek bar above: a local float tracks the drag smoothly; the real (coarse) system
    // volume only overwrites it when the user isn't actively dragging.
    var displayVolume by remember { mutableFloatStateOf(volume.toFloat()) }
    var isDragging by remember { mutableStateOf(false) }
    LaunchedEffect(volume) {
        if (!isDragging) displayVolume = volume.toFloat()
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = {
            if (volume > 0) {
                lastNonZeroVolume = volume
                onVolumeChange(0)
            } else {
                onVolumeChange(lastNonZeroVolume)
            }
        }) {
            Icon(
                if (volume == 0) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = if (volume == 0) "Unmute" else "Mute",
            )
        }
        val volumeTicker = rememberHapticTicker()
        Slider(
            value = displayVolume,
            valueRange = 0f..100f,
            onValueChange = {
                isDragging = true
                displayVolume = it
                onVolumeChange(it.toInt())
                volumeTicker(it, 0f, 100f)
            },
            onValueChangeFinished = { isDragging = false },
            modifier = Modifier.weight(1f),
            thumb = { CircleThumb() },
        )
    }
}

/**
 * A light haptic tick each time a drag crosses one of [bucketCount] evenly-spaced
 * points across the slider's range — a fixed tick count regardless of song length or
 * volume range, rather than one per raw value change (which would buzz constantly).
 *
 * Drives the vibrator directly with an explicit amplitude rather than going through
 * Compose's semantic `HapticFeedbackType.SegmentTick` — confirmed on-device that the
 * OS renders that constant as too weak to feel at all, while an explicit
 * `VibrationEffect` amplitude reliably comes through. Needs `VIBRATE` in the manifest,
 * unlike the permission-free `performHapticFeedback` route.
 */
@Composable
private fun rememberHapticTicker(bucketCount: Int = 30): (Float, Float, Float) -> Unit {
    val context = LocalContext.current
    val vibrator = remember { context.getSystemService(Vibrator::class.java) }
    var lastBucket by remember { mutableIntStateOf(-1) }
    return { value, rangeStart, rangeEnd ->
        val span = (rangeEnd - rangeStart).coerceAtLeast(0.0001f)
        val bucket = (((value - rangeStart) / span).coerceIn(0f, 1f) * bucketCount).toInt()
        if (bucket != lastBucket) {
            lastBucket = bucket
            vibrator?.vibrate(VibrationEffect.createOneShot(15, 130))
        }
    }
}

/** A plain filled circle instead of Material3's default thin vertical-bar thumb —
 * bigger, and reads as something you grab and drag rather than a tick mark. */
@Composable
private fun CircleThumb(size: Dp = 20.dp) {
    Box(modifier = Modifier.size(size).background(MaterialTheme.colorScheme.primary, CircleShape))
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
