package io.github.eladimany.spindle.ui.player

import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.abs
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.eladimany.spindle.R
import io.github.eladimany.spindle.core.model.PlaybackState
import io.github.eladimany.spindle.data.history.trackKey
import io.github.eladimany.spindle.playback.QueueManager
import io.github.eladimany.spindle.playback.QueueState
import io.github.eladimany.spindle.ui.components.SparkleIcon
import io.github.eladimany.spindle.ui.components.TrackArtwork
import io.github.eladimany.spindle.ui.components.countLabel
import io.github.eladimany.spindle.ui.components.durationLabel
import io.github.eladimany.spindle.ui.components.glow
import io.github.eladimany.spindle.ui.output.OutputPickerSheet
import io.github.eladimany.spindle.ui.output.displayName
import io.github.eladimany.spindle.ui.playlists.AddToPlaylistSheet
import io.github.eladimany.spindle.ui.shuffle.SmartShuffleSheet
import kotlinx.coroutines.delay

// "Ambient light" (mockup 5C, 2026-11-02): near-black, lit by two soft glows in the
// cover's own colours (CoverPalette), whatever the app's light/dark theme — the player
// keeps its own identity, like Spotify/Apple Music's. Violet/teal while a cover loads
// or when there's none.
private val AmbientBase = Color(0xFF09080E)
private val HeroOnBackdrop = Color.White
private val HeroOnBackdropMuted = Color.White.copy(alpha = 0.7f)
private val HeroOnBackdropFaint = Color.White.copy(alpha = 0.55f)
private val HeroTrackColor = Color.White.copy(alpha = 0.22f)
private val HeroAccent = Color(0xFFC9C4FF)
private val HeroBlobViolet = Color(0xFF8B7CF6)
private val HeroBlobTeal = Color(0xFF2AA7A0)

