package io.github.eladimany.spindle.ui.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import io.github.eladimany.spindle.ui.components.AlbumArtwork
import io.github.eladimany.spindle.ui.components.DetailHeader
import io.github.eladimany.spindle.ui.components.DetailTopBar
import io.github.eladimany.spindle.ui.components.LocalBottomOverlayPadding
import io.github.eladimany.spindle.ui.components.TrackActionsSheet
import io.github.eladimany.spindle.ui.components.TrackRow
import io.github.eladimany.spindle.ui.components.countLabel
import io.github.eladimany.spindle.ui.components.durationLabel
import io.github.eladimany.spindle.ui.playlists.AddToPlaylistSheet

@Composable
fun AlbumDetailScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AlbumDetailViewModel = hiltViewModel(),
) {
    val album by viewModel.album.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val currentTrackId by viewModel.currentTrackId.collectAsStateWithLifecycle()
    var albumPlaylistTrackIds by remember { mutableStateOf<List<Long>?>(null) }
    var actionsTarget by remember { mutableStateOf<List<Track>?>(null) }
    val listState = rememberLazyListState()

    Scaffold(
        modifier = modifier,
        topBar = {
            DetailTopBar(title = album?.name.orEmpty(), onBack = onBack, listState = listState) {
                IconButton(onClick = { albumPlaylistTrackIds = tracks.map { it.id } }) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Add album to playlist")
                }
            }
        },
    ) { innerPadding ->
        // Top padding goes in contentPadding so the list (and the header's glow) reaches
        // up under the clear top bar.
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = innerPadding.calculateTopPadding(), bottom = LocalBottomOverlayPadding.current),
        ) {
            item(key = "header") {
                album?.let { a ->
                    DetailHeader(
                        label = "ALBUM",
                        title = a.name,
                        accent = a.artistName,
                        meta = listOfNotNull(
                            a.year?.toString(),
                            countLabel(tracks.size.takeIf { it > 0 } ?: a.trackCount, "track"),
                            tracks.sumOf { it.durationMs }.takeIf { it > 0 }?.let { durationLabel(it) },
                        ).joinToString(" · "),
                        onPlay = viewModel::playAll,
                        onShuffle = viewModel::playShuffled,
                        onSmartShuffle = viewModel::playSmartShuffled,
                        playEnabled = tracks.isNotEmpty(),
                    ) {
                        AlbumArtwork(
                            album = a,
                            fetchArtworkUri = viewModel::artworkUriForAlbum,
                            modifier = Modifier.fillMaxSize(),
                            cornerRadiusDp = 14,
                        )
                    }
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

    albumPlaylistTrackIds?.let { ids ->
        AddToPlaylistSheet(trackIds = ids, onDismiss = { albumPlaylistTrackIds = null })
    }
    actionsTarget?.let { target ->
        TrackActionsSheet(
            tracks = target,
            onAddToQueue = viewModel::addToQueue,
            onDismiss = { actionsTarget = null },
        )
    }
}
