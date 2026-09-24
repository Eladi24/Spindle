package io.github.eladimany.spindle.ui.playlists

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.eladimany.spindle.core.model.Playlist
import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.ui.components.CoverMosaic
import io.github.eladimany.spindle.ui.components.GlassIconButton
import io.github.eladimany.spindle.ui.components.LargeTitleBar
import io.github.eladimany.spindle.ui.components.LocalBottomOverlayPadding
import io.github.eladimany.spindle.ui.components.countLabel
import io.github.eladimany.spindle.ui.components.durationLabel
import io.github.eladimany.spindle.ui.components.rememberScrollTapGuard
import io.github.eladimany.spindle.ui.smartplaylists.AiRequestRow
import io.github.eladimany.spindle.ui.smartplaylists.BuildPlaylistSheet
import io.github.eladimany.spindle.ui.smartplaylists.DescribePlaylistSheet
import io.github.eladimany.spindle.ui.smartplaylists.MakePlaylistCard

@Composable
fun PlaylistsScreen(
    onPlaylistClick: (Long) -> Unit,
    onSearchClick: () -> Unit = {},
    onDraftMade: () -> Unit = {},
    onOpenAiSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: PlaylistsViewModel = hiltViewModel(),
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val tapGuard = rememberScrollTapGuard(listState)
    var showCreateDialog by remember { mutableStateOf(false) }
    var showBuilder by remember { mutableStateOf(false) }
    var showDescribe by remember { mutableStateOf(false) }
    val aiStatus by viewModel.aiStatus.collectAsStateWithLifecycle()
    val aiRequest by viewModel.aiRequest.collectAsStateWithLifecycle()
    var renameTarget by remember { mutableStateOf<Playlist?>(null) }
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        if (content != null) {
            val suggestedName = queryDisplayName(context, uri)?.substringBeforeLast('.') ?: "Imported playlist"
            viewModel.importM3u(suggestedName, content)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.importResults.collect { result ->
            snackbarHostState.showSnackbar("Imported ${result.matched} of ${result.total} tracks")
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState, modifier = Modifier.padding(bottom = LocalBottomOverlayPadding.current)) },
        topBar = {
            LargeTitleBar(title = "Playlists", subtitle = countLabel(playlists.size, "playlist")) {
                GlassIconButton(onClick = onSearchClick) {
                    Icon(Icons.Default.Search, contentDescription = "Search")
                }
                GlassIconButton(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "New playlist")
                }
                GlassIconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Default.FileUpload, contentDescription = "Import M3U playlist")
                }
            }
        },
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = LocalBottomOverlayPadding.current),
        ) {
            item(key = "make-playlist") {
                MakePlaylistCard(
                    status = aiStatus,
                    onDescribe = tapGuard.guard { showDescribe = true },
                    onSuggestion = { viewModel.makeFromRequest(it) },
                    onBuild = tapGuard.guard { showBuilder = true },
                    onOpenSettings = onOpenAiSettings,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                )
            }
            aiRequest?.let { request ->
                item(key = "ai-request") {
                    AiRequestRow(
                        state = request,
                        onOpen = onDraftMade,
                        onRetry = viewModel::retryAiRequest,
                        onDismiss = viewModel::dismissAiRequest,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            if (playlists.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "No playlists yet",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            items(playlists, key = { it.id }) { playlist ->
                PlaylistRow(
                    playlist = playlist,
                    coverTracks = viewModel::coverTracks,
                    fetchArtworkUri = viewModel::artworkUriFor,
                    onClick = tapGuard.guard { onPlaylistClick(playlist.id) },
                    onRename = { renameTarget = playlist },
                    onDelete = { viewModel.delete(playlist) },
                    modifier = Modifier.animateItem(),
                )
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

    if (showDescribe) {
        DescribePlaylistSheet(
            initialText = "",
            onDismiss = { showDescribe = false },
            onMake = { request, default ->
                showDescribe = false
                viewModel.makeFromRequest(request, default)
            },
        )
    }

    if (showBuilder) {
        BuildPlaylistSheet(
            initial = null,
            onDismiss = { showBuilder = false },
            onMade = {
                showBuilder = false
                onDraftMade()
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

/** Cover mosaic, name, "42 tracks · 2 h 51 min", and a ⋮ menu for rename/delete. */
@Composable
private fun PlaylistRow(
    playlist: Playlist,
    coverTracks: suspend (Long) -> List<Track>,
    fetchArtworkUri: suspend (Track) -> String?,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val covers by produceState(emptyList<Track>(), playlist.id, playlist.trackCount, playlist.updatedAt) {
        value = coverTracks(playlist.id)
    }
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CoverMosaic(covers, fetchArtworkUri, size = 56.dp, cornerRadius = 10.dp)
        Column(Modifier.weight(1f)) {
            Text(playlist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    countLabel(playlist.trackCount, "track"),
                    playlist.totalDurationMs.takeIf { it > 0 }?.let { durationLabel(it) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "More options for ${playlist.name}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? {
    val cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
    return cursor?.use {
        if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
    }
}
