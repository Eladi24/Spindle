package io.github.eladimany.spindle.ui.trackinfo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.eladimany.spindle.data.trackinfo.FixRow
import io.github.eladimany.spindle.data.trackinfo.FixSession
import io.github.eladimany.spindle.data.trackinfo.FixSource
import io.github.eladimany.spindle.data.trackinfo.NothingRow
import io.github.eladimany.spindle.data.trackinfo.RowLookup
import io.github.eladimany.spindle.data.trackinfo.TrackInfoFixer
import io.github.eladimany.spindle.ui.components.LocalBottomOverlayPadding
import io.github.eladimany.spindle.ui.components.glow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class TrackInfoFixViewModel @Inject constructor(private val fixer: TrackInfoFixer) : ViewModel() {
    val session: StateFlow<FixSession?> = fixer.session
    val fillInCount: StateFlow<Int> = fixer.fillInCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init {
        fixer.load()
    }

    fun reload() = fixer.load()
    fun toggle(trackId: Long) = fixer.toggle(trackId)
    fun swap(trackId: Long) = fixer.swap(trackId)
    fun selectAll(selected: Boolean) = fixer.selectAll(selected)
    fun startLookup(includeSplits: Boolean, alsoFillIn: Boolean) = fixer.startLookup(includeSplits, alsoFillIn)
    fun stopLookup() = fixer.stopLookup()
    fun apply() = fixer.apply()
}

/** Mockup screens 2 + 4: review the suggested fixes, before and after a MusicBrainz lookup. */
@Composable
fun TrackInfoFixScreen(onBack: () -> Unit, viewModel: TrackInfoFixViewModel = hiltViewModel()) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val fillInCount by viewModel.fillInCount.collectAsStateWithLifecycle()
    var showLookupSheet by remember { mutableStateOf(false) }
    var editTrackId by remember { mutableStateOf<Long?>(null) }
    val colors = MaterialTheme.colorScheme

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        bottomBar = {
            session?.let { s ->
                BottomActions(
                    session = s,
                    // Offered again after a lookup that couldn't reach MusicBrainz.
                    canLookUp = (s.lookup == null || (s.lookup.error != null && !s.lookup.running)) &&
                        (s.splits.any { it.selected } || fillInCount > 0),
                    onLookUp = { showLookupSheet = true },
                    onApply = viewModel::apply,
                )
            }
        },
    ) { innerPadding ->
        val s = session
        if (s == null) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 4.dp).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Fix track info", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp)
                    Text(
                        "Saved in Spindle only — your music files stay exactly as they are.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            if (s.lookup != null) {
                item { LookupProgressCard(s, onStop = viewModel::stopLookup) }
            }
            if (s.splits.isEmpty() && s.nothing.isEmpty() && s.fillIns.isEmpty()) {
                item {
                    Text(
                        if (fillInCount > 0) "Every track has an artist. $fillInCount tracks are missing a year or genre — MusicBrainz can fill those in."
                        else "Nothing to fix — every track has an artist.",
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            if (s.splits.isNotEmpty()) {
                item {
                    SectionHeader("ARTIST FOUND IN THE TITLE · ${s.splits.size}") {
                        val allSelected = s.splits.all { it.selected }
                        TextButton(onClick = { viewModel.selectAll(!allSelected) }) { Text(if (allSelected) "Select none" else "Select all") }
                    }
                }
                items(s.splits, key = { "s${it.trackId}" }) { row ->
                    FixRowCard(row, showSwap = true, onToggle = { viewModel.toggle(row.trackId) }, onSwap = { viewModel.swap(row.trackId) })
                }
            }
            if (s.fillIns.isNotEmpty()) {
                item { SectionHeader("YEAR AND GENRE FROM MUSICBRAINZ · ${s.fillIns.size}") }
                items(s.fillIns, key = { "f${it.trackId}" }) { row ->
                    FixRowCard(row, showSwap = false, onToggle = { viewModel.toggle(row.trackId) }, onSwap = {})
                }
            }
            if (s.nothing.isNotEmpty()) {
                item { SectionHeader("NOTHING TO GO ON · ${s.nothing.size}") }
                items(s.nothing, key = { "n${it.trackId}" }) { row -> NothingRowCard(row, onEdit = { editTrackId = row.trackId }) }
            }
        }
    }

    if (showLookupSheet) {
        session?.let { s ->
            LookupSheet(
                splitCount = s.splits.count { it.selected },
                fillInCount = fillInCount,
                onStart = { splits, fillIn ->
                    viewModel.startLookup(splits, fillIn)
                    showLookupSheet = false
                },
                onDismiss = { showLookupSheet = false },
            )
        }
    }
    editTrackId?.let { id ->
        TrackInfoSheet(trackId = id, onDismiss = {
            editTrackId = null
            viewModel.reload()
        })
    }
}

@Composable
private fun SectionHeader(text: String, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            letterSpacing = 0.6.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        trailing()
    }
}

