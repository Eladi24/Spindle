package io.github.eladimany.spindle.ui.library

import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.eladimany.spindle.core.model.Album
import io.github.eladimany.spindle.ui.components.AlbumArtwork
import io.github.eladimany.spindle.ui.components.SparkleIcon
import io.github.eladimany.spindle.ui.components.countLabel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/*
 * Flipping through albums (mockups 7A/7B, 2026-11-02): a record crate upright, a cover
 * flow sideways (the crate wants height, the row of covers width). Both are one continuous position over the album list — whole numbers
 * are albums — that a drag moves and a fling settles on the nearest album, with a light
 * tick each time an album passes the centre. Hand-placed rather than a pager: the covers
 * overlap and turn, which a pager's side-by-side layout doesn't do.
 */

private val FlowAccent = Color(0xFFC3BFFF)
private val FlowSmart = Color(0xFFFF8AD8)
private const val VISIBLE_EACH_SIDE = 5

/** The position, the gesture that moves it, and the tick. [itemPx] = finger travel per album. */
@Composable
private fun rememberFlip(albums: List<Album>, startAlbumId: Long?, onCentred: (Long) -> Unit): Animatable<Float, AnimationVector1D> {
    val count = albums.size
    // Back from an album (or after turning the phone) the flip opens where it was, not at
    // the first album (user report, A73).
    val position = remember { Animatable(albums.indexOfFirst { it.id == startAlbumId }.coerceAtLeast(0).toFloat()) }
    val latestAlbums by rememberUpdatedState(albums)
    LaunchedEffect(position) {
        snapshotFlow { position.value.roundToInt() }.collect { index ->
            latestAlbums.getOrNull(index)?.let { onCentred(it.id) }
        }
    }
    val context = LocalContext.current
    val vibrator = remember { context.getSystemService(Vibrator::class.java) }
    LaunchedEffect(position) {
        snapshotFlow { position.value.roundToInt() }.drop(1).collect {
            vibrator?.vibrate(VibrationEffect.createOneShot(8, 70))
        }
    }
    // The list can shrink under the position (a rescan).
    LaunchedEffect(count) {
        if (count > 0 && position.value > count - 1) position.snapTo((count - 1).toFloat())
    }
    return position
}

@Composable
private fun Modifier.flipGesture(
    position: Animatable<Float, AnimationVector1D>,
    count: Int,
    itemPx: Float,
    orientation: Orientation,
): Modifier {
    val scope = rememberCoroutineScope()
    val maxIndex = (count - 1).coerceAtLeast(0).toFloat()
    return draggable(
        orientation = orientation,
        state = rememberDraggableState { delta ->
            scope.launch { position.snapTo((position.value - delta / itemPx).coerceIn(-0.4f, maxIndex + 0.4f)) }
        },
        onDragStopped = { velocity ->
            // About a quarter second of travel at the fling's speed, then the nearest album.
            val target = (position.value - velocity / itemPx * 0.25f).roundToInt().coerceIn(0, maxIndex.toInt())
            position.animateTo(
                target.toFloat(),
                spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
                initialVelocity = -velocity / itemPx,
            )
        },
    )
}

