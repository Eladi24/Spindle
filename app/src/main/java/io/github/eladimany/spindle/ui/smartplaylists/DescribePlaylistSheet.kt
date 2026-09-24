package io.github.eladimany.spindle.ui.smartplaylists

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.eladimany.spindle.data.smartplaylists.PlaylistCriteria
import io.github.eladimany.spindle.data.smartplaylists.PlaylistLength
import io.github.eladimany.spindle.ui.components.TwinkleSparkle
import io.github.eladimany.spindle.ui.components.edgeGlint
import io.github.eladimany.spindle.ui.components.glow
import io.github.eladimany.spindle.ui.components.rememberGlintAngle

/** Ideas under the text box; tapping one fills it in. */
val RequestSuggestions = listOf("Songs I used to love", "Sunday morning", "Loud and fast", "Late-night focus")

/**
 * Mockup screen A's sheet: say what you want to hear. [onMake] gets the request and the
 * length/history choices; the caller hands them to the generator and closes the sheet —
 * the answer arrives in the background (the in-progress row on Playlists).
 */
@Composable
fun DescribePlaylistSheet(
    initialText: String,
    onDismiss: () -> Unit,
    onMake: (request: String, default: PlaylistCriteria) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by rememberSaveable { mutableStateOf(initialText) }
    var length by rememberSaveable { mutableStateOf(PlaylistLength.ONE_HOUR) }
    var lean by rememberSaveable { mutableStateOf(true) }
    val angle by rememberGlintAngle(periodMs = 4_000)
    val canMake = text.isNotBlank()
    fun make() {
        if (text.isNotBlank()) onMake(text.trim(), PlaylistCriteria(length = length, leanOnHistory = lean))
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TwinkleSparkle(size = 30.dp)
                Text("What should it sound like?", style = MaterialTheme.typography.headlineSmall, letterSpacing = (-0.4).sp)
            }

            TextField(
                value = text,
                // Multi-line so long requests wrap, but Enter sends rather than adding a line
                // (some keyboards type a newline even with imeAction = Done).
                onValueChange = { value ->
                    text = value.replace("\n", "").take(300)
                    if ('\n' in value) make()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 104.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .edgeGlint({ angle }, cornerRadius = 22.dp, glint = colors.primary, glintTail = colors.tertiary, width = 1.5.dp),
                placeholder = { Text("mellow 90s for a rainy evening…") },
                textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal, lineHeight = 26.sp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { make() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = colors.surfaceContainerLowest,
                    unfocusedContainerColor = colors.surfaceContainerLowest,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RequestSuggestions.forEach { idea ->
                    FilterPill(label = idea, selected = false, onClick = { text = idea })
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "LENGTH",
                    style = MaterialTheme.typography.labelMedium,
                    letterSpacing = 0.6.sp,
                    color = colors.onSurfaceVariant,
                )
                LengthSegments(selected = length, onSelect = { length = it })
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
                    .toggleable(value = lean, role = Role.Switch, onValueChange = { lean = it })
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
                Switch(checked = lean, onCheckedChange = null)
            }

            Button(
                onClick = ::make,
                enabled = canMake,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .then(if (canMake) Modifier.glow(colors.primary.copy(alpha = 0.4f), radius = 18.dp, cornerRadius = 28.dp) else Modifier),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
            ) { Text("Make it", fontWeight = FontWeight.Bold, fontSize = 17.sp) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(14.dp))
                Text(
                    "On-device AI. Nothing leaves your phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}
