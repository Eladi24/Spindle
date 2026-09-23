package io.github.eladimany.spindle.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.core.model.TrackSort
import io.github.eladimany.spindle.ui.components.LocalBottomOverlayPadding
import io.github.eladimany.spindle.ui.components.TrackActionsSheet
import io.github.eladimany.spindle.ui.components.TrackRow
import io.github.eladimany.spindle.ui.components.rememberScrollTapGuard
import kotlinx.coroutines.launch

@Composable
fun TracksScreen(
    onSearchClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: TracksViewModel = hiltViewModel(),
) {
    val tracks = viewModel.tracks.collectAsLazyPagingItems()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val currentTrackId by viewModel.currentTrackId.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val sectionIndex by viewModel.sectionIndex.collectAsStateWithLifecycle()
    var showSortMenu by remember { mutableStateOf(false) }
    var actionsTarget by remember { mutableStateOf<List<Track>?>(null) }
    var scrubLetter by remember { mutableStateOf<Char?>(null) }
    val listState = rememberLazyListState()
    val tapGuard = rememberScrollTapGuard(listState)
    val scope = rememberCoroutineScope()
    // The list has a "Shuffle All" header before the tracks, so a section's position
    // in the title-ordered library is one behind its row index in this LazyColumn.
    val headerOffset = 1

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Tracks") },
                actions = {
                    IconButton(onClick = onSearchClick) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                    }
                    DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Title") },
                            onClick = { viewModel.setSort(TrackSort.TITLE); showSortMenu = false },
                        )
                        DropdownMenuItem(
                            text = { Text("Date added") },
                            onClick = { viewModel.setSort(TrackSort.DATE_ADDED); showSortMenu = false },
                        )
                        DropdownMenuItem(
                            text = { Text("Year") },
                            onClick = { viewModel.setSort(TrackSort.YEAR); showSortMenu = false },
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        if (tracks.itemCount == 0) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(bottom = LocalBottomOverlayPadding.current), contentAlignment = Alignment.Center) {
                Text("No tracks yet", style = MaterialTheme.typography.bodyLarge)
            }
            return@Scaffold
        }

        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = LocalBottomOverlayPadding.current),
            ) {
                item {
                    ShuffleAllRow(onClick = tapGuard.guard(viewModel::shuffleAll))
                }
                items(count = tracks.itemCount, key = tracks.itemKey { it.id }) { index ->
                    val track = tracks[index]
                    if (track != null) {
                        TrackRow(
                            track = track,
                            isCurrent = track.id == currentTrackId,
                            isPlaying = isPlaying && track.id == currentTrackId,
                            onClick = tapGuard.guard { viewModel.playTrack(track) },
                            onLongClick = { actionsTarget = listOf(track) },
                            fetchArtworkUri = viewModel::artworkUriFor,
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxWidth().height(76.dp)) { CircularProgressIndicator() }
                    }
                }
            }

            if (sort == TrackSort.TITLE && sectionIndex.isNotEmpty()) {
                AlphabetIndexBar(
                    sections = sectionIndex,
                    onScrub = { section, active ->
                        if (active) {
                            scrubLetter = section.letter
                            scope.launch {
                                listState.scrollToItem((section.index + headerOffset).coerceAtMost(tracks.itemCount))
                            }
                        } else {
                            scrubLetter = null
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        // Stays clear of the floating bar, or its bottom letters would be
                        // unreachable underneath it.
                        .padding(top = 4.dp, bottom = 4.dp + LocalBottomOverlayPadding.current),
                )
            }

            scrubLetter?.let { letter ->
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.92f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        letter.toString(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }

    actionsTarget?.let { target ->
        TrackActionsSheet(
            tracks = target,
            onAddToQueue = viewModel::addToQueue,
            onDismiss = { actionsTarget = null },
        )
    }
}

// A flat tonal pill with a hairline border, not a filled gradient — a bright gradient
// button here read as "cheap" against the rest of the list (see the UI refresh concepts
// board's revised, darker Tracks mockup).
@Composable
private fun ShuffleAllRow(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Shuffle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Shuffle All",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

/**
 * A-Z (+ "#") fast-scroll rail. A single unified gesture handles both a tap (jumps once)
 * and a drag (scrubs continuously) — [awaitFirstDown] fires on first touch with no slop,
 * then [drag] tracks the same pointer for as long as it's down.
 */
@Composable
private fun AlphabetIndexBar(
    sections: List<SectionAnchor>,
    onScrub: (SectionAnchor, active: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var heightPx by remember { mutableStateOf(0f) }

    fun sectionForY(y: Float): SectionAnchor {
        val fraction = if (heightPx > 0f) (y / heightPx).coerceIn(0f, 1f) else 0f
        val index = (fraction * (sections.size - 1)).toInt().coerceIn(0, sections.lastIndex)
        return sections[index]
    }

    Column(
        modifier = modifier
            .width(24.dp)
            .onGloballyPositioned { heightPx = it.size.height.toFloat() }
            .pointerInput(sections) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    onScrub(sectionForY(down.position.y), true)
                    drag(down.id) { change ->
                        onScrub(sectionForY(change.position.y), true)
                        change.consume()
                    }
                    onScrub(sections.first(), false)
                }
            },
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        sections.forEach { section ->
            Text(
                text = section.letter.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
