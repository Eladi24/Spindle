package io.github.eladimany.spindle.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.eladimany.spindle.core.model.Album
import io.github.eladimany.spindle.core.model.Artist
import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.ui.components.AlbumArtwork
import io.github.eladimany.spindle.ui.components.LocalBottomOverlayPadding
import io.github.eladimany.spindle.ui.components.TrackActionsSheet
import io.github.eladimany.spindle.ui.components.TrackRow
import io.github.eladimany.spindle.ui.components.countLabel

@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onArtistClick: (Long) -> Unit,
    onAlbumClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val currentTrackId by viewModel.currentTrackId.collectAsStateWithLifecycle()
    var actionsTarget by remember { mutableStateOf<List<Track>?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = query,
                        onValueChange = viewModel::setQuery,
                        placeholder = { Text(viewModel.scope.placeholder) },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setQuery("") }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                                }
                            }
                        },
                        colors = TextFieldDefaults.colors(
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            query.isBlank() -> Box(
                modifier = Modifier.padding(innerPadding).fillMaxSize().padding(bottom = LocalBottomOverlayPadding.current),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Search your library",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            results.isEmpty -> Box(
                modifier = Modifier.padding(innerPadding).fillMaxSize().padding(bottom = LocalBottomOverlayPadding.current),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No results for \"$query\"",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
                contentPadding = PaddingValues(bottom = LocalBottomOverlayPadding.current),
            ) {
                if (results.artists.isNotEmpty()) {
                    if (viewModel.scope == SearchScope.ALL) item { SectionHeader("Artists") }
                    items(results.artists, key = { "artist${it.id}" }) { artist ->
                        ArtistResultRow(artist, onClick = { onArtistClick(artist.id) })
                    }
                }
                if (results.albums.isNotEmpty()) {
                    if (viewModel.scope == SearchScope.ALL) item { SectionHeader("Albums") }
                    items(results.albums, key = { "album${it.id}" }) { album ->
                        AlbumResultRow(
                            album,
                            onClick = { onAlbumClick(album.id) },
                            fetchArtworkUri = viewModel::artworkUriForAlbum,
                        )
                    }
                }
                if (results.tracks.isNotEmpty()) {
                    if (viewModel.scope == SearchScope.ALL) item { SectionHeader("Tracks") }
                    items(results.tracks, key = { "track${it.id}" }) { track ->
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
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun ArtistResultRow(artist: Artist, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .padding(4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(artist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${countLabel(artist.albumCount, "album")} · ${countLabel(artist.trackCount, "track")}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun AlbumResultRow(album: Album, onClick: () -> Unit, fetchArtworkUri: suspend (Album) -> String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArtwork(
            album = album,
            fetchArtworkUri = fetchArtworkUri,
            modifier = Modifier.size(48.dp),
        )
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(album.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(album.artistName, style = MaterialTheme.typography.bodySmall)
        }
    }
}
