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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.eladimany.spindle.core.model.Album
import io.github.eladimany.spindle.core.model.Artist
import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.ui.components.AlbumArtwork
import io.github.eladimany.spindle.ui.components.DetailTopBar
import io.github.eladimany.spindle.ui.components.GlowPlayButton
import io.github.eladimany.spindle.ui.components.LocalBottomOverlayPadding
import io.github.eladimany.spindle.ui.components.ShufflePair
import io.github.eladimany.spindle.ui.components.TrackActionsSheet
import io.github.eladimany.spindle.ui.components.TrackRow
import io.github.eladimany.spindle.ui.components.countLabel
import io.github.eladimany.spindle.ui.playlists.AddToPlaylistSheet

/** Tracks shown before "All N tracks" expands the list. */
private const val TOP_TRACKS = 5

@Composable
fun ArtistDetailScreen(
    onBack: () -> Unit,
    onAlbumClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ArtistDetailViewModel = hiltViewModel(),
) {
    val artist by viewModel.artist.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val ranked by viewModel.tracksByPlays.collectAsStateWithLifecycle()
    val photoUrl by viewModel.photoUrl.collectAsStateWithLifecycle()
    val fetchingPhoto by viewModel.fetchingPhoto.collectAsStateWithLifecycle()
    val currentTrackId by viewModel.currentTrackId.collectAsStateWithLifecycle()
    var addToPlaylistTrackIds by remember { mutableStateOf<List<Long>?>(null) }
    var actionsTarget by remember { mutableStateOf<List<Track>?>(null) }
    var showAllTracks by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val tracks = ranked.tracks
    val shownTracks = if (showAllTracks) tracks else tracks.take(TOP_TRACKS)

    Scaffold(
        modifier = modifier,
        topBar = {
            DetailTopBar(title = artist?.name.orEmpty(), onBack = onBack, listState = listState) {
                IconButton(onClick = { addToPlaylistTrackIds = tracks.map { it.id } }) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Add artist to playlist")
                }
            }
        },
    ) { _ ->
        // No top padding at all: the photo runs up under the status bar and the clear top bar.
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = LocalBottomOverlayPadding.current),
        ) {
            item(key = "hero") {
                artist?.let { a ->
                    ArtistHero(
                        artist = a,
                        photoUrl = photoUrl,
                        albums = albums,
                        fetchAlbumArtworkUri = viewModel::artworkUriForAlbum,
                        fetchingPhoto = fetchingPhoto,
                        onFetchPhoto = viewModel::fetchPhoto,
                        albumCount = albums.size,
                        trackCount = tracks.size,
                    )
                }
            }
            item(key = "actions") {
                Row(
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    GlowPlayButton(onClick = viewModel::playAll, enabled = tracks.isNotEmpty())
                    ShufflePair(onShuffle = viewModel::playShuffled, onSmartShuffle = viewModel::playSmartShuffled, enabled = tracks.isNotEmpty())
                }
            }
            if (albums.isNotEmpty()) {
                item(key = "albums-title") { SectionTitle("Albums") }
                item(key = "albums") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(albums, key = { it.id }) { album ->
                            AlbumTile(album, viewModel::artworkUriForAlbum, onClick = { onAlbumClick(album.id) })
                        }
                    }
                }
            }
            if (tracks.isNotEmpty()) {
                item(key = "tracks-title") {
                    SectionTitle(
                        if (ranked.hasHistory) "Most played" else "Tracks",
                        action = if (tracks.size > TOP_TRACKS) {
                            {
                                TextButton(onClick = { showAllTracks = !showAllTracks }) {
                                    Text(if (showAllTracks) "Show less" else "All ${tracks.size} tracks")
                                }
                            }
                        } else {
                            null
                        },
                    )
                }
                items(shownTracks, key = { it.id }) { track ->
                    TrackRow(
                        track = track,
                        isCurrent = track.id == currentTrackId,
                        onClick = { viewModel.playTrack(track) },
                        onLongClick = { actionsTarget = listOf(track) },
                        fetchArtworkUri = viewModel::artworkUriFor,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    addToPlaylistTrackIds?.let { ids ->
        AddToPlaylistSheet(trackIds = ids, onDismiss = { addToPlaylistTrackIds = null })
    }
    actionsTarget?.let { target ->
        TrackActionsSheet(
            tracks = target,
            onAddToQueue = viewModel::addToQueue,
            onDismiss = { actionsTarget = null },
        )
    }
}

/**
 * The artist's photo full-bleed, fading into the background, with the name over it.
 * Without a photo, the backdrop is a mosaic of the artist's album covers and a
 * "Find photo" button offers the opt-in Deezer lookup.
 */
@Composable
private fun ArtistHero(
    artist: Artist,
    photoUrl: String?,
    albums: List<Album>,
    fetchAlbumArtworkUri: suspend (Album) -> String?,
    fetchingPhoto: Boolean,
    onFetchPhoto: () -> Unit,
    albumCount: Int,
    trackCount: Int,
) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().height(400.dp)) {
        if (photoUrl != null) {
            AsyncImage(
                model = photoUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            CoverBackdrop(albums, fetchAlbumArtworkUri)
        }
        // Top scrim keeps the back arrow and status bar readable on a bright photo.
        Box(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent))),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(260.dp)
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, colors.background.copy(alpha = 0.85f), colors.background))),
        )
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "ARTIST",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = colors.primary,
            )
            Text(
                artist.name,
                fontSize = 44.sp,
                lineHeight = 46.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-1.4).sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    listOf(countLabel(albumCount, "album"), countLabel(trackCount, "track")).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                if (photoUrl == null) {
                    FindPhotoChip(fetching = fetchingPhoto, onClick = onFetchPhoto)
                }
            }
        }
    }
}

/** Up to four of the artist's album covers, tiled edge to edge (one fills the whole backdrop). */
@Composable
private fun CoverBackdrop(albums: List<Album>, fetchArtworkUri: suspend (Album) -> String?) {
    val covers = albums.take(4)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        when {
            covers.isEmpty() -> Unit
            covers.size < 4 -> AlbumArtwork(covers.first(), fetchArtworkUri, Modifier.fillMaxSize(), cornerRadiusDp = 0)
            else -> Column(Modifier.fillMaxSize()) {
                covers.chunked(2).forEach { row ->
                    Row(Modifier.weight(1f)) {
                        row.forEach { AlbumArtwork(it, fetchArtworkUri, Modifier.weight(1f).fillMaxSize(), cornerRadiusDp = 0) }
                    }
                }
            }
        }
    }
}

/** Small glass pill: the one, deliberate trigger for fetching this artist's photo. */
@Composable
private fun FindPhotoChip(fetching: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, Color.White.copy(alpha = 0.16f), shape)
            .clickable(enabled = !fetching, onClickLabel = "Find a photo of this artist online", onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (fetching) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text("Find photo", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun SectionTitle(text: String, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge, letterSpacing = (-0.3).sp, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
private fun AlbumTile(album: Album, fetchArtworkUri: suspend (Album) -> String?, onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(132.dp).clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AlbumArtwork(album, fetchArtworkUri, Modifier.fillMaxWidth().aspectRatio(1f))
        Text(album.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        album.year?.let {
            Text(it.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(2.dp))
    }
}
