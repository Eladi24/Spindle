package io.github.eladimany.spindle.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.ui.playlists.AddToPlaylistSheet

/**
 * The long-press menu for a track row anywhere in the library — "Add to Queue" acts
 * immediately, "Add to Playlist" swaps to [AddToPlaylistSheet]'s playlist picker in the
 * same sheet slot rather than stacking a second sheet on top.
 */
@Composable
fun TrackActionsSheet(
    tracks: List<Track>,
    onAddToQueue: (List<Track>) -> Unit,
    onDismiss: () -> Unit,
) {
    var showPlaylistPicker by remember { mutableStateOf(false) }

    if (showPlaylistPicker) {
        AddToPlaylistSheet(trackIds = tracks.map { it.id }, onDismiss = onDismiss)
        return
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        TrackActionRow(
            icon = Icons.AutoMirrored.Filled.QueueMusic,
            label = "Add to queue",
            onClick = {
                onAddToQueue(tracks)
                onDismiss()
            },
        )
        TrackActionRow(
            icon = Icons.AutoMirrored.Filled.PlaylistAdd,
            label = "Add to playlist",
            onClick = { showPlaylistPicker = true },
        )
    }
}

@Composable
private fun TrackActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
    }
}
