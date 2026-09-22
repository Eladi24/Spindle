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
import io.github.eladimany.spindle.playback.AudioOutputSwitcher
import io.github.eladimany.spindle.playback.OutputTarget
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class OutputPickerViewModel @Inject constructor(
    private val discovery: BluOsDiscovery,
    private val switcher: AudioOutputSwitcher,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val target: StateFlow<OutputTarget> = switcher.target

    private val _players = MutableStateFlow<List<BluOsPlayer>>(emptyList())
    val players: StateFlow<List<BluOsPlayer>> = _players.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var discoveryJob: Job? = null

    /** Android 13+ gates NSD behind this — see the manifest and BluOsDiscovery for why nothing is needed pre-33. */
    fun hasNearbyWifiPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.NEARBY_WIFI_DEVICES,
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

    fun selectLocal() {
        _errorMessage.value = null
        viewModelScope.launch { switcher.switchTo(OutputTarget.Local) }
    }

    fun selectNode(player: BluOsPlayer) {
        _errorMessage.value = null
        _isConnecting.value = true
        viewModelScope.launch {
            try {
                switcher.switchTo(OutputTarget.Node(player))
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
