package io.github.eladimany.spindle.ui.trackinfo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.eladimany.spindle.data.db.entity.TrackEntity
import io.github.eladimany.spindle.data.trackinfo.TrackInfoFixer
import io.github.eladimany.spindle.ui.components.glow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/** Seconds per track for the estimate: one search, sometimes a second, plus artist genres. */
private const val SECONDS_PER_TRACK = 1.5

private fun estimate(tracks: Int): String {
    val seconds = (tracks * SECONDS_PER_TRACK).toInt().coerceAtLeast(2)
    return if (seconds < 90) "About $seconds seconds" else "About ${(seconds + 30) / 60} min"
}

/** Mockup screen 3: what the MusicBrainz check sends and does, before it starts. */
@Composable
fun LookupSheet(splitCount: Int, fillInCount: Int, onStart: (splits: Boolean, fillIn: Boolean) -> Unit, onDismiss: () -> Unit) {
    var splits by remember { mutableStateOf(splitCount > 0) }
    var fillIn by remember { mutableStateOf(splitCount == 0 && fillInCount > 0) }
    val total = (if (splits) splitCount else 0) + (if (fillIn) fillInCount else 0)
    val colors = MaterialTheme.colorScheme

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Check with MusicBrainz", style = MaterialTheme.typography.headlineSmall, letterSpacing = (-0.3).sp)
                Text(
                    "A free, open music database. It tells Spindle which side is the artist and what album and year the song is from.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            Point(Icons.AutoMirrored.Outlined.ArrowForward, "Sends only the words", " — artist and title text. Never audio, file names or anything else.")
            Point(Icons.Outlined.Schedule, estimate(total.coerceAtLeast(1)), " — one request per second, as MusicBrainz asks. Keep using Spindle meanwhile.")
            Point(Icons.Outlined.Check, "You review before anything changes", " — nothing is applied by itself.")

            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp)),
            ) {
                if (splitCount > 0) {
                    Choice(
                        title = if (splitCount == 1) "The track above" else "The $splitCount tracks above",
                        subtitle = "Confirm the artist, add album · year · genre",
                        checked = splits,
                        onChange = { splits = it },
                    )
                }
                if (splitCount > 0 && fillInCount > 0) HorizontalDivider(Modifier.padding(horizontal = 14.dp))
                if (fillInCount > 0) {
                    Choice(
                        title = "Also $fillInCount tracks missing year or genre",
                        subtitle = "Better smart shuffle and AI playlists · ${estimate(fillInCount).lowercase()}",
                        checked = fillIn,
                        onChange = { fillIn = it },
                    )
                }
            }

            Button(
                onClick = { onStart(splits, fillIn) },
                enabled = total > 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .then(if (total > 0) Modifier.glow(colors.primary.copy(alpha = 0.35f), radius = 16.dp, cornerRadius = 28.dp) else Modifier),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
            ) { Text(if (total == 1) "Look up 1 track" else "Look up $total tracks", fontWeight = FontWeight.Bold, fontSize = 17.sp) }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
        }
    }
}

@Composable
private fun Point(icon: ImageVector, bold: String, rest: String) {
    val colors = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
        Text(
            androidx.compose.ui.text.buildAnnotatedString {
                pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Medium))
                append(bold)
                pop()
                pushStyle(androidx.compose.ui.text.SpanStyle(color = colors.onSurfaceVariant))
                append(rest)
                pop()
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun Choice(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Checkbox(checked = checked, onCheckedChange = onChange)
    }
}

// ---- Screen 5: one track by hand --------------------------------------------------------

data class TrackInfoForm(
    val track: TrackEntity? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val year: String = "",
    val genre: String = "",
    val hasFix: Boolean = false,
    val busy: Boolean = false,
    val lookingUp: Boolean = false,
    val message: String? = null,
    val done: Boolean = false,
)

@HiltViewModel
class TrackInfoViewModel @Inject constructor(private val fixer: TrackInfoFixer) : ViewModel() {
    private val _form = MutableStateFlow(TrackInfoForm())
    val form: StateFlow<TrackInfoForm> = _form.asStateFlow()

    /** Called each time the sheet opens; always reloads (the view model outlives the sheet). */
    fun load(trackId: Long) {
        _form.value = TrackInfoForm()
        viewModelScope.launch {
            val track = fixer.track(trackId) ?: return@launch
            val fix = fixer.fixFor(track.fileKey)
            _form.value = TrackInfoForm(
                track = track,
                title = track.title,
                // For a missing tag the library shows "Unknown artist" / the folder's name —
                // placeholders, not values: those fields start empty unless a fix filled them.
                artist = fix?.artist ?: if (track.fileArtistMissing) "" else track.artistName,
                album = fix?.album ?: if (track.fileArtistMissing || track.albumName == "Unknown album") "" else track.albumName,
                year = track.year?.toString().orEmpty(),
                genre = track.genre.orEmpty(),
                hasFix = fix != null,
            )
        }
    }

