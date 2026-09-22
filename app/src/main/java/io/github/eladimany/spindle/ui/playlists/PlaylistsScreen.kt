package io.github.eladimany.spindle.ui.playlists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.eladimany.spindle.core.model.Playlist

@Composable
fun PlaylistsScreen(
    onPlaylistClick: (Long) -> Unit,
    onSearchClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: PlaylistsViewModel = hiltViewModel(),
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Playlist?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Playlists") },
                actions = {
                    IconButton(onClick = onSearchClick) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "New playlist")
            }
        },
    ) { innerPadding ->
        if (playlists.isEmpty()) {
            Text(
                "No playlists yet",
                modifier = Modifier.padding(innerPadding).padding(16.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            LazyColumn(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                items(playlists, key = { it.id }) { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlaylistClick(playlist.id) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(playlist.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${playlist.trackCount} tracks",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { renameTarget = playlist }) {
                            Icon(Icons.Default.Edit, contentDescription = "Rename playlist")
                        }
                        IconButton(onClick = { viewModel.delete(playlist) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete playlist")
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (showCreateDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("New playlist") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.create(name)
                    showCreateDialog = false
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") }
            },
        )
    }

    renameTarget?.let { target ->
        var name by remember(target.id) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename playlist") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.rename(target, name)
                    renameTarget = null
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            },
        )
    }
}
