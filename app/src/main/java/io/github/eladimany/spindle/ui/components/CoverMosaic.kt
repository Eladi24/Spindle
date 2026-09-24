package io.github.eladimany.spindle.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.eladimany.spindle.core.model.Track

/**
 * 2×2 of the first four different albums' covers; with fewer than four albums, the
 * first cover fills the tile. Used for playlists and drafts.
 */
@Composable
fun CoverMosaic(
    tracks: List<Track>,
    fetchArtworkUri: suspend (Track) -> String?,
    size: Dp,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
) {
    val covers = tracks.distinctBy { it.albumId }.take(4)
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape),
    ) {
        if (covers.size < 4) {
            covers.firstOrNull()?.let {
                TrackArtwork(it, fetchArtworkUri, Modifier.fillMaxSize(), cornerRadiusDp = 0)
            }
        } else {
            Column {
                covers.chunked(2).forEach { row ->
                    Row {
                        row.forEach { TrackArtwork(it, fetchArtworkUri, Modifier.size(size / 2), cornerRadiusDp = 0) }
                    }
                }
            }
        }
    }
}
