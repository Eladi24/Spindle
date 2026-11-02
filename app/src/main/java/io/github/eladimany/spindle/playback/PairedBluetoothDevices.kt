package io.github.eladimany.spindle.playback

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRouter2
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class PairedAudioDevice(val name: String, val address: String)

/**
 * Paired Bluetooth audio devices for the Play on sheet's BLUETOOTH section (mockup 8A,
 * 2026-11-02). Connected ones play through [LocalAudioRoutes] like before; this adds the
 * paired-but-not-connected ones. Apps can't connect an A2DP device themselves, so
 * [openOutputSwitcher] hands over to Android's own output switcher (API 34+), which can;
 * older phones get Bluetooth settings.
 */
@Singleton
class PairedBluetoothDevices @Inject constructor(@ApplicationContext private val context: Context) {

    /** Empty without BLUETOOTH_CONNECT (API 31+) or with Bluetooth off. */
    @SuppressLint("MissingPermission") // checked by hasPermission()
    fun audioDevices(): List<PairedAudioDevice> {
        if (!hasPermission()) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        return runCatching {
            adapter.bondedDevices
                .filter { device ->
                    val btClass = device.bluetoothClass
                    btClass == null ||
                        btClass.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO ||
                        btClass.hasService(BluetoothClass.Service.AUDIO) ||
                        btClass.hasService(BluetoothClass.Service.RENDER)
                }
                .map { device ->
                    PairedAudioDevice(
                        name = device.name?.trim().takeUnless { it.isNullOrEmpty() } ?: device.address,
                        address = device.address.uppercase(),
                    )
                }
                .sortedBy { it.name.lowercase() }
        }.getOrDefault(emptyList())
    }

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Android's output switcher, where a paired device can be connected; Bluetooth settings where that's not offered. */
    fun openOutputSwitcher() {
        val shown = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            runCatching { MediaRouter2.getInstance(context).showSystemOutputSwitcher() }.getOrDefault(false)
        if (!shown) openBluetoothSettings()
    }

    fun openBluetoothSettings() {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
