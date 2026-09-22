package io.github.eladimany.spindle.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.core.model.TrackSort
import io.github.eladimany.spindle.ui.components.TrackActionsSheet
import io.github.eladimany.spindle.ui.components.TrackRow

@Composable
fun TracksScreen(
    onSearchClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: TracksViewModel = hiltViewModel(),
) {
    val tracks = viewModel.tracks.collectAsLazyPagingItems()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val currentTrackId by viewModel.currentTrackId.collectAsStateWithLifecycle()
    var showSortMenu by remember { mutableStateOf(false) }
    var actionsTarget by remember { mutableStateOf<List<Track>?>(null) }

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
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text("No tracks yet", style = MaterialTheme.typography.bodyLarge)
            }
            return@Scaffold
        }

        LazyColumn(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            item {
                ShuffleAllRow(onClick = viewModel::shuffleAll)
                HorizontalDivider()
            }
            items(count = tracks.itemCount, key = tracks.itemKey { it.id }) { index ->
                val track = tracks[index]
                if (track != null) {
                    TrackRow(
                        track = track,
                        isCurrent = track.id == currentTrackId,
                        onClick = { viewModel.playTrack(track) },
                        onLongClick = { actionsTarget = listOf(track) },
                        fetchArtworkUri = viewModel::artworkUriFor,
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize()) { CircularProgressIndicator() }
                }
                HorizontalDivider()
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

@Composable
private fun ShuffleAllRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Shuffle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            "Shuffle All",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}