// Shuffle/repeat (mockup 4C): off is a dim icon; on is lavender with a glow and a dot
// under it; smart shuffle is pink with a sparkle. Fixed colours, not the cover's, so
// "on" always looks the same.
private val ModeOff = Color.White.copy(alpha = 0.42f)
private val ModeOn = Color(0xFFC3BFFF)
private val ModeSmart = Color(0xFFFF8AD8)

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
    val boostedKeys by viewModel.boostedTrackKeys.collectAsStateWithLifecycle()
    // One line under the pill after a tap: what a boost does.
    var boostHint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(boostHint) {
        if (boostHint != null) {
            delay(2500)
            boostHint = null
        }
    }

    // Above the early return below: switching outputs passes through Idle for a
    // moment, and state declared after the return would reset — closing the picker
    // (and the battery setup step it shows right after a switch to the Node).
    var showOutputPicker by remember { mutableStateOf(false) }
    if (showOutputPicker) {
        OutputPickerSheet(onDismiss = { showOutputPicker = false })
    }
    // Each tap on the shuffle button names the new mode for a moment — the icon
    // alone can't tell "shuffle" from "smart" at a glance.
    var shuffleLabel by remember { mutableStateOf<String?>(null) }
    var shuffleLabelTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(shuffleLabelTick) {
        if (shuffleLabel != null) {
            delay(1500)
            shuffleLabel = null
        }
    }
    var showSmartShuffleSheet by remember { mutableStateOf(false) }
    if (showSmartShuffleSheet) {
        SmartShuffleSheet(onDismiss = { showSmartShuffleSheet = false })
    }

    val item = when (val s = playbackState) {
        is PlaybackState.Playing -> s.item
        is PlaybackState.Paused -> s.item
        is PlaybackState.Buffering -> s.item
        else -> null
    }
    if (item == null) {
        // The queue played out: it's kept, and play starts it again (see PlaybackController.finishQueue).
        if (queueState.finished && queueState.items.isNotEmpty()) {
            FinishedNowPlaying(
                queueState = queueState,
                onCollapse = onBack,
                onOpenQueue = onOpenQueue,
                onPlayAgain = viewModel::togglePlayPause,
                modifier = modifier,
            )
        }
        return
    }

    val durationMs = when (val s = playbackState) {
        is PlaybackState.Playing -> s.durationMs
        is PlaybackState.Paused -> s.durationMs
        else -> 0L
    }.takeIf { it > 0 } ?: item.track.durationMs

    var displayPositionMs by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var showAddToPlaylist by remember { mutableStateOf(false) }
    val outputTarget by viewModel.outputTarget.collectAsStateWithLifecycle()
    val localOutputName by viewModel.localOutputName.collectAsStateWithLifecycle()

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
    // A new song starts the bar at 0, not where the last song was, while it loads.
    LaunchedEffect(item.id) {
        if (!isDragging) displayPositionMs = 0f
    }

    val palette = rememberCoverPalette(item.track, viewModel::artworkUriFor)
    val glowPrimary by animateColorAsState(palette?.let { Color(it.primary) } ?: HeroBlobViolet, tween(600), label = "glowPrimary")
    val glowSecondary by animateColorAsState(palette?.let { Color(it.secondary) } ?: HeroBlobTeal, tween(600), label = "glowSecondary")
    val accent by animateColorAsState(palette?.let { Color(it.accent) } ?: HeroAccent, tween(600), label = "accent")

    // 6B: the queue as a row of covers — swipe to the next/previous song.
    val items = queueState.items
    val pagerState = rememberPagerState(initialPage = queueState.currentIndex.coerceAtLeast(0)) { items.size }
    val context = LocalContext.current
    val vibrator = remember { context.getSystemService(Vibrator::class.java) }
    val latestQueue by rememberUpdatedState(queueState)
    var userSwiped by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState) {
        pagerState.interactionSource.interactions.collect { if (it is DragInteraction.Start) userSwiped = true }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val queue = latestQueue
            if (!userSwiped || page == queue.currentIndex || page !in queue.items.indices) return@collect
            userSwiped = false
            vibrator?.vibrate(VibrationEffect.createOneShot(25, 170))
            // Swiping back plays that song from its start — not "previous", which restarts
            // the current song past 5 s.
            if (page == queue.currentIndex + 1) viewModel.next() else viewModel.jumpTo(queue.items[page].id)
        }
    }
    // The song changed some other way (buttons, lock screen, end of track): follow it.
    LaunchedEffect(queueState.currentIndex, items.size) {
        val target = queueState.currentIndex
        if (target !in items.indices || pagerState.isScrollInProgress || pagerState.currentPage == target) return@LaunchedEffect
        if (abs(target - pagerState.currentPage) > 3) pagerState.scrollToPage(target) else pagerState.animateScrollToPage(target)
    }

    Box(modifier = modifier.fillMaxSize()) {
        AmbientBackdrop(glowPrimary, glowSecondary)

        Column(
            modifier = Modifier
                .fillMaxSize()
                // Drawn edge to edge in the player sheet, under the system bars.
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, colors = heroIconButtonColors()) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Close player", tint = HeroOnBackdrop)
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { showOutputPicker = true }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "PLAYING ON",
                            style = MaterialTheme.typography.labelSmall,
                            letterSpacing = 1.2.sp,
                            color = HeroOnBackdropFaint,
                        )
                        Text(
                            outputTarget.displayName(localOutputName),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
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
                // Sized off the smaller dimension (landscape: the width is the long edge), and
                // scrollable so title/artist never hide behind the slider in a short window.
                val artSize = minOf(maxWidth, maxHeight) * 0.62f
                // The covers run to the screen edges, past this column's 24 dp padding.
                val rowWidth = maxWidth + 48.dp

                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    HorizontalPager(
                        state = pagerState,
                        // Room above and below for the cover's glow: the pager clips its content,
                        // and a tight fit cut the glow off in a straight line (A73).
                        modifier = Modifier.requiredWidth(rowWidth).height(artSize + 56.dp),
                        pageSize = PageSize.Fixed(artSize),
                        contentPadding = PaddingValues(horizontal = (rowWidth - artSize) / 2, vertical = 28.dp),
                        pageSpacing = 18.dp,
                        beyondViewportPageCount = 1,
                    ) { page ->
                        val track = items.getOrNull(page)?.track ?: return@HorizontalPager
                        TrackArtwork(
                            track = track,
                            fetchArtworkUri = viewModel::artworkUriFor,
                            modifier = Modifier
                                .size(artSize)
                                .graphicsLayer {
                                    val away = pagerState.awayFrom(page)
                                    val scale = 1f - 0.18f * away
                                    scaleX = scale
                                    scaleY = scale
                                    alpha = 1f - 0.55f * away
                                    // The glow belongs to the centre cover only: a separate
                                    // shadow modifier on a faded cover left a hard edge (A73).
                                    shadowElevation = 36.dp.toPx() * (1f - away)
                                    shape = RoundedCornerShape(18.dp)
                                    ambientShadowColor = glowPrimary
                                    spotShadowColor = glowPrimary
                                },
                            cornerRadiusDp = 18,
                        )
                    }

                    // The title follows the cover under the finger, fading through the swipe.
                    val shownTrack = items.getOrNull(pagerState.currentPage)?.track ?: item.track
                    Column(
                        modifier = Modifier
                            .graphicsLayer { alpha = 1f - (abs(pagerState.currentPageOffsetFraction) * 1.6f).coerceAtMost(0.75f) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            shownTrack.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = HeroOnBackdrop,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        Text(
                            shownTrack.artistName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = HeroOnBackdropMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val boosted = trackKey(item.track) in boostedKeys
                    BoostPill(
                        boosted = boosted,
                        accent = accent,
                        onClick = {
                            viewModel.toggleBoost(item.track)
                            boostHint = if (boosted) "Boost removed" else "Comes up sooner in your next smart shuffle"
                        },
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Text(
                        boostHint.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 4.dp),
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
                    thumb = { CircleThumb(size = 14.dp, color = HeroOnBackdrop) },
                    track = {
                        ThinTrack(
                            fraction = displayPositionMs / durationMs.coerceAtLeast(1).toFloat(),
                            activeColor = accent,
                            inactiveColor = HeroTrackColor,
                            glow = true,
                        )
                    },
                    colors = heroSliderColors(),
                )
                Box(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatDuration(displayPositionMs.toLong()), style = MaterialTheme.typography.labelSmall, color = HeroOnBackdropFaint)
                        Text(formatDuration(durationMs), style = MaterialTheme.typography.labelSmall, color = HeroOnBackdropFaint)
                    }
                    // The shuffle mode's name, briefly, in the empty middle of the time
                    // row — overlaid (matchParentSize + unbounded) so nothing shifts.
                    Box(modifier = Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = shuffleLabel != null,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            modifier = Modifier.wrapContentSize(unbounded = true),
                        ) {
                            ShuffleModeLabel(text = shuffleLabel.orEmpty(), smart = queueState.shuffleMode == QueueManager.ShuffleMode.SMART)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShuffleModeButton(
                    mode = queueState.shuffleMode,
                    onClick = {
                        shuffleLabel = when (viewModel.cycleShuffleMode()) {
                            QueueManager.ShuffleMode.OFF -> "Shuffle off"
                            QueueManager.ShuffleMode.SHUFFLE -> "Shuffle"
                            QueueManager.ShuffleMode.SMART -> "Smart shuffle"
                        }
                        shuffleLabelTick++
                    },
                    onLongPress = { showSmartShuffleSheet = true },
                )
                IconButton(onClick = viewModel::previous, colors = heroIconButtonColors()) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = HeroOnBackdrop, modifier = Modifier.size(32.dp))
                }
                // A glass circle lit by the cover, not a solid disc (mockup 5C).
                FilledIconButton(
                    onClick = viewModel::togglePlayPause,
                    modifier = Modifier
                        .size(78.dp)
                        .glow(glowPrimary.copy(alpha = 0.5f), radius = 26.dp, cornerRadius = 39.dp)
                        .border(1.dp, HeroOnBackdrop.copy(alpha = 0.22f), CircleShape),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color(0xFF1A1724),
                        contentColor = HeroOnBackdrop,
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
                    Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = HeroOnBackdrop, modifier = Modifier.size(32.dp))
                }
                RepeatModeButton(mode = queueState.repeatMode, onClick = viewModel::cycleRepeatMode)
            }

            VolumeRow(volume = volume, onVolumeChange = viewModel::setVolume)
        }
    }

    if (showAddToPlaylist) {
        AddToPlaylistSheet(trackIds = listOf(item.track.id), onDismiss = { showAddToPlaylist = false })
    }

}

