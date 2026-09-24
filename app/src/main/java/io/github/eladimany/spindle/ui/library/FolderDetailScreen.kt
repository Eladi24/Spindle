package io.github.eladimany.spindle.ui.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.ui.components.DetailHeader
import io.github.eladimany.spindle.ui.components.DetailTopBar
import io.github.eladimany.spindle.ui.components.FolderTile
import io.github.eladimany.spindle.ui.components.LocalBottomOverlayPadding
import io.github.eladimany.spindle.ui.components.TrackActionsSheet
import io.github.eladimany.spindle.ui.components.TrackRow
import io.github.eladimany.spindle.ui.components.countLabel
import io.github.eladimany.spindle.ui.components.durationLabel

@Composable
fun FolderDetailScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FolderDetailViewModel = hiltViewModel(),
) {
    val folder by viewModel.folder.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val currentTrackId by viewModel.currentTrackId.collectAsStateWithLifecycle()
    var actionsTarget by remember { mutableStateOf<List<Track>?>(null) }
    val listState = rememberLazyListState()

    Scaffold(
        modifier = modifier,
        topBar = { DetailTopBar(title = folder?.name.orEmpty(), onBack = onBack, listState = listState) },
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = innerPadding.calculateTopPadding(), bottom = LocalBottomOverlayPadding.current),
        ) {
            item(key = "header") {
                folder?.let { f ->
                    DetailHeader(
                        label = "FOLDER",
                        title = f.name,
                        meta = listOfNotNull(
                            countLabel(tracks.size, "track"),
                            tracks.sumOf { it.durationMs }.takeIf { it > 0 }?.let { durationLabel(it) },
                            tracks.map { it.artistId }.distinct().size.takeIf { it > 1 }?.let { countLabel(it, "artist") },
                        ).joinToString(" · "),
                        onPlay = viewModel::playAll,
                        onShuffle = viewModel::playShuffled,
                        onSmartShuffle = viewModel::playSmartShuffled,
                        playEnabled = tracks.isNotEmpty(),
                    ) { FolderTile() }
                }
            }
            items(tracks, key = { it.id }) { track ->
                TrackRow(
                    track = track,
                    isCurrent = track.id == currentTrackId,
                    onClick = { viewModel.playTrack(track) },
                    onLongClick = { actionsTarget = listOf(track) },
                    fetchArtworkUri = viewModel::artworkUriFor,
                )
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
