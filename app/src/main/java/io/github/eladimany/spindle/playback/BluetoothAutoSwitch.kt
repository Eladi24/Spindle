package io.github.eladimany.spindle.playback

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Headphones or a car that connect take over playback, like in other music apps
 * (user request, 2026-10-22).
 * - A new Bluetooth / wired output while on the phone: any pin is cleared, so Android
 *   routes to it (and on to Android Auto, which takes over from the car's Bluetooth).
 * - A new Bluetooth output while on a Node: switches to the phone, through it.
 * - The pinned device disconnects: the pin is cleared (ExoPlayer already pauses on
 *   "audio becoming noisy").
 * Never pins anything itself. It used to pin the new device, then the speaker when that
 * device dropped — and a car connects over Bluetooth and drops it within ~3 s when
 * Android Auto starts, so music stayed on the phone speaker until the app restarted
 * (S25+, 2026-11-02).
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
                val external = current.filter { it.kind != LocalRouteKind.SPEAKER }
                val ids = external.mapTo(HashSet()) { it.id }
                val before = previous
                previous = ids
                if (before == null) return@collect
                val added = external.firstOrNull { it.id !in before }
                if (added != null) {
                    Timber.i("Output connected (%s, %s) — following Android's routing", added.kind, added.device.productName)
                    if (localOutput.preferredRouteId.value != null) localOutput.setPreferredRoute(null)
                    if (added.kind == LocalRouteKind.BLUETOOTH && switcher.target.value != OutputTarget.Local) {
                        switcher.switchTo(OutputTarget.Local)
                    }
                    return@collect
                }
                val pinned = localOutput.preferredRouteId.value
                if (pinned != null && pinned in before && pinned !in ids) {
                    Timber.i("Pinned output disconnected — back to Android's routing")
                    localOutput.setPreferredRoute(null)
                }
            }
        }
    }
}
