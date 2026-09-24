package io.github.eladimany.spindle.ui.playlists

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.eladimany.spindle.core.model.PlaylistEntry
import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.data.history.trackKey
import io.github.eladimany.spindle.ui.components.BoostTag
import io.github.eladimany.spindle.ui.components.CoverMosaic
import io.github.eladimany.spindle.ui.components.DetailHeader
import io.github.eladimany.spindle.ui.components.DetailTopBar
import io.github.eladimany.spindle.ui.components.HeaderArtSize
import io.github.eladimany.spindle.ui.components.LocalBottomOverlayPadding
import io.github.eladimany.spindle.ui.components.SwipeToRemoveOrBoost
import io.github.eladimany.spindle.ui.components.TrackActionsSheet
import io.github.eladimany.spindle.ui.components.TrackArtwork
import io.github.eladimany.spindle.ui.components.countLabel
import io.github.eladimany.spindle.ui.components.durationLabel
import kotlinx.coroutines.launch

@Composable
fun PlaylistDetailScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlaylistDetailViewModel = hiltViewModel(),
) {
    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val currentTrackId by viewModel.currentTrackId.collectAsStateWithLifecycle()
    var showRenameDialog by remember { mutableStateOf(false) }
    var actionsTarget by remember { mutableStateOf<List<Track>?>(null) }

    // Local order for smooth drag visuals — same pattern as QueueScreen; resyncs
    // whenever the real entries change for any other reason (add, remove elsewhere).
    var displayEntries by remember(entries) { mutableStateOf(entries) }
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragDeltaY by remember { mutableFloatStateOf(0f) }
    var rowHeightPx by remember { mutableFloatStateOf(0f) }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val boostedKeys by viewModel.boostedTrackKeys.collectAsStateWithLifecycle()
    fun announce(message: String, onUndo: () -> Unit) {
        snackbarHostState.currentSnackbarData?.dismiss()
        scope.launch {
            if (snackbarHostState.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                onUndo()
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/x-mpegurl"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val content = viewModel.exportM3u()
            context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            snackbarHostState.showSnackbar("Exported ${displayEntries.size} tracks")
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState, modifier = Modifier.padding(bottom = LocalBottomOverlayPadding.current)) },
        topBar = {
            DetailTopBar(title = playlist?.name.orEmpty(), onBack = onBack, listState = listState) {
                IconButton(onClick = {
                    exportLauncher.launch("${playlist?.name ?: "playlist"}.m3u8")
                }) {
                    Icon(Icons.Default.FileDownload, contentDescription = "Export as M3U")
                }
                IconButton(onClick = { showRenameDialog = true }) {
                    Icon(Icons.Default.Edit, contentDescription = "Rename playlist")
                }
            }
        },
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = innerPadding.calculateTopPadding(), bottom = LocalBottomOverlayPadding.current),
        ) {
            item(key = "header") {
                playlist?.let { p ->
                    val tracks = displayEntries.map { it.track }
                    DetailHeader(
                        label = "PLAYLIST",
                        title = p.name,
                        onTitleClick = { showRenameDialog = true },
                        meta = listOfNotNull(
                            countLabel(tracks.size, "track"),
                            tracks.sumOf { it.durationMs }.takeIf { it > 0 }?.let { durationLabel(it) },
                            tracks.map { it.artistId }.distinct().size.takeIf { it > 1 }?.let { countLabel(it, "artist") },
                        ).joinToString(" · "),
                        onPlay = viewModel::playAll,
                        onShuffle = viewModel::playShuffled,
                        onSmartShuffle = viewModel::playSmartShuffled,
                        playEnabled = tracks.isNotEmpty(),
                    ) {
                        CoverMosaic(tracks, viewModel::artworkUriFor, size = HeaderArtSize, cornerRadius = 14.dp)
                    }
                }
            }
            if (displayEntries.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "No tracks yet — long-press a track anywhere in the library and choose this playlist.",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(displayEntries, key = { _, entry -> entry.crossRefId }) { _, entry ->
                val isDragging = entry.crossRefId == draggingId
                Box(
                    modifier = Modifier.animateItem(
                        placementSpec = if (draggingId != null) null else spring(stiffness = Spring.StiffnessMediumLow),
                    ),
                ) {
                    val boosted = trackKey(entry.track) in boostedKeys
                    SwipeToRemoveOrBoost(
                        boosted = boosted,
                        onRemove = {
                            viewModel.removeEntry(entry.crossRefId)
                            displayEntries = displayEntries.filter { it.crossRefId != entry.crossRefId }
                            announce("Removed “${entry.track.title}”") { viewModel.restoreEntry(entry) }
                        },
                        onToggleBoost = {
                            viewModel.toggleBoost(entry.track)
                            announce(if (boosted) "Boost removed" else "Boosted for your next smart shuffle") {
                                viewModel.toggleBoost(entry.track)
                            }
                        },
                    ) { swiping ->
                        PlaylistTrackRow(
                            entry = entry,
                            isCurrent = entry.track.id == currentTrackId,
                            isDragging = isDragging,
                            swiping = swiping,
                            boosted = boosted,
                            dragOffsetY = if (isDragging) dragDeltaY else 0f,
                            onClick = { viewModel.playTrack(entry.track) },
                            onLongClick = { actionsTarget = listOf(entry.track) },
                            fetchArtworkUri = viewModel::artworkUriFor,
                            onDragStart = {
                                draggingId = entry.crossRefId
                                dragDeltaY = 0f
                                rowHeightPx = listState.layoutInfo.visibleItemsInfo
                                    .find { it.key == entry.crossRefId }?.size?.toFloat() ?: 0f
                            },
                            onDrag = { delta ->
                                dragDeltaY += delta
                                if (rowHeightPx <= 0f) return@PlaylistTrackRow
                                while (dragDeltaY > rowHeightPx / 2f) {
                                    val currentIndex = displayEntries.indexOfFirst { it.crossRefId == entry.crossRefId }
                                    if (currentIndex >= displayEntries.lastIndex) break
                                    displayEntries = displayEntries.toMutableList().apply {
                                        add(currentIndex + 1, removeAt(currentIndex))
                                    }
                                    dragDeltaY -= rowHeightPx
                                }
                                while (dragDeltaY < -rowHeightPx / 2f) {
                                    val currentIndex = displayEntries.indexOfFirst { it.crossRefId == entry.crossRefId }
                                    if (currentIndex <= 0) break
                                    displayEntries = displayEntries.toMutableList().apply {
                                        add(currentIndex - 1, removeAt(currentIndex))
                                    }
                                    dragDeltaY += rowHeightPx
                                }
                            },
                            onDragEnd = {
                                if (displayEntries.map { it.crossRefId } != entries.map { it.crossRefId }) {
                                    viewModel.reorder(displayEntries)
                                }
                                draggingId = null
                                dragDeltaY = 0f
                            },
                        )
                    }
                }
            }
        }
    }

    if (showRenameDialog) {
        var name by remember { mutableStateOf(playlist?.name ?: "") }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename playlist") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.rename(name)
                    showRenameDialog = false
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            },
        )
    }

    actionsTarget?.let { target ->
        TrackActionsSheet(
            tracks = target,
            onAddToQueue = viewModel::addToQueue,
            onDismiss = { actionsTarget = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistTrackRow(
    entry: PlaylistEntry,
    isCurrent: Boolean,
    isDragging: Boolean,
    swiping: Boolean,
    boosted: Boolean,
    dragOffsetY: Float,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    fetchArtworkUri: suspend (Track) -> String?,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .graphicsLayer { translationY = dragOffsetY }
            .zIndex(if (isDragging) 1f else 0f)
            .scale(if (isDragging) 1.02f else 1f)
            .shadow(elevation = if (isDragging) 6.dp else 0.dp, shape = RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        // Clear unless lifted, current or mid-swipe, so the header's glow isn't cut off by
        // an opaque row — but a swiped row must hide the action underneath it.
        color = when {
            isDragging -> MaterialTheme.colorScheme.primaryContainer
            isCurrent -> MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp)
            swiping -> MaterialTheme.colorScheme.surface
            else -> Color.Transparent
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(onClick = onClick, onLongClick = onLongClick),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TrackArtwork(
                    track = entry.track,
                    fetchArtworkUri = fetchArtworkUri,
                    modifier = Modifier.size(52.dp),
                )
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            entry.track.title,
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (boosted) BoostTag(Modifier.padding(start = 8.dp))
                    }
                    Text(entry.track.artistName, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .pointerInput(entry.crossRefId) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { onDragStart() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onDrag(dragAmount.y)
                            },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragEnd() },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.DragHandle,
                    contentDescription = "Drag to reorder",
                    tint = if (isDragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
