package io.github.eladimany.spindle.playback

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.app.UiModeManager
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** [OTHER] = where Android sends music but no row can pin, e.g. Android Auto or a USB/dock output. */
enum class LocalRouteKind { SPEAKER, BLUETOOTH, WIRED, OTHER }

data class LocalRoute(val device: AudioDeviceInfo, val kind: LocalRouteKind) {
    val id: Int get() = device.id

    /** A Bluetooth device's MAC (API 28+), to match it with its paired-device entry. */
    val address: String? get() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) device.address.takeIf { it.isNotEmpty() }?.uppercase() else null
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

    private val _androidRoute = MutableStateFlow(currentAndroidRoute())

    /**
     * Where Android sends music when nothing is pinned — what "automatic" really means.
     * The picker and the output pill show this instead of assuming the speaker: with
     * earbuds connected at launch, "This phone" used to look selected while the sound
     * was in the earbuds (S25+, 2026-11-02).
     */
    val androidRoute: StateFlow<LocalRoute?> = _androidRoute.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private val settleRefresh = Runnable { refresh() }

    init {
        audioManager?.registerAudioDeviceCallback(
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) = onDevicesChanged()
                override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) = onDevicesChanged()
            },
            null,
        )
    }

    private fun onDevicesChanged() {
        refresh()
        // Android picks the new route a moment after the device callback (a car handing
        // over to Android Auto takes a second or two), so look again once it settles.
        handler.removeCallbacks(settleRefresh)
        handler.postDelayed(settleRefresh, 2_000)
    }

    /** Re-reads the devices and Android's current media route, e.g. when the Play on sheet opens. */
    fun refresh() {
        _routes.value = currentRoutes()
        _androidRoute.value = currentAndroidRoute()
    }

    /** Best-effort — [AudioDeviceInfo.getProductName] needs BLUETOOTH_CONNECT (API 31+) for a real Bluetooth device name. */
    fun displayName(route: LocalRoute): String = when (route.kind) {
        LocalRouteKind.SPEAKER -> "Speaker"
        LocalRouteKind.WIRED -> "Wired headphones"
        LocalRouteKind.OTHER -> if (isCarMode()) "Android Auto" else "Connected output"
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

    private fun isCarMode(): Boolean =
        context.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_CAR

    /**
     * API 33+: asks Android where media goes now. Older phones: Android's own order
     * (Bluetooth, then wired, then the speaker), which is right outside of car projection.
     */
    private fun currentAndroidRoute(): LocalRoute? {
        val manager = audioManager ?: return null
        val listed = currentRoutes()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val media = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val device = runCatching { manager.getAudioDevicesForAttributes(media).firstOrNull() }.getOrNull()
            if (device != null) {
                return listed.firstOrNull { it.id == device.id }
                    ?: LocalRoute(device, kindOf(device) ?: LocalRouteKind.OTHER)
            }
        }
        return listed.firstOrNull { it.kind == LocalRouteKind.BLUETOOTH }
            ?: listed.firstOrNull { it.kind == LocalRouteKind.WIRED }
            ?: listed.firstOrNull { it.kind == LocalRouteKind.SPEAKER }
    }

    private fun kindOf(device: AudioDeviceInfo): LocalRouteKind? = when (device.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> LocalRouteKind.SPEAKER
        // Deliberately not TYPE_BLUETOOTH_SCO: that's the voice-call profile
        // (mono, 8/16kHz) the same physical earbuds also report as. Pinning
        // ExoPlayer's media AudioTrack to it doesn't route media there — it
        // silently falls back to the speaker, which reads as "this Bluetooth
        // row acts like the phone speaker" (found on-device, A73 + earbuds).
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_HEARING_AID -> LocalRouteKind.BLUETOOTH
        // LE Audio earbuds (API 31+; older phones never report these types).
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> LocalRouteKind.BLUETOOTH
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET -> LocalRouteKind.WIRED
        else -> null
    }

    private fun currentRoutes(): List<LocalRoute> {
        val manager = audioManager ?: return emptyList()
        return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .mapNotNull { device ->
                LocalRoute(device, kindOf(device) ?: return@mapNotNull null)
            }
            .distinctBy { it.id }
    }
}
