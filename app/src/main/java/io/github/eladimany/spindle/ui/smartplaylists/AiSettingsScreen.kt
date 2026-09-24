package io.github.eladimany.spindle.ui.smartplaylists

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import io.github.eladimany.spindle.data.smartplaylists.AiEngineStatus
import io.github.eladimany.spindle.data.smartplaylists.AiPlaylistEngine
import io.github.eladimany.spindle.ui.components.edgeGlint
import io.github.eladimany.spindle.ui.components.glow
import io.github.eladimany.spindle.ui.components.rememberGlintAngle
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class AiSettingsViewModel @Inject constructor(private val engine: AiPlaylistEngine) : ViewModel() {
    val status: StateFlow<AiEngineStatus> = engine.status

    fun refresh() {
        viewModelScope.launch { engine.refreshStatus() }
    }

    fun download() = engine.startDownload()
}

/**
 * Mockup screen E, "Where the AI runs". Only the on-device engine exists so far; the
 * bring-your-own-key card joins when a cloud engine is built.
 */
@Composable
fun AiSettingsScreen(onBack: () -> Unit, viewModel: AiSettingsViewModel = hiltViewModel()) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
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
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "AI playlists",
                modifier = Modifier.padding(horizontal = 4.dp),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.8).sp,
            )
            Text(
                "Choose where the AI thinks.",
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            OnDeviceCard(status = status, onDownload = viewModel::download)
            Text(
                "Without on-device AI, \"Build a playlist\" picks by era and genre from your tags instead.",
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OnDeviceCard(status: AiEngineStatus, onDownload: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val angle by rememberGlintAngle(periodMs = 6_000)
    val active = status == AiEngineStatus.Ready
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceContainerLow)
            .then(
                if (active) Modifier.edgeGlint({ angle }, cornerRadius = 24.dp, glint = colors.primary, glintTail = colors.tertiary)
                else Modifier.border(1.dp, Color.White.copy(alpha = 0.10f), shape),
            )
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("On this phone", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            StatusBadge(status)
        }
        Text(
            when (status) {
                AiEngineStatus.Ready -> "Gemini Nano, built into Android."
                AiEngineStatus.Downloadable -> "This phone supports Gemini Nano. Android downloads the model once; Spindle's size doesn't change."
                is AiEngineStatus.Downloading -> "Android is fetching the model. Keep using Spindle."
                AiEngineStatus.Unavailable -> "This phone doesn't have Gemini Nano (or Android hasn't enabled it)."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        when (status) {
            AiEngineStatus.Ready -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Free", "Private", "Works offline").forEach { Tag(it) }
            }
            AiEngineStatus.Downloadable -> Button(
                onClick = onDownload,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .glow(colors.primary.copy(alpha = 0.35f), radius = 14.dp, cornerRadius = 24.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
            ) { Text("Download on-device AI", fontWeight = FontWeight.Bold) }
            is AiEngineStatus.Downloading -> {
                val progress = status.progress
                if (progress == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            }
            AiEngineStatus.Unavailable -> Unit
        }
    }
}

@Composable
private fun StatusBadge(status: AiEngineStatus) {
    val colors = MaterialTheme.colorScheme
    val (label, color) = when (status) {
        AiEngineStatus.Ready -> "Ready" to colors.primary
        AiEngineStatus.Downloadable -> "Not downloaded" to colors.onSurfaceVariant
        is AiEngineStatus.Downloading ->
            (status.progress?.let { "Downloading ${(it * 100).roundToInt()}%" } ?: "Downloading…") to colors.primary
        AiEngineStatus.Unavailable -> "Not available" to colors.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(8.dp)
                .then(if (status == AiEngineStatus.Ready) Modifier.glow(color.copy(alpha = 0.7f), radius = 6.dp, cornerRadius = 4.dp) else Modifier)
                .clip(CircleShape)
                .background(color),
        )
        Text(label, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

@Composable
private fun Tag(text: String) {
    Text(
        text,
        modifier = Modifier
            .clip(RoundedCornerShape(13.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(13.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium,
    )
}
