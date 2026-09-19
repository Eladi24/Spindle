package io.github.eladimany.spindle

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.eladimany.spindle.ui.folders.FoldersScreen
import io.github.eladimany.spindle.ui.library.MainUiState
import io.github.eladimany.spindle.ui.library.MainViewModel
import io.github.eladimany.spindle.ui.permission.AudioPermissionScreen
import io.github.eladimany.spindle.ui.permission.audioLibraryPermission
import io.github.eladimany.spindle.ui.player.PlayerScreen
import io.github.eladimany.spindle.ui.theme.SpindleTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SpindleTheme {
                var hasPermission by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(this, audioLibraryPermission) ==
                            PackageManager.PERMISSION_GRANTED,
                    )
                }
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted -> hasPermission = granted }

                LaunchedEffect(hasPermission) {
                    if (hasPermission) viewModel.onPermissionGranted()
                }

                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                var showFolders by remember { mutableStateOf(false) }
                BackHandler(enabled = showFolders) { showFolders = false }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    when {
                        !hasPermission -> AudioPermissionScreen(
                            onRequestPermission = { launcher.launch(audioLibraryPermission) },
                            modifier = Modifier.padding(innerPadding),
                        )

                        showFolders -> Column(modifier = Modifier.padding(innerPadding)) {
                            TextButton(onClick = { showFolders = false }) { Text("← Back") }
                            FoldersScreen()
                        }

                        else -> Column(modifier = Modifier.padding(innerPadding)) {
                            LibrarySummary(
                                state = uiState,
                                onManageFolders = { showFolders = true },
                            )
                            PlayerScreen(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibrarySummary(
    state: MainUiState,
    onManageFolders: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Spindle", style = MaterialTheme.typography.headlineMedium)
        if (state.isScanning) {
            CircularProgressIndicator()
            Text("Scanning… ${state.scannedCount} tracks so far")
        } else {
            Text("${state.trackCount} tracks · ${state.albumCount} albums · ${state.artistCount} artists")
        }
        TextButton(onClick = onManageFolders) { Text("Manage folders") }
    }
}
