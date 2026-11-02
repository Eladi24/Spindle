package io.github.eladimany.spindle.ui.covers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.eladimany.spindle.core.model.Album
import io.github.eladimany.spindle.data.covers.AlbumCoverFinder
import io.github.eladimany.spindle.data.covers.CoverCandidate
import io.github.eladimany.spindle.ui.components.AlbumArtwork
import io.github.eladimany.spindle.ui.components.countLabel

/* Finding missing album covers online (mockups 9A/9B, 2026-11-02). */

private const val PRIVACY_NOTE =
    "Sends the album and artist name to Deezer, like artist photos. Saved in Spindle only — your music files aren't changed."

/** 9A: on an album without a cover. */
@Composable
fun FindCoverChip(searching: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(15.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(Color(0xD90E0B1A))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), shape)
            .clickable(enabled = !searching, role = Role.Button, onClick = onClick)
            .heightIn(min = 30.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (searching) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Default.ImageSearch, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
        }
        Text("Find cover", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}

/** 9A: "Is this the cover?" — Deezer's best match, then the next on "Not this one". [candidate] null = [message] only. */
@Composable
fun CoverConfirmSheet(
    candidate: CoverCandidate?,
    message: String?,
    saving: Boolean,
    onUse: () -> Unit,
    onNotThis: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (candidate == null) {
                Text(message ?: "No cover found", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close") }
                return@Column
            }
            Text("Is this the cover?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = candidate.coverUrl,
                    contentDescription = "Cover found for ${candidate.title}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(132.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(candidate.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(candidate.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("from Deezer", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onUse, enabled = !saving, modifier = Modifier.weight(1f)) {
                    if (saving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Use this cover")
                }
                OutlinedButton(onClick = onNotThis, enabled = !saving, modifier = Modifier.weight(1f)) { Text("Not this one") }
            }
            Text(PRIVACY_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 9B: on Albums while some albums have no cover. */
@Composable
fun MissingCoversNotice(count: Int, onFind: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(primary.copy(alpha = 0.08f))
            .border(1.dp, primary.copy(alpha = 0.35f), shape)
            .padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Default.Image, contentDescription = null, tint = primary, modifier = Modifier.size(20.dp))
        Text(
            "${countLabel(count, "album")} ${if (count == 1) "has" else "have"} no cover",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = onFind, modifier = Modifier.height(36.dp)) { Text("Find covers") }
        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Hide") }
    }
}

/** 9B: the library-wide lookup — start, progress with what was found (× drops a wrong match), done. */
@Composable
fun FindCoversSheet(
    scan: AlbumCoverFinder.Scan,
    missingCount: Int,
    fetchArtworkUri: suspend (Album) -> String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onReject: (Album) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (scan) {
                AlbumCoverFinder.Scan.Idle -> {
                    Text("Find missing covers", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "${countLabel(missingCount, "album")} ${if (missingCount == 1) "has" else "have"} no cover in the music files. " +
                            "Spindle looks each one up on Deezer — about ${(missingCount * 0.6).toInt().coerceAtLeast(1)} s. It keeps running if you close this.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(PRIVACY_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onStart, enabled = missingCount > 0, modifier = Modifier.fillMaxWidth()) { Text("Find covers") }
                }
                is AlbumCoverFinder.Scan.Running -> {
                    Text("Finding covers…", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    LinearProgressIndicator(progress = { scan.done / scan.total.coerceAtLeast(1).toFloat() }, modifier = Modifier.fillMaxWidth())
                    Text("${scan.done} of ${scan.total} · found ${scan.found.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FoundGrid(scan.found, fetchArtworkUri, onReject)
                    OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
                }
                is AlbumCoverFinder.Scan.Finished -> {
                    Text(
                        if (scan.found.isEmpty()) "No covers found" else "Found ${countLabel(scan.found.size, "cover")}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    scan.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    if (scan.notFound.isNotEmpty()) {
                        Text(
                            "Not found: " + scan.notFound.joinToString(", ") { it.name },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (scan.found.isNotEmpty()) {
                        Text("Tap × on a wrong match to drop it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FoundGrid(scan.found, fetchArtworkUri, onReject)
                    }
                    Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                }
            }
        }
    }
}

@Composable
private fun FoundGrid(found: List<Album>, fetchArtworkUri: suspend (Album) -> String?, onReject: (Album) -> Unit) {
    if (found.isEmpty()) return
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(found, key = { it.id }) { album ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box {
                    AlbumArtwork(album = album, fetchArtworkUri = fetchArtworkUri, modifier = Modifier.fillMaxWidth().aspectRatio(1f), cornerRadiusDp = 10)
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color(0xCC0E0B1A))
                            .clickable(role = Role.Button, onClickLabel = "Drop this cover") { onReject(album) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Wrong cover for ${album.name}", modifier = Modifier.size(16.dp), tint = Color.White)
                    }
                }
                Text(album.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 9B in Flow view, where there's no room for the notice: a small pill in the corner. */
@Composable
fun MissingCoversButton(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    val primary = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .clip(shape)
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), shape)
            .clickable(role = Role.Button, onClickLabel = "Find missing covers", onClick = onClick)
            .heightIn(min = 36.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Default.ImageSearch, contentDescription = null, tint = primary, modifier = Modifier.size(16.dp))
        Text(
            "$count",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = primary,
            modifier = Modifier.semantics { contentDescription = "${countLabel(count, "album")} without a cover" },
        )
    }
}
