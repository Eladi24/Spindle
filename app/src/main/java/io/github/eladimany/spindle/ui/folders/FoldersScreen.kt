package io.github.eladimany.spindle.ui.folders

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.eladimany.spindle.core.model.Folder

@Composable
fun FoldersScreen(
    modifier: Modifier = Modifier,
    viewModel: FoldersViewModel = hiltViewModel(),
) {
    val folders by viewModel.folders.collectAsStateWithLifecycle()

    LazyColumn(modifier = modifier.fillMaxWidth()) {
        item {
            Text(
                text = "Choose which folders are part of your library. Turn off " +
                    "chat-app voice notes, ringtones, or anything else that isn't music.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
        items(folders, key = { it.id }) { folder ->
            FolderRow(folder, onToggle = { included -> viewModel.setFolderExcluded(folder.id, !included) })
            HorizontalDivider()
        }
    }
}

@Composable
private fun FolderRow(folder: Folder, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(folder.name, style = MaterialTheme.typography.bodyLarge)
            Text("${folder.trackCount} tracks", style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = !folder.isExcluded, onCheckedChange = onToggle)
    }
}
