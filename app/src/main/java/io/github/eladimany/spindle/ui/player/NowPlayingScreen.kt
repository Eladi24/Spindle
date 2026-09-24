package io.github.eladimany.spindle.ui.player

import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.eladimany.spindle.core.model.PlaybackState
import io.github.eladimany.spindle.playback.QueueManager
import io.github.eladimany.spindle.ui.components.TrackArtwork
import io.github.eladimany.spindle.ui.output.OutputPickerSheet
import io.github.eladimany.spindle.ui.output.displayName
import io.github.eladimany.spindle.ui.playlists.AddToPlaylistSheet
import kotlinx.coroutines.delay

// The hero backdrop is intentionally always this dark violet gradient, independent of
// the app's light/dark theme — same idea as Spotify/Apple Music's player screen having
// its own fixed identity rather than following the surrounding UI. Approximates "color
// derived from the album art" with a fixed gradient; real per-track extraction (e.g.
// androidx.palette) is a possible follow-up, not done here — see the UI refresh concepts.
private val HeroBackdrop = Brush.linearGradient(
    colors = listOf(Color(0xFF1B1730), Color(0xFF3A2F7A), Color(0xFF6C5CE0)),
)
private val HeroOnBackdrop = Color.White
private val HeroOnBackdropMuted = Color.White.copy(alpha = 0.7f)
private val HeroOnBackdropFaint = Color.White.copy(alpha = 0.55f)
private val HeroTrackColor = Color.White.copy(alpha = 0.22f)
private val HeroAccent = Color(0xFFC9C4FF)
private val HeroBlobViolet = Color(0xFF8B7CF6)
private val HeroBlobTeal = Color(0xFF2AA7A0)

@Composable
fun NowPlayingScreen(
    onBack: () -> Unit = {},
    onOpenQueue: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val queueState by viewModel.queueState.collectAsStateWithLifecycle()
    val volume by viewModel.volume.collectAsStateWithLifecycle()

    // Above the early return below: switching outputs passes through Idle for a
    // moment, and state declared after the return would reset — closing the picker
    // (and the battery setup step it shows right after a switch to the Node).
    var showOutputPicker by remember { mutableStateOf(false) }
    if (showOutputPicker) {
        OutputPickerSheet(onDismiss = { showOutputPicker = false })
    }

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
    val outputTarget by viewModel.outputTarget.collectAsStateWithLifecycle()

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

    Box(modifier = modifier.fillMaxSize().background(HeroBackdrop)) {
        // Decorative blurred color blobs — purely atmospheric, same shape as the
        // approved mockup's faux-blurred-album-art backdrop.
        Box(
            modifier = Modifier
                .offset(x = 210.dp, y = (-90).dp)
                .size(320.dp)
                .clip(CircleShape)
                .background(HeroBlobViolet.copy(alpha = 0.55f))
                .blur(90.dp),
        )
        Box(
            modifier = Modifier
                .offset(x = (-90).dp, y = 620.dp)
                .size(300.dp)
                .clip(CircleShape)
                .background(HeroBlobTeal.copy(alpha = 0.3f))
                .blur(100.dp),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, colors = heroIconButtonColors()) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = HeroOnBackdrop)
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { showOutputPicker = true }
                        .background(HeroOnBackdrop.copy(alpha = 0.10f))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "PLAYING ON",
                            style = MaterialTheme.typography.labelSmall,
                            color = HeroOnBackdropFaint,
                        )
                        Text(
                            outputTarget.displayName(),
                            style = MaterialTheme.typography.labelLarge,
                            color = HeroOnBackdrop,
                        )
                    }
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = HeroOnBackdropFaint,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Row {
                    IconButton(onClick = { showAddToPlaylist = true }, colors = heroIconButtonColors()) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Add to playlist", tint = HeroOnBackdrop)
                    }
                    IconButton(onClick = onOpenQueue, colors = heroIconButtonColors()) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Queue", tint = HeroOnBackdrop)
                    }
                }
            }

            BoxWithConstraints(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                // fillMaxWidth(fraction) alone sized this off screen WIDTH, which is the
                // long edge in landscape — the artwork blew up past the screen and
                // overlapped the controls (found rotating the screen on-device). Cap it by
                // whichever dimension is actually smaller here instead. Still not enough on
                // its own in a short landscape window — the leftover space after a
                // reasonably-sized art square can be too short for title+artist too, and
                // Column doesn't clip, so that text was rendering *behind* the slider below
                // it rather than actually overlapping visibly. verticalScroll on this block
                // is the actual fix: title/artist scroll into view there instead of ever
                // being silently hidden.
                val artSize = minOf(maxWidth, maxHeight) * 0.6f

                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TrackArtwork(
                        track = item.track,
                        fetchArtworkUri = viewModel::artworkUriFor,
                        modifier = Modifier.size(artSize),
                        cornerRadiusDp = 28,
                    )

                    Column(
                    modifier = Modifier.padding(top = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        item.track.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = HeroOnBackdrop,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Text(
                        item.track.artistName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = HeroOnBackdropMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (queueState.items.isNotEmpty() && queueState.currentIndex >= 0) {
                        Text(
                            "Track ${queueState.currentIndex + 1} of ${queueState.items.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = HeroOnBackdropFaint,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    }
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
                    thumb = { CircleThumb(color = HeroOnBackdrop) },
                    track = {
                        ThinTrack(
                            fraction = displayPositionMs / durationMs.coerceAtLeast(1).toFloat(),
                            activeColor = HeroAccent,
                            inactiveColor = HeroTrackColor,
                        )
                    },
                    colors = heroSliderColors(),
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatDuration(displayPositionMs.toLong()), style = MaterialTheme.typography.labelSmall, color = HeroOnBackdropFaint)
                    Text(formatDuration(durationMs), style = MaterialTheme.typography.labelSmall, color = HeroOnBackdropFaint)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { viewModel.setShuffled(!queueState.isShuffled) }, colors = heroIconButtonColors()) {
                    Icon(
                        Icons.Default.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (queueState.isShuffled) HeroAccent else HeroOnBackdropMuted,
                    )
                }
                IconButton(onClick = viewModel::previous, colors = heroIconButtonColors()) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = HeroOnBackdrop, modifier = Modifier.size(30.dp))
                }
                FilledIconButton(
                    onClick = viewModel::togglePlayPause,
                    modifier = Modifier.size(78.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = HeroOnBackdrop,
                        contentColor = Color(0xFF1B1730),
                    ),
                ) {
                    val isPlaying = playbackState is PlaybackState.Playing
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(34.dp),
                    )
                }
                IconButton(onClick = viewModel::next, colors = heroIconButtonColors()) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = HeroOnBackdrop, modifier = Modifier.size(30.dp))
                }
                IconButton(onClick = viewModel::cycleRepeatMode, colors = heroIconButtonColors()) {
                    val (icon, tint) = repeatIconFor(queueState.repeatMode, active = HeroAccent, inactive = HeroOnBackdropMuted)
                    Icon(icon, contentDescription = "Repeat", tint = tint)
                }
            }

            VolumeRow(volume = volume, onVolumeChange = viewModel::setVolume)
        }
    }

    if (showAddToPlaylist) {
        AddToPlaylistSheet(trackIds = listOf(item.track.id), onDismiss = { showAddToPlaylist = false })
    }

}

