package io.github.eladimany.spindle.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.eladimany.spindle.core.model.PlaybackState
import io.github.eladimany.spindle.core.model.elapsedMs
import io.github.eladimany.spindle.data.server.NetworkAddress
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Context for the playback log (PlaybackLogTree) — most Node problems happened with the
 * screen off (user, 2026-10-22). Logs screen on/off, Doze and power-saving changes, the
 * battery setting when a Node session starts, and a heartbeat every 30 s while playing
 * on a Node: a gap between heartbeats means Android froze the app.
 */
@Singleton
class PlaybackDiagnostics @Inject constructor(
    @ApplicationContext private val context: Context,
    private val switcher: AudioOutputSwitcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val power = context.getSystemService(PowerManager::class.java)
    private var heartbeat: Job? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> Timber.i("Screen off")
                    Intent.ACTION_SCREEN_ON -> Timber.i("Screen on")
                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> Timber.i("Doze: %s", power?.isDeviceIdleMode)
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> Timber.i("Power saving: %s", power?.isPowerSaveMode)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        scope.launch {
            switcher.target.collect { target ->
                if (target is OutputTarget.Node) {
                    Timber.i(
                        "Playing on %s (%s) — battery unrestricted: %s, power saving: %s",
                        target.player.name, target.player.host,
                        power?.isIgnoringBatteryOptimizations(context.packageName), power?.isPowerSaveMode,
                    )
                }
            }
        }
        scope.launch {
            combine(switcher.target, switcher.state) { target, state ->
                target is OutputTarget.Node && (state is PlaybackState.Playing || state is PlaybackState.Buffering)
            }.distinctUntilChanged().collect { active ->
                heartbeat?.cancel()
                if (active) heartbeat = scope.launch { beat() }
            }
        }
    }

    private suspend fun beat() {
        while (true) {
            delay(HEARTBEAT_MS)
            val state = switcher.state.value
            val item = when (state) {
                is PlaybackState.Playing -> state.item
                is PlaybackState.Buffering -> state.item
                else -> null
            }
            Timber.d(
                "Heartbeat: %s %s at %ds, phone %s",
                state::class.simpleName, item?.track?.title, (state.elapsedMs() ?: 0L) / 1000, NetworkAddress.wlanIpv4(context),
            )
        }
    }

    private companion object {
        const val HEARTBEAT_MS = 30_000L
    }
}