/** Off → Shuffle → Smart. A plain IconButton has no long-press, and long-press opens the Smart shuffle sheet. */
@Composable
private fun ShuffleModeButton(
    mode: QueueManager.ShuffleMode,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val description = when (mode) {
        QueueManager.ShuffleMode.OFF -> "Shuffle: off"
        QueueManager.ShuffleMode.SHUFFLE -> "Shuffle: on"
        QueueManager.ShuffleMode.SMART -> "Shuffle: smart"
    }
    ModeGlyph(
        icon = Icons.Default.Shuffle,
        color = when (mode) {
            QueueManager.ShuffleMode.OFF -> null
            QueueManager.ShuffleMode.SHUFFLE -> ModeOn
            QueueManager.ShuffleMode.SMART -> ModeSmart
        },
        sparkle = mode == QueueManager.ShuffleMode.SMART,
        modifier = Modifier
            .combinedClickable(
                role = Role.Button,
                onClickLabel = "Change shuffle mode",
                onLongClickLabel = "Smart shuffle settings",
                onLongClick = onLongPress,
                onClick = onClick,
            )
            .semantics { contentDescription = description },
    )
}

/** Off → All → One, drawn like [ShuffleModeButton]. */
@Composable
private fun RepeatModeButton(mode: QueueManager.RepeatMode, onClick: () -> Unit) {
    val description = when (mode) {
        QueueManager.RepeatMode.OFF -> "Repeat: off"
        QueueManager.RepeatMode.ALL -> "Repeat: all"
        QueueManager.RepeatMode.ONE -> "Repeat: one"
    }
    ModeGlyph(
        icon = if (mode == QueueManager.RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
        color = if (mode == QueueManager.RepeatMode.OFF) null else ModeOn,
        sparkle = false,
        modifier = Modifier
            .clickable(role = Role.Button, onClickLabel = "Change repeat mode", onClick = onClick)
            .semantics { contentDescription = description },
    )
}

/** [color] null = off. On: the icon in [color] over a soft halo, and a glowing dot under it. */
@Composable
private fun ModeGlyph(icon: ImageVector, color: Color?, sparkle: Boolean, modifier: Modifier = Modifier) {
    val tint by animateColorAsState(color ?: ModeOff, tween(160), label = "modeTint")
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .then(modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (color != null) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(Brush.radialGradient(listOf(color.copy(alpha = 0.30f), Color.Transparent)), CircleShape),
            )
        }
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        if (sparkle) {
            Icon(
                SparkleIcon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 9.dp, end = 9.dp)
                    .size(10.dp),
            )
        }
        if (color != null) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 4.dp)
                    .size(6.dp)
                    .glow(color, radius = 6.dp, cornerRadius = 3.dp)
                    .background(color, CircleShape),
            )
        }
    }
}

