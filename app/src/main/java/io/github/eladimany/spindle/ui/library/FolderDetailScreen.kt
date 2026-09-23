package io.github.eladimany.spindle.ui.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.ui.components.LocalBottomOverlayPadding
import io.github.eladimany.spindle.ui.components.TrackActionsSheet
import io.github.eladimany.spindle.ui.components.TrackRow

@Composable
fun FolderDetailScreen(
    modifier: Modifier = Modifier,
    viewModel: FolderDetailViewModel = hiltViewModel(),
) {
    val folder by viewModel.folder.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val currentTrackId by viewModel.currentTrackId.collectAsStateWithLifecycle()
    var actionsTarget by remember { mutableStateOf<List<Track>?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(folder?.name ?: "") }) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = LocalBottomOverlayPadding.current),
        ) {
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
