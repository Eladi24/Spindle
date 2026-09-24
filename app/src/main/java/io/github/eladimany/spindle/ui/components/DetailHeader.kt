package io.github.eladimany.spindle.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.eladimany.spindle.ui.shuffle.SmartShuffleSheet

/**
 * Top bar for detail screens (album, folder, playlist). Clear while the header is on
 * screen — the header's glow shows through it — then fills in and shows [title] once
 * the header has scrolled away. The list must start at the very top of the screen
 * (pass the Scaffold's top padding as contentPadding, not as a modifier) for that.
 */
@Composable
fun DetailTopBar(
    title: String,
    onBack: () -> Unit,
    listState: LazyListState,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val container by animateColorAsState(
        if (scrolled) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent,
        label = "topBarContainer",
    )
    TopAppBar(
        title = {
            AnimatedVisibility(scrolled, enter = fadeIn(), exit = fadeOut()) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = container, scrolledContainerColor = container),
    )
}

/**
 * The shared detail header: art on the left, a small label, a bold title, an accent
 * line (artist) and a meta line, then the glowing play button and smart shuffle —
 * the same shape as the AI draft screen's header.
 */
@Composable
fun DetailHeader(
    label: String,
    title: String,
    meta: String,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onSmartShuffle: () -> Unit,
    modifier: Modifier = Modifier,
    accent: String? = null,
    onTitleClick: (() -> Unit)? = null,
    playEnabled: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {},
    art: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().headerGlow()) {
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.size(HeaderArtSize)) { art() }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    color = colors.primary,
                )
                Text(
                    title,
                    modifier = if (onTitleClick != null) Modifier.clickable(onClickLabel = "Rename", onClick = onTitleClick) else Modifier,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.8).sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                accent?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(meta, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlowPlayButton(onClick = onPlay, enabled = playEnabled)
            ShufflePair(onShuffle = onShuffle, onSmartShuffle = onSmartShuffle, enabled = playEnabled)
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}

val HeaderArtSize = 148.dp

/** 60dp filled play button with a soft violet halo. */
@Composable
fun GlowPlayButton(onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(60.dp)
            .glow(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f), radius = 20.dp, cornerRadius = 30.dp),
        shape = CircleShape,
    ) { Icon(Icons.Default.PlayArrow, contentDescription = "Play", modifier = Modifier.size(30.dp)) }
}

/**
 * "Shuffle | ✦ Smart" in one glass pill, beside the play button (which plays in
 * order). Each half starts playback; long-press Smart for the smart shuffle rules,
 * as on the Tracks tab.
 */
@Composable
fun ShufflePair(
    onShuffle: () -> Unit,
    onSmartShuffle: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    var showRules by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .height(48.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .clip(CircleShape)
                .combinedClickable(enabled = enabled, role = Role.Button, onClick = onShuffle)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Shuffle", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .clip(CircleShape)
                .background(colors.primary.copy(alpha = 0.14f))
                .border(1.dp, colors.primary.copy(alpha = 0.35f), CircleShape)
                .combinedClickable(
                    enabled = enabled,
                    role = Role.Button,
                    onClickLabel = "Smart shuffle",
                    onLongClickLabel = "Smart shuffle settings",
                    onLongClick = { showRules = true },
                    onClick = onSmartShuffle,
                )
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(SparkleIcon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(14.dp))
            Text("Smart", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
    if (showRules) SmartShuffleSheet(onDismiss = { showRules = false })
}

/** Header art for a folder: a tonal tile with the folder glyph. */
@Composable
fun FolderTile(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .size(HeaderArtSize)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
    }
}

/**
 * One soft violet glow behind the header's art. It spills upward under the clear top
 * bar, which is why detail lists start at the top of the screen.
 */
private fun Modifier.headerGlow(): Modifier = drawBehind {
    val r = 240.dp.toPx()
    val c = Offset(20.dp.toPx() + HeaderArtSize.toPx() / 2, HeaderArtSize.toPx() / 2)
    drawCircle(Brush.radialGradient(listOf(GlowViolet.copy(alpha = 0.38f), Color.Transparent), c, r), r, c)
}
