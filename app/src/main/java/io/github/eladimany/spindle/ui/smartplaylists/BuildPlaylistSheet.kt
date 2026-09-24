package io.github.eladimany.spindle.ui.smartplaylists

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.eladimany.spindle.data.smartplaylists.AiEngineStatus
import io.github.eladimany.spindle.data.smartplaylists.PlaylistCriteria
import io.github.eladimany.spindle.data.smartplaylists.PlaylistLength
import io.github.eladimany.spindle.ui.components.glow

/** Genres shown before "All genres…" — the rest are one tap away. */
private const val COLLAPSED_GENRES = 8

/**
 * The chip builder (mockup screen B): era, genre, length, history — no AI. The
 * fallback on phones without on-device AI, and the "Adjust" sheet on a draft.
 * [onMade] runs once the draft exists (in [io.github.eladimany.spindle.data.smartplaylists.SmartPlaylistGenerator.draft]).
 */
@Composable
fun BuildPlaylistSheet(
    initial: PlaylistCriteria?,
    onDismiss: () -> Unit,
    onMade: () -> Unit,
    viewModel: BuildPlaylistViewModel = hiltViewModel(key = "build-playlist"),
) {
    LaunchedEffect(Unit) { viewModel.start(initial) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val engineStatus by viewModel.engineStatus.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showAllGenres by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Build a playlist", style = MaterialTheme.typography.headlineSmall, letterSpacing = (-0.3).sp)

            if (engineStatus is AiEngineStatus.Unavailable) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.04f))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Outlined.Info, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Text(
                        "No on-device AI on this phone, so this uses your tags.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }

            val facets = state.facets
            if (facets == null) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Section("ERA") {
                    if (facets.decades.isEmpty()) {
                        EmptyNote("Your tracks have no year tags.")
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            facets.decades.forEach { facet ->
                                FilterPill(
                                    label = PlaylistCriteria.decadeLabel(facet.value),
                                    selected = facet.value in state.criteria.decades,
                                    onClick = { viewModel.toggleDecade(facet.value) },
                                )
                            }
                        }
                    }
                }

                Section("GENRE · FROM YOUR TAGS") {
                    if (facets.genres.isEmpty()) {
                        EmptyNote("Your tracks have no genre tags.")
                    } else {
                        val selectedKeys = state.criteria.genres
                        val shown = if (showAllGenres) {
                            facets.genres
                        } else {
                            // Keep selected genres visible even when collapsed.
                            facets.genres.take(COLLAPSED_GENRES) +
                                facets.genres.drop(COLLAPSED_GENRES).filter { it.value in selectedKeys }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            shown.forEach { facet ->
                                FilterPill(
                                    label = "${facet.value} · ${facet.count}",
                                    selected = facet.value in selectedKeys,
                                    onClick = { viewModel.toggleGenre(facet.value) },
                                )
                            }
                            if (!showAllGenres && facets.genres.size > COLLAPSED_GENRES) {
                                FilterPill(
                                    label = "All genres (${facets.genres.size})…",
                                    selected = false,
                                    dashed = true,
                                    onClick = { showAllGenres = true },
                                )
                            }
                        }
                    }
                }

                Section("LENGTH") {
                    LengthSegments(selected = state.criteria.length, onSelect = viewModel::setLength)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.White.copy(alpha = 0.04f))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
                        .toggleable(
                            value = state.criteria.leanOnHistory,
                            role = Role.Switch,
                            onValueChange = viewModel::setLeanOnHistory,
                        )
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Lean on my listening", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "More of what you finish, less of what you skip",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Switch(checked = state.criteria.leanOnHistory, onCheckedChange = null)
                }

                val canMake = state.matchCount > 0 && !state.making
                Button(
                    onClick = { viewModel.make(onMade) },
                    enabled = canMake,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .then(if (canMake) Modifier.glow(colors.primary.copy(alpha = 0.4f), radius = 18.dp, cornerRadius = 28.dp) else Modifier),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
                ) {
                    if (state.making) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = colors.onPrimary, strokeWidth = 2.dp)
                    } else {
                        Text(
                            if (state.matchCount == 0) "No tracks match" else "Make it · ${tracksLabel(state.matchCount)} match",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                        )
                    }
                }
            }
        }
    }
}

private fun tracksLabel(count: Int) = if (count == 1) "1 track" else "$count tracks"

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            letterSpacing = 0.6.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A glassy round filter chip; selected ones take the primary tint and a soft glow. */
@Composable
internal fun FilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dashed: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .then(if (selected) Modifier.glow(colors.primary.copy(alpha = 0.22f), radius = 10.dp, cornerRadius = 18.dp) else Modifier)
            .clip(shape)
            .background(if (selected) colors.primary.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f))
            .border(
                BorderStroke(1.dp, if (selected) colors.primary.copy(alpha = 0.5f) else Color.White.copy(alpha = if (dashed) 0.22f else 0.12f)),
                shape,
            )
            .selectable(selected = selected, role = Role.Checkbox, onClick = onClick)
            .height(36.dp)
            .padding(start = 16.dp, end = if (trailing != null) 10.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                selected -> colors.onPrimaryContainer
                dashed -> colors.primary
                else -> colors.onSurface
            },
        )
        trailing?.invoke()
    }
}

@Composable
private fun LengthSegments(selected: PlaylistLength, onSelect: (PlaylistLength) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color.Black.copy(alpha = 0.3f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(22.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PlaylistLength.entries.forEach { length ->
            val isSelected = length == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (isSelected) colors.primary.copy(alpha = 0.18f) else Color.Transparent)
                    .border(
                        1.dp,
                        if (isSelected) colors.primary.copy(alpha = 0.45f) else Color.Transparent,
                        RoundedCornerShape(18.dp),
                    )
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(length) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    length.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                )
            }
        }
    }
}