/** One suggestion: what it was read from, the result, and MusicBrainz's word on it. */
@Composable
private fun FixRowCard(row: FixRow, showSwap: Boolean, onToggle: () -> Unit, onSwap: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val found = row.lookup as? RowLookup.Found
    val warn = !row.sure || found?.swapped == true
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (warn) colors.tertiary.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.04f))
            .border(1.dp, if (warn) colors.tertiary.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.08f), shape)
            .clickable(onClick = onToggle)
            .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = row.selected, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (row.readFrom.isNotEmpty()) {
                Text(
                    if (row.source == FixSource.FILE_NAME) "File name “${row.readFrom}”" else "“${row.readFrom}”",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Text(row.title, style = MaterialTheme.typography.titleSmall)
            Text("by ${row.artist}", style = MaterialTheme.typography.bodyMedium, color = colors.primary)
            when {
                found?.swapped == true -> Note("Turned around — MusicBrainz knows ${row.artist} as the artist.", colors.tertiary)
                !row.sure -> Note("Which side is the artist? Tap ⇅ to swap.", colors.tertiary)
                row.lookup == RowLookup.NotFound -> Note("Not found on MusicBrainz — kept as read from the title.", colors.onSurfaceVariant)
            }
            if (found != null) {
                val chips = listOfNotNull(found.album, found.year?.toString(), found.genre)
                if (chips.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        chips.forEach { InfoChip(it) }
                    }
                }
            }
        }
        if (showSwap) {
            IconButton(onClick = onSwap) {
                Icon(Icons.Outlined.SwapVert, contentDescription = "Swap artist and title", tint = if (warn) colors.tertiary else colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Note(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
private fun InfoChip(text: String) {
    Text(
        text,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .padding(horizontal = 9.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun NothingRowCard(row: NothingRow, onEdit: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.title, style = MaterialTheme.typography.titleSmall)
            Text("${row.folderName} · ${row.fileName}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        OutlinedButton(onClick = onEdit) { Text("Edit") }
    }
}

/** Screen 4's top card: progress while looking up, then what MusicBrainz said. */
@Composable
private fun LookupProgressCard(s: FixSession, onStop: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val lookup = s.lookup ?: return
    val all = s.splits + s.fillIns
    val matched = all.count { (it.lookup as? RowLookup.Found)?.swapped == false }
    val turned = all.count { (it.lookup as? RowLookup.Found)?.swapped == true }
    val notFound = s.splits.count { it.lookup == RowLookup.NotFound }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (lookup.running) "MusicBrainz: ${lookup.done} of ${lookup.total}" else "MusicBrainz checked ${lookup.done} of ${lookup.total}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
            if (lookup.running) TextButton(onClick = onStop) { Text("Stop") }
            else if (lookup.error == null) Text("Done", color = colors.primary, style = MaterialTheme.typography.labelLarge)
        }
        LinearProgressIndicator(
            progress = { if (lookup.total == 0) 1f else lookup.done.toFloat() / lookup.total },
            modifier = Modifier.fillMaxWidth(),
        )
        if (lookup.running) {
            Text("You can leave this screen — it keeps going.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        lookup.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.tertiary) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (matched > 0) InfoChip("$matched matched")
            if (turned > 0) InfoChip("$turned turned around")
            if (notFound > 0) InfoChip("$notFound not found")
        }
    }
}

@Composable
private fun BottomActions(session: FixSession, canLookUp: Boolean, onLookUp: () -> Unit, onApply: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val running = session.lookup?.running == true
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface.copy(alpha = 0.96f))
            // The nav host has already consumed the navigation-bar inset, so read the raw
            // one; while something plays, the mini-player's overlay is taller.
            .padding(bottom = maxOf(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(), LocalBottomOverlayPadding.current))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (canLookUp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.primary.copy(alpha = 0.06f))
                    .border(1.dp, colors.primary.copy(alpha = 0.22f), RoundedCornerShape(16.dp))
                    .clickable(onClick = onLookUp)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Outlined.TravelExplore, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f)) {
                    Text("Check with MusicBrainz first", style = MaterialTheme.typography.titleSmall)
                    Text("Confirms who's the artist and adds album, year, genre", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.onSurfaceVariant)
            }
        }
        // Only "Nothing to go on" left (fixed one by one with Edit): no Apply button.
        if (session.splits.isEmpty() && session.fillIns.isEmpty()) return@Column
        val count = session.selectedCount
        val enabled = count > 0 && !running && !session.applying
        Button(
            onClick = onApply,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .then(if (enabled) Modifier.glow(colors.primary.copy(alpha = 0.35f), radius = 16.dp, cornerRadius = 28.dp) else Modifier),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
        ) {
            if (session.applying) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.onPrimary)
            } else {
                Text(if (count == 1) "Apply 1 fix" else "Apply $count fixes", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
        }
    }
}