/** Sideways (7A): covers in a row, the centre one facing you, the rest turned toward it. */
@Composable
fun AlbumCoverFlow(
    albums: List<Album>,
    fetchArtworkUri: suspend (Album) -> String?,
    onOpenAlbum: (Long) -> Unit,
    onPlay: (Album, AlbumsViewModel.PlayMode) -> Unit,
    bottomPadding: Dp,
    startAlbumId: Long?,
    onCentred: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (albums.isEmpty()) return
    val position = rememberFlip(albums, startAlbumId, onCentred)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // Sideways there's width but little height: covers on the left get the full height,
    // the name and buttons sit beside them (stacked under them, the buttons ended up
    // behind the mini-player on the A73).
    Row(modifier = modifier.fillMaxSize().padding(bottom = bottomPadding), verticalAlignment = Alignment.CenterVertically) {
        BoxWithConstraints(modifier = Modifier.weight(2f).fillMaxSize()) {
            val cover = minOf(maxHeight - 8.dp, maxWidth * 0.46f, 330.dp).coerceAtLeast(80.dp)
            val coverPx = with(density) { cover.toPx() }
            val centre = position.value.roundToInt().coerceIn(albums.indices)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .flipGesture(position, albums.size, coverPx * 0.45f, Orientation.Horizontal),
                contentAlignment = Alignment.Center,
            ) {
                // A soft floor shadow under the centre cover; no glossy reflection.
                Box(
                    Modifier
                        .graphicsLayer { translationY = coverPx / 2 + 4.dp.toPx() }
                        .size(cover * 0.9f, 22.dp)
                        .background(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)), CircleShape),
                )
                val first = (centre - VISIBLE_EACH_SIDE).coerceAtLeast(0)
                val last = (centre + VISIBLE_EACH_SIDE).coerceAtMost(albums.lastIndex)
                for (index in first..last) {
                    val album = albums[index]
                    key(album.id) {
                        val d = index - position.value
                        val near = min(abs(d), 1f)
                        AlbumArtwork(
                            album = album,
                            fetchArtworkUri = fetchArtworkUri,
                            cornerRadiusDp = 10,
                            modifier = Modifier
                                .size(cover)
                                .zIndex(-abs(d))
                                .graphicsLayer {
                                    val a = abs(d)
                                    val spread = if (a < 1f) a * 0.62f else 0.62f + (a - 1f) * 0.22f
                                    translationX = sign(d) * spread * coverPx
                                    rotationY = -sign(d) * near * 58f
                                    cameraDistance = 12f * this.density
                                    val scale = 1f - 0.1f * near
                                    scaleX = scale
                                    scaleY = scale
                                    alpha = if (a > VISIBLE_EACH_SIDE - 0.5f) 0f else 1f
                                }
                                .drawWithContent {
                                    drawContent()
                                    drawRoundRect(Color.Black.copy(alpha = 0.42f * near), cornerRadius = CornerRadius(10.dp.toPx()))
                                }
                                .clickable(role = Role.Button, onClickLabel = album.name) {
                                    if (index == centre) onOpenAlbum(album.id)
                                    else scope.launch { position.animateTo(index.toFloat(), spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)) }
                                },
                        )
                    }
                }
            }
        }
        val current = albums[position.value.roundToInt().coerceIn(albums.indices)]
        AlbumFlowInfo(current, onPlay, Modifier.weight(0.8f).padding(end = 20.dp), alignStart = true)
    }
}

