package io.github.eladimany.spindle.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.ui.components.AlbumArtwork
import io.github.eladimany.spindle.ui.components.LocalBottomOverlayPadding
import io.github.eladimany.spindle.ui.components.TrackActionsSheet
import io.github.eladimany.spindle.ui.components.TrackRow
import io.github.eladimany.spindle.ui.playlists.AddToPlaylistSheet

@Composable
fun AlbumDetailScreen(
    modifier: Modifier = Modifier,
    viewModel: AlbumDetailViewModel = hiltViewModel(),
) {
    val album by viewModel.album.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val currentTrackId by viewModel.currentTrackId.collectAsStateWithLifecycle()
    var albumPlaylistTrackIds by remember { mutableStateOf<List<Long>?>(null) }
    var actionsTarget by remember { mutableStateOf<List<Track>?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(album?.name ?: "") },
                actions = {
                    IconButton(onClick = { albumPlaylistTrackIds = tracks.map { it.id } }) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Add album to playlist")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = LocalBottomOverlayPadding.current),
        ) {
            item {
                album?.let { a ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        AlbumArtwork(
                            album = a,
                            fetchArtworkUri = viewModel::artworkUriForAlbum,
                            modifier = Modifier.size(96.dp),
                        )
                        Column {
                            Text(a.name, style = MaterialTheme.typography.titleLarge)
                            Text(a.artistName, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${a.year ?: "—"} · ${a.trackCount} tracks",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(onClick = viewModel::playAll, modifier = Modifier.padding(top = 8.dp)) {
                                Text("Play all")
                            }
                        }
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
