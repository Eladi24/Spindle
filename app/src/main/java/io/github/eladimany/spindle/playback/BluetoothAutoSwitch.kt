package io.github.eladimany.spindle.playback

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Bluetooth headphones that connect take over playback, like in other music apps
 * (user request, 2026-10-22). Before this, "This phone" pinned the speaker, so earbuds
 * connecting mid-session stayed silent until the output was changed by hand.
 * - Connected while on the phone: playback moves to them.
 * - Connected while on a Node: switches to the phone, through them.
 * - The pinned headphones disconnect: back to the speaker (ExoPlayer already pauses
 *   on "audio becoming noisy").
 * Only reacts to changes after the app starts — headphones already connected at
 * launch get Android's own routing, which prefers them anyway.
 */
@Singleton
class BluetoothAutoSwitch @Inject constructor(
    private val routes: LocalAudioRoutes,
    private val localOutput: LocalOutput,
    private val switcher: AudioOutputSwitcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            var previous: Set<Int>? = null
            routes.routes.collect { current ->
                val bluetooth = current.filter { it.kind == LocalRouteKind.BLUETOOTH }
                val ids = bluetooth.mapTo(HashSet()) { it.id }
                val before = previous
                previous = ids
                if (before == null) return@collect
                val added = bluetooth.firstOrNull { it.id !in before }
                if (added != null) {
                    Timber.i("Bluetooth output connected (%s) — playing there", added.device.productName)
                    localOutput.setPreferredRoute(added)
                    if (switcher.target.value != OutputTarget.Local) switcher.switchTo(OutputTarget.Local)
                    return@collect
                }
                val pinned = localOutput.preferredRouteId.value
                if (pinned != null && pinned in before && pinned !in ids) {
                    Timber.i("Bluetooth output disconnected — back to the speaker")
                    localOutput.setPreferredRoute(current.firstOrNull { it.kind == LocalRouteKind.SPEAKER })
                }
            }
        }
    }
}
