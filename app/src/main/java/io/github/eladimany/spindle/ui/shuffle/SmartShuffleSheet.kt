package io.github.eladimany.spindle.ui.shuffle

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.eladimany.spindle.playback.SmartShuffleRules
import io.github.eladimany.spindle.ui.components.SparkleIcon
import io.github.eladimany.spindle.ui.components.glow

/** What "smart" means, and the four rules behind it. Opened by long-pressing either smart shuffle control. */
@Composable
fun SmartShuffleSheet(
    onDismiss: () -> Unit,
    viewModel: SmartShuffleViewModel = hiltViewModel(),
) {
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val listenCount by viewModel.listenCount.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshListenCount() }

    val colors = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(SparkleIcon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
                Text("Smart shuffle", style = MaterialTheme.typography.headlineSmall)
            }
            Text(
                "Learns from what you play. Everything stays on your phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .background(colors.surfaceContainer)
                    .border(1.dp, Color.White.copy(alpha = 0.06f), MaterialTheme.shapes.large),
            ) {
                RuleRow(
                    title = "Spread out artists",
                    description = "No two songs by the same artist or from the same album back to back",
                    checked = rules.spreadArtists,
                    onCheckedChange = { viewModel.setRules(rules.copy(spreadArtists = it)) },
                )
                RuleDivider()
                RuleRow(
                    title = "More of your favourites",
                    description = "Songs you usually play to the end come up sooner",
                    checked = rules.favourites,
                    onCheckedChange = { viewModel.setRules(rules.copy(favourites = it)) },
                )
                RuleDivider()
                RuleRow(
                    title = "Hold back skipped songs",
                    description = "Songs you often skip wait until later in the queue",
                    checked = rules.holdBackSkipped,
                    onCheckedChange = { viewModel.setRules(rules.copy(holdBackSkipped = it)) },
                )
                RuleDivider()
                RuleRow(
                    title = "Rediscover",
                    description = "Mix in songs you haven't heard in a long time",
                    checked = rules.rediscover,
                    onCheckedChange = { viewModel.setRules(rules.copy(rediscover = it)) },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    buildString {
                        append("Every song still plays once before anything repeats.")
                        listenCount?.let { append(" Learning from ${listensLabel(it)} so far.") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }

            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .glow(colors.primary.copy(alpha = 0.18f), radius = 14.dp, cornerRadius = 25.dp),
                shape = RoundedCornerShape(25.dp),
                border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.45f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = colors.primary.copy(alpha = 0.14f),
                    contentColor = colors.primary,
                ),
            ) { Text("Done", fontWeight = FontWeight.SemiBold) }
        }
    }
}

private fun listensLabel(count: Int) = if (count == 1) "1 listen" else "$count listens"

@Composable
private fun RuleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The whole row toggles; the switch is just the indicator.
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun RuleDivider() {
    HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
}
