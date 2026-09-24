package io.github.eladimany.spindle.playback

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps WiFi and the CPU awake while the Node is streaming from us. On Node output
 * nothing plays locally, so with the screen off Android is free to put WiFi into
 * power-save and the CPU to sleep — either stalls [io.github.eladimany.spindle.data.server.MediaHttpServer]
 * mid-track. Same pair Media3 holds for `WAKE_MODE_NETWORK`. Only held while
 * actually streaming (playing/buffering), never while paused or idle.
 */
@Singleton
class NodeStreamingLocks @Inject constructor(
    @ApplicationContext context: Context,
) {
    // HIGH_PERF, not LOW_LATENCY: LOW_LATENCY only applies while the app is in the
    // foreground with the screen on — exactly the case this doesn't need to cover.
    @Suppress("DEPRECATION")
    private val wifiLock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
        .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Spindle:NodeStreaming")
        .apply { setReferenceCounted(false) }

    private val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Spindle:NodeStreaming")
        .apply { setReferenceCounted(false) }

    @SuppressLint("WakelockTimeout") // Released explicitly when streaming stops; a track can be hours long.
    fun setHeld(held: Boolean) {
        if (held == wakeLock.isHeld) return
        if (held) {
            wakeLock.acquire()
            wifiLock.acquire()
        } else {
            wifiLock.release()
            wakeLock.release()
        }
        Timber.d("Node streaming locks %s", if (held) "acquired" else "released")
    }
}