/** Near-black with two soft glows in the cover's colours, drawn with radial gradients (no blur API needed). */
@Composable
private fun AmbientBackdrop(primary: Color, secondary: Color) {
    Box(
        Modifier
            .fillMaxSize()
            .background(AmbientBase)
            .drawBehind {
                val w = size.width
                drawCircle(
                    Brush.radialGradient(
                        listOf(primary.copy(alpha = 0.55f), primary.copy(alpha = 0.18f), Color.Transparent),
                        center = Offset(w * 0.5f, w * 0.62f),
                        radius = w * 0.85f,
                    ),
                    radius = w * 0.85f,
                    center = Offset(w * 0.5f, w * 0.62f),
                )
                drawCircle(
                    Brush.radialGradient(
                        listOf(secondary.copy(alpha = 0.32f), Color.Transparent),
                        center = Offset(w * 0.78f, w * 0.30f),
                        radius = w * 0.55f,
                    ),
                    radius = w * 0.55f,
                    center = Offset(w * 0.78f, w * 0.30f),
                )
            },
    )
}

/** How far [page] is from the centre of the row, 0 (centred) to 1 (a neighbour or further). */
private fun PagerState.awayFrom(page: Int): Float =
    abs((currentPage - page) + currentPageOffsetFraction).coerceIn(0f, 1f)

