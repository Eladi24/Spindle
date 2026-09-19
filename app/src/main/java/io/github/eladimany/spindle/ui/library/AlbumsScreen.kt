package io.github.eladimany.spindle.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import io.github.eladimany.spindle.ui.components.AlbumArtwork

@Composable
fun AlbumsScreen(
    onAlbumClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AlbumsViewModel = hiltViewModel(),
) {
    val albums = viewModel.albums.collectAsLazyPagingItems()

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Albums") }) },
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 140.dp),
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
        ) {
            items(count = albums.itemCount, key = albums.itemKey { it.id }) { index ->
                val album = albums[index] ?: return@items
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                        .clickable { onAlbumClick(album.id) },
                ) {
                    AlbumArtwork(
                        album = album,
                        fetchArtworkUri = viewModel::artworkUriForAlbum,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    )
                    Text(
                        album.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(album.artistName, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
        }
    }
}
