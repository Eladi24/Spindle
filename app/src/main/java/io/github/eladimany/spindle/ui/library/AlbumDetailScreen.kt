package io.github.eladimany.spindle.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.eladimany.spindle.ui.components.AlbumArtwork
import io.github.eladimany.spindle.ui.components.TrackRow

@Composable
fun AlbumDetailScreen(
    modifier: Modifier = Modifier,
    viewModel: AlbumDetailViewModel = hiltViewModel(),
) {
    val album by viewModel.album.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(album?.name ?: "") }) },
    ) { innerPadding ->
        LazyColumn(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
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
                    isCurrent = false,
                    onClick = { viewModel.playTrack(track) },
                    fetchArtworkUri = viewModel::artworkUriFor,
                )
            }
        }
    }
}