/** BOOST under the artist; BOOSTED (filled, glowing) once tapped — for the next smart shuffle. */
@Composable
private fun BoostPill(boosted: Boolean, accent: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .then(if (boosted) Modifier.glow(accent.copy(alpha = 0.45f), radius = 12.dp, cornerRadius = 16.dp) else Modifier)
            .clip(shape)
            .background(if (boosted) accent.copy(alpha = 0.22f) else accent.copy(alpha = 0.06f))
            .border(1.dp, accent.copy(alpha = if (boosted) 0.9f else 0.5f), shape)
            .clickable(
                role = Role.Switch,
                onClickLabel = if (boosted) "Remove boost" else "Boost",
                onClick = onClick,
            )
            .semantics { contentDescription = if (boosted) "Boosted for your next smart shuffle" else "Boost this song" }
            .heightIn(min = 32.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(SparkleIcon, contentDescription = null, tint = if (boosted) HeroOnBackdrop else accent, modifier = Modifier.size(13.dp))
        Text(
            if (boosted) "BOOSTED" else "BOOST",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
            color = if (boosted) HeroOnBackdrop else accent,
        )
    }
}

@Composable
private fun ShuffleModeLabel(text: String, smart: Boolean) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .glow((if (smart) ModeSmart else ModeOn).copy(alpha = 0.25f), radius = 14.dp, cornerRadius = 14.dp)
            .clip(shape)
            .background(Color(0xD9141228))
            .border(1.dp, (if (smart) ModeSmart else ModeOn).copy(alpha = 0.35f), shape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (smart) Icon(SparkleIcon, contentDescription = null, tint = ModeSmart, modifier = Modifier.size(12.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = HeroOnBackdrop)
    }
}

@Composable
private fun heroIconButtonColors() = IconButtonDefaults.iconButtonColors(contentColor = HeroOnBackdrop)

@Composable
private fun heroSliderColors() = SliderDefaults.colors(
    activeTrackColor = ModeOn,
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
private fun ThinTrack(fraction: Float, activeColor: Color, inactiveColor: Color, glow: Boolean = false) {
    // Not clipped as a whole, so the played part's glow can spill past the line.
    Box(modifier = Modifier.fillMaxWidth().height(4.dp)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(2.dp))
                .background(inactiveColor),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .then(if (glow) Modifier.glow(activeColor.copy(alpha = 0.8f), radius = 8.dp, cornerRadius = 2.dp) else Modifier)
                .clip(RoundedCornerShape(2.dp))
                .background(activeColor),
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/**
 * After the last track: the queue is still there, so the player shows "Queue finished"
 * with one big button that plays it again — reshuffled first if shuffle was on.
 */
@Composable
private fun FinishedNowPlaying(
    queueState: QueueState,
    onCollapse: () -> Unit,
    onOpenQueue: () -> Unit,
    onPlayAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val totalMs = queueState.items.sumOf { it.track.durationMs }
    Box(modifier = modifier.fillMaxSize()) {
        AmbientBackdrop(HeroBlobViolet, HeroBlobTeal)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCollapse, colors = heroIconButtonColors()) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Close player", tint = HeroOnBackdrop)
                }
                IconButton(onClick = onOpenQueue, colors = heroIconButtonColors()) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Queue", tint = HeroOnBackdrop)
                }
            }
            Spacer(Modifier.weight(1f))
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.78f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(28.dp))
                    .background(HeroOnBackdrop.copy(alpha = 0.06f))
                    .border(1.dp, HeroOnBackdrop.copy(alpha = 0.14f), RoundedCornerShape(28.dp)),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    painterResource(R.drawable.ic_spindle_signal),
                    contentDescription = null,
                    tint = HeroOnBackdropMuted,
                    modifier = Modifier.size(112.dp),
                )
                Text(
                    listOf(countLabel(queueState.items.size, "track"), durationLabel(totalMs)).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = HeroOnBackdropFaint,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            Text(
                "Queue finished",
                style = MaterialTheme.typography.headlineSmall,
                color = HeroOnBackdrop,
                modifier = Modifier.padding(top = 28.dp),
            )
            Text(
                "Play it again from the top",
                style = MaterialTheme.typography.bodyLarge,
                color = HeroOnBackdropMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
            val (label, icon) = when (queueState.shuffleMode) {
                QueueManager.ShuffleMode.OFF -> "Play again" to Icons.Filled.PlayArrow
                QueueManager.ShuffleMode.SHUFFLE -> "Shuffle again" to Icons.Filled.Shuffle
                QueueManager.ShuffleMode.SMART -> "Smart shuffle again" to SparkleIcon
            }
            Button(
                onClick = onPlayAgain,
                modifier = Modifier.padding(top = 28.dp).height(56.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = HeroOnBackdrop, contentColor = Color(0xFF3A2F7A)),
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1.2f))
        }
    }
}
