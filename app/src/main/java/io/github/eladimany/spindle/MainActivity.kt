package io.github.eladimany.spindle

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import io.github.eladimany.spindle.ui.library.MainUiState
import io.github.eladimany.spindle.ui.library.MainViewModel
import io.github.eladimany.spindle.ui.permission.AudioPermissionScreen
import io.github.eladimany.spindle.ui.permission.audioLibraryPermission
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

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    if (hasPermission) {
                        LibrarySummary(uiState, modifier = Modifier.padding(innerPadding))
                    } else {
                        AudioPermissionScreen(
                            onRequestPermission = { launcher.launch(audioLibraryPermission) },
                            modifier = Modifier.padding(innerPadding),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibrarySummary(state: MainUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Spindle", style = MaterialTheme.typography.headlineMedium)
        if (state.isScanning) {
            CircularProgressIndicator()
            Text("Scanning… ${state.scannedCount} tracks so far")
        } else {
            Text("${state.trackCount} tracks")
            Text("${state.albumCount} albums")
            Text("${state.artistCount} artists")
            state.lastScanMs?.let { Text("Last scan: ${it}ms") }
        }
    }
}
