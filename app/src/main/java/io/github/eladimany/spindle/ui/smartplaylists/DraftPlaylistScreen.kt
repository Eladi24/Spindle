package io.github.eladimany.spindle.ui.smartplaylists

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.data.smartplaylists.PlaylistCriteria
import io.github.eladimany.spindle.data.smartplaylists.PlaylistDraft
import io.github.eladimany.spindle.ui.components.LocalBottomOverlayPadding
import io.github.eladimany.spindle.ui.components.SparkleIcon
import io.github.eladimany.spindle.ui.components.TrackArtwork
import io.github.eladimany.spindle.ui.components.aiGlowBehind
import io.github.eladimany.spindle.ui.components.glow
import io.github.eladimany.spindle.ui.components.rememberDriftPhase
import java.util.Locale

/** Mockup screen D: the unsaved result, with the filters it came from editable in place. */
@Composable
fun DraftPlaylistScreen(
    onBack: () -> Unit,
    onSaved: (Long) -> Unit,
    viewModel: DraftPlaylistViewModel = hiltViewModel(),
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val currentTrackId by viewModel.currentTrackId.collectAsStateWithLifecycle()
    var showAdjust by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (draft?.tracks?.isNotEmpty() == true) {
                        OutlinedButton(
                            onClick = { viewModel.save(onSaved) },
                            modifier = Modifier.padding(end = 8.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                        ) {
                            Icon(Icons.Default.BookmarkBorder, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Save")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { innerPadding ->
        val current = draft
        if (current == null) {
            Column(
                Modifier.padding(innerPadding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("This draft is gone", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Drafts aren't kept when Android closes the app. Build a new one from Playlists.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = LocalBottomOverlayPadding.current + 8.dp),
        ) {
            item(key = "header") {
                DraftHeader(
                    draft = current,
                    fetchArtworkUri = viewModel::artworkUriFor,
                    onRename = { showRename = true },
                )
            }
            item(key = "filters") {
                FilterChips(
                    criteria = current.criteria,
                    fromAi = current.request != null,
                    onDropDecade = viewModel::dropDecade,
                    onDropGenre = viewModel::dropGenre,
                    onAdjust = { showAdjust = true },
                )
            }
            item(key = "actions") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FilledIconButton(
                        onClick = { viewModel.play() },
                        enabled = current.tracks.isNotEmpty(),
                        modifier = Modifier
                            .size(60.dp)
                            .glow(colors.primary.copy(alpha = 0.45f), radius = 20.dp, cornerRadius = 30.dp),
                        shape = CircleShape,
                    ) { Icon(Icons.Default.PlayArrow, contentDescription = "Play", modifier = Modifier.size(30.dp)) }
                    IconButton(
                        onClick = viewModel::playSmartShuffled,
                        enabled = current.tracks.isNotEmpty(),
                        modifier = Modifier
                            .size(48.dp)
                            .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape),
                        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.White.copy(alpha = 0.06f)),
                    ) {
                        // Shuffle glyph with the smart sparkle badge, as on Now Playing's button.
                        Box {
                            Icon(Icons.Default.Shuffle, contentDescription = "Smart shuffle")
                            Icon(
                                SparkleIcon,
                                contentDescription = null,
                                tint = colors.primary,
                                modifier = Modifier.size(10.dp).align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp),
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = viewModel::remix,
                        enabled = !busy,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Autorenew, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("Remix")
                    }
                }
            }
            if (current.tracks.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "Nothing in your library matches these filters. Drop one above, or adjust.",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(current.tracks, key = { _, t -> t.id }) { index, track ->
                DraftTrackRow(
                    track = track,
                    isCurrent = track.id == currentTrackId,
                    fetchArtworkUri = viewModel::artworkUriFor,
                    onClick = { viewModel.play(index) },
                    onRemove = { viewModel.removeTrack(track) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }

    if (showAdjust) {
        BuildPlaylistSheet(
            initial = draft?.criteria,
            onDismiss = { showAdjust = false },
            onMade = { showAdjust = false },
        )
    }

    if (showRename) {
        var name by remember { mutableStateOf(draft?.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Name this playlist") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.rename(name)
                    showRename = false
                }) { Text("Done") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DraftHeader(
    draft: PlaylistDraft,
    fetchArtworkUri: suspend (Track) -> String?,
    onRename: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val drift by rememberDriftPhase()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .aiGlowBehind({ drift }, strength = 0.4f)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CoverMosaic(draft.tracks, fetchArtworkUri)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.primary.copy(alpha = 0.14f))
                    .border(1.dp, colors.primary.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(SparkleIcon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(12.dp))
                Text(
                    if (draft.request != null) "AI DRAFT" else "DRAFT",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                )
            }
            Text(
                draft.name,
                modifier = Modifier.clickable(onClickLabel = "Rename", onClick = onRename),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.8).sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            draft.request?.let {
                Text(
                    "“$it”",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(summary(draft.tracks), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

/** 2×2 of the first four different albums' covers (fewer albums → fewer tiles filled). */
@Composable
private fun CoverMosaic(tracks: List<Track>, fetchArtworkUri: suspend (Track) -> String?) {
    val covers = tracks.distinctBy { it.albumId }.take(4)
    Box(
        modifier = Modifier
            .size(132.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(22.dp)),
    ) {
        if (covers.size < 4) {
            covers.firstOrNull()?.let {
                TrackArtwork(it, fetchArtworkUri, Modifier.fillMaxSize(), cornerRadiusDp = 0)
            }
        } else {
            Column {
                covers.chunked(2).forEach { row ->
                    Row {
                        row.forEach { TrackArtwork(it, fetchArtworkUri, Modifier.size(66.dp), cornerRadiusDp = 0) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChips(
    criteria: PlaylistCriteria,
    fromAi: Boolean,
    onDropDecade: (Int) -> Unit,
    onDropGenre: (String) -> Unit,
    onAdjust: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            if (fromAi) "SPINDLE HEARD · TAP ✕ TO DROP" else "BUILT FROM · TAP ✕ TO DROP",
            style = MaterialTheme.typography.labelMedium,
            letterSpacing = 0.6.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            criteria.decades.sorted().forEach { decade ->
                val label = PlaylistCriteria.decadeLabel(decade)
                DroppableChip(label, onDrop = { onDropDecade(decade) })
            }
            criteria.genres.sorted().forEach { genre ->
                DroppableChip(genre, onDrop = { onDropGenre(genre) })
            }
            FilterPill(label = criteria.length.label, selected = true, onClick = onAdjust)
            if (criteria.decades.isEmpty() && criteria.genres.isEmpty()) {
                FilterPill(label = "Whole library", selected = true, onClick = onAdjust)
            }
            FilterPill(
                label = "Adjust",
                selected = false,
                dashed = true,
                onClick = onAdjust,
                trailing = { Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary) },
            )
        }
    }
}

@Composable
private fun DroppableChip(label: String, onDrop: () -> Unit) {
    FilterPill(
        label = label,
        selected = true,
        onClick = onDrop,
        trailing = {
            Icon(
                Icons.Default.Close,
                contentDescription = "Drop $label",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
private fun DraftTrackRow(
    track: Track,
    isCurrent: Boolean,
    fetchArtworkUri: suspend (Track) -> String?,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TrackArtwork(track, fetchArtworkUri, Modifier.size(48.dp), cornerRadiusDp = 10)
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) colors.primary else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(track.artistName, track.year?.toString()).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Close, contentDescription = "Remove ${track.title}", tint = colors.onSurfaceVariant)
        }
    }
}

/** "17 tracks · 1 h 02 min · 9 artists". */
private fun summary(tracks: List<Track>): String {
    val totalMin = tracks.sumOf { it.durationMs } / 60_000
    val duration = if (totalMin >= 60) {
        String.format(Locale.ROOT, "%d h %02d min", totalMin / 60, totalMin % 60)
    } else {
        "$totalMin min"
    }
    val artists = tracks.map { it.artistId }.distinct().size
    return listOf(
        if (tracks.size == 1) "1 track" else "${tracks.size} tracks",
        duration,
        if (artists == 1) "1 artist" else "$artists artists",
    ).joinToString(" · ")
}
