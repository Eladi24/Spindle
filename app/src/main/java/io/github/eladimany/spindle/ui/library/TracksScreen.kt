package io.github.eladimany.spindle.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.core.model.TrackSort
import io.github.eladimany.spindle.ui.components.AlphabetIndexBar
import io.github.eladimany.spindle.ui.components.GlassIconButton
import io.github.eladimany.spindle.ui.components.GlowPlayButton
import io.github.eladimany.spindle.ui.components.LargeTitleBar
import io.github.eladimany.spindle.ui.components.LocalBottomOverlayPadding
import io.github.eladimany.spindle.ui.components.ScrubLetterBubble
import io.github.eladimany.spindle.ui.components.SparkleIcon
import io.github.eladimany.spindle.ui.components.TrackActionsSheet
import io.github.eladimany.spindle.ui.components.TrackRow
import io.github.eladimany.spindle.ui.components.glow
import io.github.eladimany.spindle.ui.components.rememberScrollTapGuard
import io.github.eladimany.spindle.ui.shuffle.SmartShuffleSheet
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
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    var showSortMenu by remember { mutableStateOf(false) }
    var actionsTarget by remember { mutableStateOf<List<Track>?>(null) }
    var scrubLetter by remember { mutableStateOf<Char?>(null) }
    val listState = rememberLazyListState()
    var showSmartShuffleSheet by remember { mutableStateOf(false) }
    if (showSmartShuffleSheet) {
        SmartShuffleSheet(onDismiss = { showSmartShuffleSheet = false })
    }
    val tapGuard = rememberScrollTapGuard(listState)
    val scope = rememberCoroutineScope()
    // The list has a "Shuffle All" header before the tracks, so a section's position
    // in the title-ordered library is one behind its row index in this LazyColumn.
    val headerOffset = 1

    Scaffold(
        modifier = modifier,
        topBar = {
            LargeTitleBar(title = "Tracks", subtitle = summary) {
                GlassIconButton(onClick = onSearchClick) {
                    Icon(Icons.Default.Search, contentDescription = "Search")
                }
                Box {
                    GlassIconButton(onClick = { showSortMenu = true }) {
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
                }
            }
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
                    ShuffleRow(
                        onPlay = tapGuard.guard(viewModel::playAll),
                        onShuffle = tapGuard.guard(viewModel::shuffleAll),
                        onSmartShuffle = tapGuard.guard(viewModel::smartShuffleAll),
                        onSmartShuffleSettings = { showSmartShuffleSheet = true },
                    )
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

            scrubLetter?.let { ScrubLetterBubble(it, Modifier.align(Alignment.Center)) }
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

// Flat tonal pills with a hairline border, not a filled gradient — a bright gradient
// button here read as "cheap" against the rest of the list (see the UI refresh concepts
// board's revised, darker Tracks mockup). Smart shuffle gets the one accent: a stronger
// border and a soft glow, same "shine without gloss" as the nav bar.
@Composable
private fun ShuffleRow(
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onSmartShuffle: () -> Unit,
    onSmartShuffleSettings: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The whole library in order — same glowing button as the detail screens, sized to the pills.
        GlowPlayButton(onClick = onPlay, modifier = Modifier.size(48.dp))
        Row(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, primary.copy(alpha = 0.18f), shape)
                .clickable(role = Role.Button, onClick = onShuffle),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Shuffle, contentDescription = null, tint = primary, modifier = Modifier.size(20.dp))
            Text(
                "Shuffle all",
                style = MaterialTheme.typography.titleSmall,
                color = primary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .glow(primary.copy(alpha = 0.18f), radius = 16.dp, cornerRadius = 24.dp)
                .clip(shape)
                .background(primary.copy(alpha = 0.12f))
                .border(1.dp, primary.copy(alpha = 0.45f), shape)
                .combinedClickable(
                    role = Role.Button,
                    onLongClickLabel = "Smart shuffle settings",
                    onLongClick = onSmartShuffleSettings,
                    onClick = onSmartShuffle,
                ),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(SparkleIcon, contentDescription = null, tint = primary, modifier = Modifier.size(18.dp))
            Text(
                "Smart shuffle",
                style = MaterialTheme.typography.titleSmall,
                color = primary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