/** Upright (7B): sleeves standing in a crate; flick up or down to flip, the chosen one pulled up with its record peeking out. */
@Composable
fun AlbumRecordCrate(
    albums: List<Album>,
    fetchArtworkUri: suspend (Album) -> String?,
    onOpenAlbum: (Long) -> Unit,
    onPlay: (Album, AlbumsViewModel.PlayMode) -> Unit,
    bottomPadding: Dp,
    startAlbumId: Long?,
    onCentred: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (albums.isEmpty()) return
    val position = rememberFlip(albums, startAlbumId, onCentred)
    val density = LocalDensity.current
    Column(
        modifier = modifier.fillMaxSize().padding(bottom = bottomPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // The records in front fade out at the crate's bottom edge instead of under the info.
                .clipToBounds()
                .flipGesture(position, albums.size, with(density) { 60.dp.toPx() }, Orientation.Vertical),
        ) {
            val sleeve = minOf(maxWidth * 0.78f, maxHeight * 0.66f, 320.dp)
            val sleevePx = with(density) { sleeve.toPx() }
            val centre = position.value.roundToInt().coerceIn(albums.indices)
            Box(
                // Lifted so the tops of the records in front show below the chosen one.
                modifier = Modifier.fillMaxSize().padding(bottom = sleeve * 0.32f),
                contentAlignment = Alignment.Center,
            ) {
                val first = (centre - VISIBLE_EACH_SIDE).coerceAtLeast(0)
                val last = (centre + VISIBLE_EACH_SIDE).coerceAtMost(albums.lastIndex)
                for (index in first..last) {
                    val album = albums[index]
                    key(album.id) {
                        val d = index - position.value
                        val near = min(abs(d), 1f)
                        val picked = 1f - near
                        // The record slides out of the chosen sleeve, to the right.
                        if (picked > 0.05f) {
                            Box(
                                Modifier
                                    .size(sleeve * 0.96f)
                                    .zIndex(-abs(d) - 0.5f)
                                    .graphicsLayer {
                                        translationX = sleevePx * 0.36f * picked
                                        translationY = -sleevePx * 0.06f
                                        rotationZ = 30f * picked
                                        alpha = picked
                                    }
                                    .background(Color(0xFF0B0B0D), CircleShape)
                                    .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape)
                                    .drawWithContent {
                                        drawContent()
                                        val r = size.minDimension / 2
                                        for (groove in listOf(0.92f, 0.8f, 0.68f, 0.56f)) {
                                            drawCircle(Color.White.copy(alpha = 0.06f), radius = r * groove, style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
                                        }
                                        drawCircle(FlowAccent.copy(alpha = 0.85f), radius = r * 0.27f)
                                        drawCircle(Color(0xFF0B0B0D), radius = r * 0.03f)
                                    },
                            )
                        }
                        AlbumArtwork(
                            album = album,
                            fetchArtworkUri = fetchArtworkUri,
                            cornerRadiusDp = 6,
                            modifier = Modifier
                                .size(sleeve)
                                // Records in front sit over the chosen one; those behind, under it.
                                .zIndex(if (d > 0) d else -abs(d))
                                .graphicsLayer {
                                    val a = abs(d)
                                    // Behind stay close above the chosen sleeve; in front drop lower.
                                    val far = when {
                                        d < 0 -> if (a < 1f) a * 0.32f else 0.32f + (a - 1f) * 0.06f
                                        else -> if (a < 1f) a * 0.5f else 0.5f + (a - 1f) * 0.08f
                                    }
                                    translationY = sign(d) * far * sleevePx
                                    translationX = -sleevePx * 0.12f * picked
                                    // All lean back, read as strips; leaning the ones in front toward the
                                    // viewer magnified them into a distorted slab (A73).
                                    rotationX = 62f * near
                                    val scale = 1f - 0.06f * near
                                    scaleX = scale
                                    scaleY = scale
                                    rotationZ = -3f * picked
                                    cameraDistance = 10f * this.density
                                    alpha = if (a > VISIBLE_EACH_SIDE - 0.5f) 0f else 1f
                                }
                                .drawWithContent {
                                    drawContent()
                                    drawRoundRect(Color.Black.copy(alpha = 0.35f * near), cornerRadius = CornerRadius(6.dp.toPx()))
                                }
                                .clickable(role = Role.Button, onClickLabel = album.name) {
                                    if (index == centre) onOpenAlbum(album.id)
                                },
                        )
                    }
                }
            }
        }
        val current = albums[position.value.roundToInt().coerceIn(albums.indices)]
        AlbumFlowInfo(current, onPlay, Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    }
}

/** The album under the centre: name, artist · year · songs, and Play / Shuffle / ✦ Smart. */
@Composable
private fun AlbumFlowInfo(
    album: Album,
    onPlay: (Album, AlbumsViewModel.PlayMode) -> Unit,
    modifier: Modifier,
    alignStart: Boolean = false,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = if (alignStart) Alignment.Start else Alignment.CenterHorizontally,
    ) {
        Text(
            album.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (alignStart) TextAlign.Start else TextAlign.Center,
        )
        Text(
            listOfNotNull(album.artistName, album.year?.toString(), countLabel(album.trackCount, "song")).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowPill("Play", Icons.Default.PlayArrow, filled = true, tint = FlowAccent) { onPlay(album, AlbumsViewModel.PlayMode.IN_ORDER) }
            FlowPill("Shuffle", Icons.Default.Shuffle, filled = false, tint = Color.White) { onPlay(album, AlbumsViewModel.PlayMode.SHUFFLE) }
            FlowPill("Smart", SparkleIcon, filled = false, tint = FlowSmart) { onPlay(album, AlbumsViewModel.PlayMode.SMART) }
        }
    }
}

@Composable
private fun FlowPill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    filled: Boolean,
    tint: Color,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(17.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(if (filled) tint else tint.copy(alpha = 0.08f))
            .border(1.dp, tint.copy(alpha = if (filled) 0f else 0.4f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 34.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        val content = if (filled) Color(0xFF16112A) else tint
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = content)
    }
}

/** "Grid | Flow" in the Albums title bar. */
@Composable
fun AlbumsViewToggle(flow: Boolean, onChange: (Boolean) -> Unit) {
    val outer = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .clip(outer)
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), outer)
            .padding(3.dp),
    ) {
        for ((label, isFlow) in listOf("Grid" to false, "Flow" to true)) {
            val selected = flow == isFlow
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(15.dp))
                    .background(if (selected) FlowAccent else Color.Transparent)
                    .clickable(role = Role.Tab, onClickLabel = label) { onChange(isFlow) }
                    .heightIn(min = 32.dp)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) Color(0xFF16112A) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
