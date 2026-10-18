package io.github.eladimany.spindle.ui.smartplaylists

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.eladimany.spindle.data.smartplaylists.AiEngineStatus
import io.github.eladimany.spindle.data.smartplaylists.GeminiApi
import io.github.eladimany.spindle.data.smartplaylists.GeminiKeyEngine
import io.github.eladimany.spindle.data.smartplaylists.GeminiNanoEngine
import io.github.eladimany.spindle.data.smartplaylists.KeyCheck
import io.github.eladimany.spindle.ui.components.edgeGlint
import io.github.eladimany.spindle.ui.components.glow
import io.github.eladimany.spindle.ui.components.rememberGlintAngle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/** A key check's progress, in the "Found your key" / "Paste your key" sheet. */
sealed interface CheckState {
    data object Checking : CheckState
    data object Works : CheckState
    data class Failed(val message: String) : CheckState
}

/** Where the free-key setup is (mockup F2–F3); null when no sheet is open. */
sealed interface KeySetup {
    /** F2: the three steps, before AI Studio opens. */
    data object Guide : KeySetup

    /**
     * AI Studio is open in the browser. [leftApp] flips once the sheet loses window focus,
     * so the clipboard is read on the way *back* — not the instant the button is tapped.
     */
    data class WaitingForCopy(val leftApp: Boolean = false) : KeySetup

    /** F3: a key to check. [fromClipboard] shows it masked; otherwise a paste field. */
    data class Entry(
        val text: String,
        val fromClipboard: Boolean,
        val check: CheckState? = null,
        val note: String? = null,
    ) : KeySetup
}

@HiltViewModel
class AiSettingsViewModel @Inject constructor(
    private val nano: GeminiNanoEngine,
    private val cloud: GeminiKeyEngine,
) : ViewModel() {
    /** Nano's own status: the key card only appears when this is Unavailable. */
    val nanoStatus: StateFlow<AiEngineStatus> = nano.status

    val maskedKey: StateFlow<String?> = cloud.key
        .map { it?.let(GeminiApi::mask) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _setup = MutableStateFlow<KeySetup?>(null)
    val setup: StateFlow<KeySetup?> = _setup.asStateFlow()

    private var checkJob: Job? = null

    fun refresh() {
        viewModelScope.launch { nano.refreshStatus() }
    }

    fun download() = nano.startDownload()

    fun startGuide() {
        _setup.value = KeySetup.Guide
    }

    fun openedKeyPage() {
        _setup.value = KeySetup.WaitingForCopy()
    }

    fun leftApp() {
        if (_setup.value is KeySetup.WaitingForCopy) _setup.value = KeySetup.WaitingForCopy(leftApp = true)
    }

    /** Back from AI Studio: use the copied key if there is one, else ask for a paste. */
    fun returned(clipboard: CharSequence?) {
        val key = GeminiApi.findKey(clipboard)
        if (key != null) {
            _setup.value = KeySetup.Entry(key, fromClipboard = true)
            check()
        } else {
            _setup.value = KeySetup.Entry(
                "",
                fromClipboard = false,
                note = "No key on the clipboard yet. In AI Studio tap Copy next to the key, then paste it here.",
            )
        }
    }

    fun enterManually() {
        checkJob?.cancel()
        _setup.value = KeySetup.Entry("", fromClipboard = false)
    }

    fun edit(text: String) {
        checkJob?.cancel()
        _setup.value = KeySetup.Entry(text, fromClipboard = false)
    }

    fun check() {
        val entry = _setup.value as? KeySetup.Entry ?: return
        val key = GeminiApi.findKey(entry.text)
        if (key == null) {
            _setup.value = entry.copy(check = CheckState.Failed("That doesn't look like a Gemini key — it starts with “AIza”."))
            return
        }
        checkJob?.cancel()
        _setup.value = entry.copy(check = CheckState.Checking)
        checkJob = viewModelScope.launch {
            val result = when (val check = cloud.checkKey(key)) {
                KeyCheck.Works -> CheckState.Works
                is KeyCheck.Failed -> CheckState.Failed(check.message)
            }
            (_setup.value as? KeySetup.Entry)?.let { _setup.value = it.copy(check = result) }
        }
    }

    fun useKey() {
        val entry = _setup.value as? KeySetup.Entry ?: return
        val key = GeminiApi.findKey(entry.text) ?: return
        if (entry.check != CheckState.Works) return
        viewModelScope.launch {
            cloud.saveKey(key)
            _setup.value = null
        }
    }

    fun removeKey() {
        viewModelScope.launch { cloud.removeKey() }
    }

    fun dismiss() {
        checkJob?.cancel()
        _setup.value = null
    }
}

/**
 * Mockup screens E + F1/F4, "Where the AI runs": Gemini Nano on top; below it, only
 * when Nano is unavailable, the user's own free Gemini key ([KeySetupSheet] sets it up).
 */
@Composable
fun AiSettingsScreen(onBack: () -> Unit, viewModel: AiSettingsViewModel = hiltViewModel()) {
    val status by viewModel.nanoStatus.collectAsStateWithLifecycle()
    val maskedKey by viewModel.maskedKey.collectAsStateWithLifecycle()
    val setup by viewModel.setup.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current

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
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
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
            if (status is AiEngineStatus.Unavailable) {
                GeminiKeyCard(
                    maskedKey = maskedKey,
                    onGetKey = viewModel::startGuide,
                    onEnterKey = viewModel::enterManually,
                    onRemove = viewModel::removeKey,
                )
                if (maskedKey != null) {
                    PrivacyNote()
                } else {
                    Text(
                        "Skip it and \"Build a playlist\" still picks by era and genre from your tags.",
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }

    setup?.let { current ->
        KeySetupSheet(
            setup = current,
            onOpenKeyPage = {
                viewModel.openedKeyPage()
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GeminiApi.KEY_PAGE_URL)))
            },
            onLeftApp = viewModel::leftApp,
            onReturned = viewModel::returned,
            onEdit = viewModel::edit,
            onCheck = viewModel::check,
            onUseKey = viewModel::useKey,
            onPasteDifferent = viewModel::enterManually,
            onDismiss = viewModel::dismiss,
        )
    }
}

