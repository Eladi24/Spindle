package io.github.eladimany.spindle.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
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

@Composable
fun ArtistsScreen(
    onArtistClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ArtistsViewModel = hiltViewModel(),
) {
    val artists = viewModel.artists.collectAsLazyPagingItems()

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Artists") }) },
    ) { innerPadding ->
        LazyColumn(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            items(count = artists.itemCount, key = artists.itemKey { it.id }) { index ->
                val artist = artists[index] ?: return@items
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onArtistClick(artist.id) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(artist.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${artist.albumCount} albums · ${artist.trackCount} tracks",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}
