package io.github.eladimany.spindle.ui.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.eladimany.spindle.core.model.QueueItem
import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.ui.components.TrackArtwork

/**
 * Shows the live play queue with drag-to-reorder, remove, and jump-to-track.
 * Takes the shared [PlayerViewModel] instance (same one as the mini-player and
 * Now Playing) rather than its own — there's only ever one active queue.
 */
@Composable
fun QueueScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel,
) {
    val queueState by viewModel.queueState.collectAsStateWithLifecycle()

    // Local order for smooth drag visuals; resyncs whenever the real queue
    // changes for any other reason (auto-advance, a move landing, etc.).
    var displayItems by remember(queueState.items) { mutableStateOf(queueState.items) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragDeltaY by remember { mutableFloatStateOf(0f) }
    var itemHeightPx by remember { mutableFloatStateOf(0f) }

    val currentItemId = queueState.items.getOrNull(queueState.currentIndex)?.id

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Queue") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        if (displayItems.isEmpty()) {
            Text(
                "Queue is empty",
                modifier = Modifier.padding(innerPadding).padding(16.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            return@Scaffold
        }

        LazyColumn(
            // Deliberately NOT toggling userScrollEnabled based on drag state: that's
            // the same class of bug as the animateItem() modifier-chain-identity issue
            // below, just one level up — flipping it mid-gesture changes the LazyColumn's
            // own modifier chain while a descendant's pointerInput is active, which can
            // tear down and cancel that gesture partway through a longer drag.
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
        ) {
            itemsIndexed(displayItems, key = { _, item -> item.id }) { _, item ->
                val isDragging = item.id == draggingId
                Column(
                    // Always call animateItem() — never toggle between it and a plain
                    // Modifier. Swapping modifier *chains* (rather than just a param)
                    // on an ancestor mid-gesture tears down and recreates the child's
                    // pointerInput node, which cancels the drag the instant it starts.
                    // Disabling just the placement animation via a null spec keeps the
                    // modifier chain's identity stable instead.
                    modifier = Modifier.animateItem(
                        placementSpec = if (isDragging) null else spring(stiffness = Spring.StiffnessMediumLow),
                    ),
                ) {
                    QueueRow(
                        item = item,
                        isCurrent = item.id == currentItemId,
                        isDragging = isDragging,
                        dragOffsetY = if (isDragging) dragDeltaY else 0f,
                        onClick = { viewModel.jumpTo(item.id) },
                        onRemove = {
                            viewModel.removeFromQueue(item.id)
                            displayItems = displayItems.filter { it.id != item.id }
                        },
                        fetchArtworkUri = viewModel::artworkUriFor,
                        onMeasuredHeight = { itemHeightPx = it },
                        onDragStart = {
                            draggingId = item.id
                            dragDeltaY = 0f
                        },
                        onDrag = { delta ->
                            dragDeltaY += delta
                            if (itemHeightPx > 0f) {
                                // Half-item hysteresis, and a loop rather than a single
                                // step, so a fast flick across several rows in one
                                // callback still lands correctly instead of stalling.
                                while (dragDeltaY > itemHeightPx / 2f) {
                                    val currentIndex = displayItems.indexOfFirst { it.id == item.id }
                                    if (currentIndex >= displayItems.lastIndex) break
                                    displayItems = displayItems.toMutableList().apply {
                                        add(currentIndex + 1, removeAt(currentIndex))
                                    }
                                    dragDeltaY -= itemHeightPx
                                }
                                while (dragDeltaY < -itemHeightPx / 2f) {
                                    val currentIndex = displayItems.indexOfFirst { it.id == item.id }
                                    if (currentIndex <= 0) break
                                    displayItems = displayItems.toMutableList().apply {
                                        add(currentIndex - 1, removeAt(currentIndex))
                                    }
                                    dragDeltaY += itemHeightPx
                                }
                            }
                        },
                        onDragEnd = {
                            val originalIndex = queueState.items.indexOfFirst { it.id == item.id }
                            val finalIndex = displayItems.indexOfFirst { it.id == item.id }
                            if (originalIndex >= 0 && finalIndex >= 0 && originalIndex != finalIndex) {
                                viewModel.moveInQueue(originalIndex, finalIndex)
                            }
                            draggingId = null
                            dragDeltaY = 0f
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun QueueRow(
    item: QueueItem,
    isCurrent: Boolean,
    isDragging: Boolean,
    dragOffsetY: Float,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    fetchArtworkUri: suspend (Track) -> String?,
    onMeasuredHeight: (Float) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { onMeasuredHeight(it.size.height.toFloat()) }
            .graphicsLayer { translationY = dragOffsetY }
            .zIndex(if (isDragging) 1f else 0f)
            // Lifted look while actively dragging — a real elevation shadow plus a
            // slight scale-up, so grabbing a row is unmistakable even mid-gesture,
            // not just a color tweak that's easy to miss out of the corner of an eye.
            .scale(if (isDragging) 1.02f else 1f)
            .shadow(elevation = if (isDragging) 6.dp else 0.dp)
            .background(
                when {
                    isDragging -> MaterialTheme.colorScheme.primaryContainer
                    isCurrent -> MaterialTheme.colorScheme.surfaceVariant
                    else -> MaterialTheme.colorScheme.background
                },
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Only this part is clickable (jump to track) — the ripple stays confined
        // to here instead of covering the delete/drag icons too.
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrackArtwork(
                track = item.track,
                fetchArtworkUri = fetchArtworkUri,
                modifier = Modifier.size(44.dp),
            )
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    item.track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(item.track.artistName, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Delete, contentDescription = "Remove from queue")
        }
        // 48dp is Material's minimum touch target — the icon itself is only 24dp,
        // too small to reliably long-press-and-drag with a real finger.
        Box(
            modifier = Modifier
                .size(48.dp)
                .pointerInput(item.id) {
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