/** F1 (no key yet) and F4 (connected). Only shown while Nano is unavailable. */
@Composable
private fun GeminiKeyCard(maskedKey: String?, onGetKey: () -> Unit, onEnterKey: () -> Unit, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val angle by rememberGlintAngle(periodMs = 6_000)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(colors.surfaceContainerLow)
            .edgeGlint({ angle }, cornerRadius = 24.dp, glint = colors.primary, glintTail = colors.tertiary)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Free Gemini key", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (maskedKey != null) {
                StatusBadge(AiEngineStatus.Ready, label = "Connected")
            } else {
                Text(
                    "FOR THIS PHONE",
                    modifier = Modifier
                        .clip(RoundedCornerShape(11.dp))
                        .background(colors.primary.copy(alpha = 0.16f))
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.primary,
                )
            }
        }
        if (maskedKey != null) {
            Text("Key $maskedKey · Google's free plan", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEnterKey) { Text("Change key") }
                OutlinedButton(
                    onClick = onRemove,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.tertiary),
                ) { Text("Remove") }
            }
        } else {
            Text(
                "Use Google's Gemini with your own free key. About a minute, once — your phone is " +
                    "already signed in to Google, so no password.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Free", "No card", "Needs internet").forEach { Tag(it) }
            }
            Button(
                onClick = onGetKey,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .glow(colors.primary.copy(alpha = 0.35f), radius = 14.dp, cornerRadius = 26.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
            ) {
                Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Get my free key", modifier = Modifier.padding(start = 8.dp), fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = onEnterKey, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("I already have a key")
            }
        }
    }
}

@Composable
private fun PrivacyNote() {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Shield, contentDescription = null, tint = colors.tertiary, modifier = Modifier.size(18.dp))
            Text("What leaves your phone", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        Text(
            "Only your request and the genre and decade names in your library — never tracks, audio " +
                "or file locations. Free-plan requests may be used by Google to improve its models.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
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
                is AiEngineStatus.Unavailable -> "Android doesn't let apps use Gemini Nano on this phone. Spindle checks again each time you open this screen."
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
            // The phone's exact answer, small — the only way to tell why on a release build.
            is AiEngineStatus.Unavailable -> status.reason?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
private fun StatusBadge(status: AiEngineStatus, label: String? = null) {
    val colors = MaterialTheme.colorScheme
    val (defaultLabel, color) = when (status) {
        AiEngineStatus.Ready -> "Ready" to colors.primary
        AiEngineStatus.Downloadable -> "Not downloaded" to colors.onSurfaceVariant
        is AiEngineStatus.Downloading ->
            (status.progress?.let { "Downloading ${(it * 100).roundToInt()}%" } ?: "Downloading…") to colors.primary
        is AiEngineStatus.Unavailable -> "Not available" to colors.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(8.dp)
                .then(if (status == AiEngineStatus.Ready) Modifier.glow(color.copy(alpha = 0.7f), radius = 6.dp, cornerRadius = 4.dp) else Modifier)
                .clip(CircleShape)
                .background(color),
        )
        Text(label ?: defaultLabel, style = MaterialTheme.typography.labelLarge, color = color)
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
