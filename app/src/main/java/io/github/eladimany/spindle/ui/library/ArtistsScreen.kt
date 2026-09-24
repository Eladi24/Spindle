package io.github.eladimany.spindle.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import io.github.eladimany.spindle.ui.components.AlphabetIndexBar
import io.github.eladimany.spindle.ui.components.LocalBottomOverlayPadding
import io.github.eladimany.spindle.ui.components.ScrubLetterBubble
import io.github.eladimany.spindle.ui.components.rememberScrollTapGuard
import kotlinx.coroutines.launch

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
    val listState = rememberLazyListState()
    val tapGuard = rememberScrollTapGuard(listState)
    val sectionIndex by viewModel.sectionIndex.collectAsStateWithLifecycle()
    var scrubLetter by remember { mutableStateOf<Char?>(null) }
    val scope = rememberCoroutineScope()

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
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = LocalBottomOverlayPadding.current),
            ) {
                items(count = artists.itemCount, key = artists.itemKey { it.id }) { index ->
                    val artist = artists[index]
                    if (artist != null) {
                        ArtistRow(
                            artist = artist,
                            imageUrl = artworkByArtistId[artist.id],
                            isFetching = artist.id in fetchingIds,
                            onClick = tapGuard.guard { onArtistClick(artist.id) },
                            // Guarded too: a stray tap here would send the artist's name to Deezer.
                            onFetchArtwork = tapGuard.guard { viewModel.fetchArtwork(artist) },
                        )
                    } else {
                        // Not-yet-loaded placeholder after a rail jump: same height as ArtistRow
                        // (56dp avatar + 2 x 10dp padding) so the list doesn't shift when it loads.
                        Spacer(modifier = Modifier.fillMaxWidth().height(76.dp))
                    }
                }
            }

            if (sectionIndex.isNotEmpty()) {
                AlphabetIndexBar(
                    sections = sectionIndex,
                    onScrub = { section, active ->
                        if (active) {
                            scrubLetter = section.letter
                            scope.launch {
                                listState.scrollToItem(section.index.coerceAtMost(artists.itemCount))
                            }
                        } else {
                            scrubLetter = null
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .padding(top = 4.dp, bottom = 4.dp + LocalBottomOverlayPadding.current),
                )
            }

            scrubLetter?.let { ScrubLetterBubble(it, Modifier.align(Alignment.Center)) }
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
