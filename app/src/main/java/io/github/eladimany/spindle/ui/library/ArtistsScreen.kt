package io.github.eladimany.spindle.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import io.github.eladimany.spindle.core.model.Artist
import io.github.eladimany.spindle.ui.components.LocalBottomOverlayPadding

@Composable
fun ArtistsScreen(
    onArtistClick: (Long) -> Unit,
    onSearchClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ArtistsViewModel = hiltViewModel(),
) {
    val artists = viewModel.artists.collectAsLazyPagingItems()
    val artworkByArtistId by viewModel.artworkByArtistId.collectAsStateWithLifecycle()
    val fetchingIds by viewModel.fetchingIds.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Artists") },
                actions = {
                    IconButton(onClick = onSearchClick) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = LocalBottomOverlayPadding.current),
        ) {
            items(count = artists.itemCount, key = artists.itemKey { it.id }) { index ->
                val artist = artists[index] ?: return@items
                ArtistRow(
                    artist = artist,
                    imageUrl = artworkByArtistId[artist.id],
                    isFetching = artist.id in fetchingIds,
                    onClick = { onArtistClick(artist.id) },
                    onFetchArtwork = { viewModel.fetchArtwork(artist) },
                )
            }
        }
    }
}

@Composable
private fun ArtistRow(
    artist: Artist,
    imageUrl: String?,
    isFetching: Boolean,
    onClick: () -> Unit,
    onFetchArtwork: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ArtistAvatar(imageUrl = imageUrl, isFetching = isFetching, onFetchArtwork = onFetchArtwork)
        Column {
            Text(artist.name, style = MaterialTheme.typography.titleMedium)
            Text(
                "${artist.albumCount} albums · ${artist.trackCount} tracks",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The placeholder itself is the only fetch trigger — no separate button, no
 * automatic/background fetching ever. Sends [artist]'s name to Deezer's
 * public search API only on this explicit tap; see
 * `ArtistArtworkRepository`'s doc comment for why that has to stay opt-in
 * per artist rather than a one-time silent scan.
 */
@Composable
private fun ArtistAvatar(
    imageUrl: String?,
    isFetching: Boolean,
    onFetchArtwork: () -> Unit,
) {
    when {
        imageUrl != null -> {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                modifier = Modifier.size(56.dp).clip(CircleShape),
            )
        }
        isFetching -> {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        }
        else -> {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .clickable(onClick = onFetchArtwork),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Get artist photo",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
