package io.github.eladimany.spindle.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.eladimany.spindle.ui.components.AlbumArtwork
import io.github.eladimany.spindle.ui.playlists.AddToPlaylistSheet

@Composable
fun ArtistDetailScreen(
    onAlbumClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ArtistDetailViewModel = hiltViewModel(),
) {
    val artist by viewModel.artist.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    var addToPlaylistTrackIds by remember { mutableStateOf<List<Long>?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(artist?.name ?: "") },
                actions = {
                    IconButton(onClick = { addToPlaylistTrackIds = tracks.map { it.id } }) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Add artist to playlist")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            items(albums, key = { it.id }) { album ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onAlbumClick(album.id) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AlbumArtwork(
                        album = album,
                        fetchArtworkUri = viewModel::artworkUriForAlbum,
                        modifier = Modifier.size(48.dp),
                    )
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(album.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${album.year ?: "—"} · ${album.trackCount} tracks",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }

    addToPlaylistTrackIds?.let { ids ->
        AddToPlaylistSheet(trackIds = ids, onDismiss = { addToPlaylistTrackIds = null })
    }
}
