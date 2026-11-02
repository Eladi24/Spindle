package io.github.eladimany.spindle.ui.library

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import io.github.eladimany.spindle.ui.components.AlbumArtwork
import io.github.eladimany.spindle.ui.covers.FindCoversSheet
import io.github.eladimany.spindle.ui.covers.MissingCoversButton
import io.github.eladimany.spindle.ui.covers.MissingCoversNotice
import io.github.eladimany.spindle.ui.covers.rememberMusicFolderPicker
import io.github.eladimany.spindle.ui.components.GlassIconButton
import io.github.eladimany.spindle.ui.components.LargeTitleBar
import io.github.eladimany.spindle.ui.components.LocalBottomOverlayPadding
import io.github.eladimany.spindle.ui.components.countLabel
import io.github.eladimany.spindle.ui.components.rememberScrollTapGuard

@Composable
fun AlbumsScreen(
    onAlbumClick: (Long) -> Unit,
    onSearchClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: AlbumsViewModel = hiltViewModel(),
) {
    val albums = viewModel.albums.collectAsLazyPagingItems()
    val albumCount by viewModel.albumCount.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    val tapGuard = rememberScrollTapGuard(gridState)
    val flowView by viewModel.flowView.collectAsStateWithLifecycle()
    val allAlbums by viewModel.allAlbums.collectAsStateWithLifecycle()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val coverNotice by viewModel.missingCoverNotice.collectAsStateWithLifecycle()
    val coverScan by viewModel.coverScan.collectAsStateWithLifecycle()
    val missingCovers by viewModel.missingCoverCount.collectAsStateWithLifecycle()
    var showCoverSheet by remember { mutableStateOf(false) }
    val folderGranted by viewModel.folderGranted.collectAsStateWithLifecycle()
    val folderPass by viewModel.folderPass.collectAsStateWithLifecycle()
    val pickMusicFolder = rememberMusicFolderPicker(viewModel::grantFolder)
    if (showCoverSheet) {
        FindCoversSheet(
            scan = coverScan,
            missingCount = missingCovers,
            fetchArtworkUri = viewModel::artworkUriForAlbum,
            folderGranted = folderGranted,
            folderPass = folderPass,
            onAllowFolder = pickMusicFolder,
            onStart = viewModel::findAllCovers,
            onStop = viewModel::stopFindingCovers,
            onReject = viewModel::rejectCover,
            onDismiss = {
                showCoverSheet = false
                viewModel.closeCoverScan()
            },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            if (landscape && flowView == true) {
                // One short row sideways, so the cover flow gets the height.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 20.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Albums", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                    Text(
                        albumCount?.let { countLabel(it, "album") }.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    AlbumsViewToggle(flow = true, onChange = viewModel::setFlowView)
                    GlassIconButton(onClick = onSearchClick, size = 40.dp) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                }
            } else {
                LargeTitleBar(title = "Albums", subtitle = albumCount?.let { countLabel(it, "album") }) {
                    AlbumsViewToggle(flow = flowView == true, onChange = viewModel::setFlowView)
                    GlassIconButton(onClick = onSearchClick) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                }
            }
        },
    ) { innerPadding ->
        // Unknown until the setting loads: draw nothing rather than flash the grid.
        if (flowView == null) return@Scaffold
        if (flowView == true) {
            // Upright: the record crate. Sideways: cover flow.
            val flowModifier = Modifier.fillMaxSize()
            Box(Modifier.padding(innerPadding).fillMaxSize()) {
            if (!landscape) {
                AlbumRecordCrate(
                    albums = allAlbums,
                    fetchArtworkUri = viewModel::artworkUriForAlbum,
                    onOpenAlbum = onAlbumClick,
                    onPlay = viewModel::play,
                    bottomPadding = LocalBottomOverlayPadding.current,
                    startAlbumId = viewModel.flowAlbumId,
                    onCentred = { viewModel.flowAlbumId = it },
                    modifier = flowModifier,
                )
            } else {
                AlbumCoverFlow(
                    albums = allAlbums,
                    fetchArtworkUri = viewModel::artworkUriForAlbum,
                    onOpenAlbum = onAlbumClick,
                    onPlay = viewModel::play,
                    bottomPadding = LocalBottomOverlayPadding.current,
                    startAlbumId = viewModel.flowAlbumId,
                    onCentred = { viewModel.flowAlbumId = it },
                    modifier = flowModifier,
                )
            }
            // No room for the notice here; the same sheet opens from a pill in the corner.
            if (missingCovers > 0) {
                MissingCoversButton(
                    count = missingCovers,
                    onClick = { showCoverSheet = true },
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 16.dp, top = 4.dp),
                )
            }
            }
            return@Scaffold
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 140.dp),
            state = gridState,
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 8.dp + LocalBottomOverlayPadding.current),
        ) {
            if (coverNotice > 0) {
                item(key = "cover-notice", span = { GridItemSpan(maxLineSpan) }) {
                    MissingCoversNotice(
                        count = coverNotice,
                        onFind = { showCoverSheet = true },
                        onDismiss = viewModel::dismissCoverNotice,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            items(count = albums.itemCount, key = albums.itemKey { it.id }) { index ->
                val album = albums[index] ?: return@items
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                        .clickable(onClick = tapGuard.guard { onAlbumClick(album.id) }),
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
