package io.github.eladimany.spindle.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import io.github.eladimany.spindle.core.model.Album
import io.github.eladimany.spindle.core.model.Track

/**
 * Album artwork, extracted lazily and cached — see [ArtworkRepository][
 * io.github.eladimany.spindle.data.library.ArtworkRepository]. Falls back to a plain
 * icon while loading or when there's no embedded art.
 */
@Composable
fun TrackArtwork(
    track: Track,
    fetchArtworkUri: suspend (Track) -> String?,
    modifier: Modifier = Modifier,
    cornerRadiusDp: Int = 12,
) {
    ArtworkBox(
        key = track.albumId,
        fetchUri = { fetchArtworkUri(track) },
        modifier = modifier,
        cornerRadiusDp = cornerRadiusDp,
    )
}

@Composable
fun AlbumArtwork(
    album: Album,
    fetchArtworkUri: suspend (Album) -> String?,
    modifier: Modifier = Modifier,
    cornerRadiusDp: Int = 12,
) {
    ArtworkBox(
        key = album.id,
        fetchUri = { fetchArtworkUri(album) },
        modifier = modifier,
        cornerRadiusDp = cornerRadiusDp,
    )
}

@Composable
private fun ArtworkBox(
    key: Any,
    fetchUri: suspend () -> String?,
    modifier: Modifier,
    cornerRadiusDp: Int,
) {
    val artworkUri by produceState<String?>(initialValue = null, key) {
        value = fetchUri()
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadiusDp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (artworkUri != null) {
            AsyncImage(
                model = artworkUri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
