package io.github.eladimany.spindle.ui.permission

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.eladimany.spindle.ui.theme.SpindleTheme

@Composable
fun AudioPermissionScreen(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = "Spindle needs access to your music",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "Spindle reads the songs already on your phone so it can build " +
                "your library. Nothing leaves your device.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onRequestPermission) {
            Text("Grant access")
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AudioPermissionScreenPreview() {
    SpindleTheme {
        AudioPermissionScreen(onRequestPermission = {})
    }
}