@Composable
private fun heroIconButtonColors() = IconButtonDefaults.iconButtonColors(contentColor = HeroOnBackdrop)

@Composable
private fun heroSliderColors() = SliderDefaults.colors(
    activeTrackColor = HeroAccent,
    inactiveTrackColor = HeroTrackColor,
)

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
        IconButton(
            onClick = {
                if (volume > 0) {
                    lastNonZeroVolume = volume
                    onVolumeChange(0)
                } else {
                    onVolumeChange(lastNonZeroVolume)
                }
            },
            colors = heroIconButtonColors(),
        ) {
            Icon(
                if (volume == 0) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = if (volume == 0) "Unmute" else "Mute",
                tint = HeroOnBackdropMuted,
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
            thumb = { CircleThumb(size = 16.dp, color = HeroOnBackdropMuted) },
            track = {
                ThinTrack(
                    fraction = displayVolume / 100f,
                    activeColor = HeroOnBackdropMuted,
                    inactiveColor = HeroTrackColor,
                )
            },
            colors = heroSliderColors(),
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
private fun CircleThumb(size: Dp = 20.dp, color: Color = MaterialTheme.colorScheme.primary) {
    Box(modifier = Modifier.size(size).background(color, CircleShape))
}

/** A thin 4dp pill track instead of Material3's own default — the current M3 Slider's
 * built-in track is a much thicker "expressive" pill (~16dp), which read as too fat
 * against the hero mockup's thin progress/volume bars. */
@Composable
private fun ThinTrack(fraction: Float, activeColor: Color, inactiveColor: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(inactiveColor),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(activeColor),
        )
    }
}

private fun repeatIconFor(mode: QueueManager.RepeatMode, active: Color, inactive: Color): Pair<ImageVector, Color> {
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
