package io.github.eladimany.spindle.ui.output

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.eladimany.spindle.core.model.BluOsPlayer
import io.github.eladimany.spindle.data.bluos.BluOsDiscovery
import io.github.eladimany.spindle.data.prefs.BatteryGuidance
import io.github.eladimany.spindle.data.prefs.SettingsRepository
import io.github.eladimany.spindle.playback.AudioOutputSwitcher
import io.github.eladimany.spindle.playback.LocalAudioRoutes
import io.github.eladimany.spindle.playback.LocalOutput
import io.github.eladimany.spindle.playback.LocalRoute
import io.github.eladimany.spindle.playback.LocalRouteKind
import io.github.eladimany.spindle.playback.OutputTarget
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class OutputPickerViewModel @Inject constructor(
    private val discovery: BluOsDiscovery,
    private val switcher: AudioOutputSwitcher,
    private val localOutput: LocalOutput,
    private val localAudioRoutes: LocalAudioRoutes,
    private val batteryGuidance: BatteryGuidance,
    private val settings: SettingsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val target: StateFlow<OutputTarget> = switcher.target

    // "This phone" sub-routes — only meaningful when target == Local; see LocalAudioRoutes.
    val localRoutes: StateFlow<List<LocalRoute>> = localAudioRoutes.routes
    val preferredRouteId: StateFlow<Int?> = localOutput.preferredRouteId
    fun routeDisplayName(route: LocalRoute): String = localAudioRoutes.displayName(route)

    private val _players = MutableStateFlow<List<BluOsPlayer>>(emptyList())
    val players: StateFlow<List<BluOsPlayer>> = _players.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var discoveryJob: Job? = null

    // Re-read on every resume (refreshBatteryState) — the user changes it in the
    // system's App info page, and nothing notifies us when they come back.
    private val _isUnrestricted = MutableStateFlow(batteryGuidance.isUnrestricted())
    val isUnrestricted: StateFlow<Boolean> = _isUnrestricted.asStateFlow()

    /** Card A: under the Node while it's active and Samsung may still put us to sleep. */
    val showBatteryCard: StateFlow<Boolean> = combine(
        target,
        _isUnrestricted,
        batteryGuidance.cardDismissed,
    ) { target, unrestricted, dismissed ->
        target is OutputTarget.Node && !unrestricted && !dismissed
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Sheet B: replaces the picker's content once, right after the first switch to a Node. */
    private val _showBatterySetup = MutableStateFlow(false)
    val showBatterySetup: StateFlow<Boolean> = _showBatterySetup.asStateFlow()

    fun refreshBatteryState() {
        _isUnrestricted.value = batteryGuidance.isUnrestricted()
    }

    /** The sheet closed, however that happened — the setup step is one-shot, never shown again on reopen. */
    fun finishBatterySetup() {
        _showBatterySetup.value = false
    }

    fun dismissBatteryCard() = batteryGuidance.dismissCard()

    fun batterySettingsIntent() = batteryGuidance.settingsIntent()

    /** Android 13+ gates NSD behind this — see the manifest and BluOsDiscovery for why nothing is needed pre-33. */
    fun hasNearbyWifiPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Only affects the *label* shown for a Bluetooth route — [LocalAudioRoutes]
     * still enumerates and lets you select an unnamed Bluetooth device without
     * this, so it's not gated on like [hasNearbyWifiPermission] is.
     */
    fun hasBluetoothConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** Call while the picker is visible; [stopDiscovery] when it isn't — no point scanning the network in the background. */
    fun startDiscovery() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = viewModelScope.launch {
            discovery.discover().collect { _players.value = it }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        _players.value = emptyList()
    }

    /**
     * "This phone" means the built-in speaker, explicitly — not Android's
     * automatic routing, which prefers a connected Bluetooth device and
     * would otherwise make this card meaningless whenever one's connected.
     */
    fun selectLocal() {
        val speaker = localAudioRoutes.routes.value.firstOrNull { it.kind == LocalRouteKind.SPEAKER }
        selectLocalRoute(speaker)
    }

    /** [route] null clears any pinned device and returns to Android's automatic routing. */
    fun selectLocalRoute(route: LocalRoute?) {
        _errorMessage.value = null
        localOutput.setPreferredRoute(route)
        viewModelScope.launch { switcher.switchTo(OutputTarget.Local) }
    }

    fun selectNode(player: BluOsPlayer) {
        _errorMessage.value = null
        _isConnecting.value = true
        viewModelScope.launch {
            try {
                switcher.switchTo(OutputTarget.Node(player))
                refreshBatteryState()
                if (!_isUnrestricted.value && !settings.batterySetupShown.first()) {
                    settings.markBatterySetupShown()
                    _showBatterySetup.value = true
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to switch to %s", player.name)
                _errorMessage.value = "Couldn't connect to ${player.name}. Make sure you're both on the same WiFi network."
            } finally {
                _isConnecting.value = false
            }
        }
    }

    fun connectManually(host: String) {
        val trimmed = host.trim()
        if (trimmed.isEmpty()) return
        selectNode(BluOsPlayer(name = trimmed, host = trimmed))
    }
}
