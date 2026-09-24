package io.github.eladimany.spindle.playback

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class LocalRouteKind { SPEAKER, BLUETOOTH, WIRED }

data class LocalRoute(val device: AudioDeviceInfo, val kind: LocalRouteKind) {
    val id: Int get() = device.id
}

/**
 * Watches which local audio outputs (built-in speaker, a connected
 * Bluetooth device, wired headphones) are currently available, so the
 * output picker can offer them as separate, explicitly selectable choices
 * under "This phone" — Android's own automatic Bluetooth-follows-connection
 * routing is what plays there otherwise, with no in-app way to override it
 * (the feature request this exists for).
 *
 * Never unregisters its [AudioDeviceCallback] — a process-lifetime
 * singleton, same pattern as [LocalOutput]'s volume broadcast receiver.
 */
@Singleton
class LocalAudioRoutes @Inject constructor(@ApplicationContext private val context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val _routes = MutableStateFlow(currentRoutes())
    val routes: StateFlow<List<LocalRoute>> = _routes.asStateFlow()

    init {
        audioManager?.registerAudioDeviceCallback(
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
                    _routes.value = currentRoutes()
                }
                override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
                    _routes.value = currentRoutes()
                }
            },
            null,
        )
    }

    /** Best-effort — [AudioDeviceInfo.getProductName] needs BLUETOOTH_CONNECT (API 31+) for a real Bluetooth device name. */
    fun displayName(route: LocalRoute): String = when (route.kind) {
        LocalRouteKind.SPEAKER -> "Speaker"
        LocalRouteKind.WIRED -> "Wired headphones"
        LocalRouteKind.BLUETOOTH -> {
            if (hasBluetoothConnectPermission()) {
                route.device.productName?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: "Bluetooth device"
            } else {
                "Bluetooth device"
            }
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun currentRoutes(): List<LocalRoute> {
        val manager = audioManager ?: return emptyList()
        return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .mapNotNull { device ->
                val kind = when (device.type) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> LocalRouteKind.SPEAKER
                    // Deliberately not TYPE_BLUETOOTH_SCO: that's the voice-call profile
                    // (mono, 8/16kHz) the same physical earbuds also report as. Pinning
                    // ExoPlayer's media AudioTrack to it doesn't route media there — it
                    // silently falls back to the speaker, which reads as "this Bluetooth
                    // row acts like the phone speaker" (found on-device, A73 + earbuds).
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> LocalRouteKind.BLUETOOTH
                    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> LocalRouteKind.WIRED
                    else -> null
                } ?: return@mapNotNull null
                LocalRoute(device, kind)
            }
            .distinctBy { it.id }
    }
}