    fun edit(change: (TrackInfoForm) -> TrackInfoForm) = _form.update { change(it).copy(message = null) }

    /** Fills the empty fields from MusicBrainz, using the artist and title typed so far. */
    fun lookUp() {
        val f = _form.value
        if (f.artist.isBlank() || f.title.isBlank()) {
            _form.update { it.copy(message = "Type the artist and title first.") }
            return
        }
        _form.update { it.copy(lookingUp = true, message = null) }
        viewModelScope.launch {
            val result = try {
                fixer.lookupOne(f.artist.trim(), f.title.trim())
            } catch (e: IOException) {
                _form.update { it.copy(lookingUp = false, message = "Couldn't reach MusicBrainz. Check the internet connection.") }
                return@launch
            }
            _form.update { current ->
                if (result == null) {
                    current.copy(lookingUp = false, message = "MusicBrainz doesn't know this song by that artist.")
                } else {
                    val m = result.match
                    current.copy(
                        artist = m.artist,
                        title = m.title,
                        album = current.album.ifBlank { m.album.orEmpty() },
                        year = current.year.ifBlank { m.year?.toString().orEmpty() },
                        genre = current.genre.ifBlank { result.genre.orEmpty() },
                        lookingUp = false,
                        message = if (result.swapped) "Turned around — MusicBrainz knows ${m.artist} as the artist." else "Filled in from MusicBrainz.",
                    )
                }
            }
        }
    }

    fun save() {
        val f = _form.value
        val track = f.track ?: return
        val year = f.year.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
        if (f.year.isNotBlank() && (year == null || year !in 1000..2999)) {
            _form.update { it.copy(message = "The year should be four digits, like 1972.") }
            return
        }
        _form.update { it.copy(busy = true) }
        viewModelScope.launch {
            fixer.saveManual(track, f.title, f.artist, f.album, year, f.genre)
            _form.update { it.copy(busy = false, done = true) }
        }
    }

    fun doneHandled() = _form.update { it.copy(done = false) }

    fun useFileInfo() {
        val track = _form.value.track ?: return
        _form.update { it.copy(busy = true) }
        viewModelScope.launch {
            fixer.useFileInfo(track)
            _form.update { it.copy(busy = false, done = true) }
        }
    }
}

/** Mockup screen 5. From the track menu ("Track info") or the review screen's "Edit". */
@Composable
fun TrackInfoSheet(trackId: Long, onDismiss: () -> Unit, viewModel: TrackInfoViewModel = hiltViewModel(key = "track-info-$trackId")) {
    LaunchedEffect(trackId) { viewModel.load(trackId) }
    val form by viewModel.form.collectAsStateWithLifecycle()
    LaunchedEffect(form.done) {
        if (form.done) {
            // Cleared as it's handled: the view model outlives the sheet, and a leftover
            // `done` would close the sheet the next time this track's info is opened.
            viewModel.doneHandled()
            onDismiss()
        }
    }
    val colors = MaterialTheme.colorScheme

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        val track = form.track
        if (track == null) {
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@ModalBottomSheet
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Track info", style = MaterialTheme.typography.headlineSmall, letterSpacing = (-0.3).sp)
                Text(
                    "File: ${track.folderName}/${track.fileName} · ${formatDuration(track.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Field("Title", form.title) { v -> viewModel.edit { it.copy(title = v) } }
            Field("Artist", form.artist, placeholder = "Unknown artist") { v -> viewModel.edit { it.copy(artist = v) } }
            Field("Album", form.album, placeholder = "Unknown album") { v -> viewModel.edit { it.copy(album = v) } }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Year", form.year, modifier = Modifier.weight(1f), numeric = true) { v -> viewModel.edit { it.copy(year = v.filter(Char::isDigit).take(4)) } }
                Field("Genre", form.genre, modifier = Modifier.weight(1f)) { v -> viewModel.edit { it.copy(genre = v) } }
            }
            TextButton(onClick = viewModel::lookUp, enabled = !form.lookingUp && !form.busy, modifier = Modifier.fillMaxWidth()) {
                if (form.lookingUp) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.TravelExplore, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Text("Look up the rest on MusicBrainz", modifier = Modifier.padding(start = 8.dp))
            }
            form.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
            Button(
                onClick = viewModel::save,
                enabled = !form.busy && form.title.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .glow(colors.primary.copy(alpha = 0.35f), radius = 16.dp, cornerRadius = 28.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
            ) {
                if (form.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.onPrimary)
                else Text("Save", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
            if (form.hasFix) {
                TextButton(onClick = viewModel::useFileInfo, enabled = !form.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Use the file's info", color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    placeholder: String? = null,
    numeric: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
    )
}

private fun formatDuration(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
