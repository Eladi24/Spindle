package io.github.eladimany.spindle.data.bluos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.eladimany.spindle.core.model.BluOsPlayer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber
import java.net.Inet4Address
import javax.inject.Inject
import javax.inject.Singleton

private const val SERVICE_TYPE = "_musc._tcp."

/**
 * NSD-based discovery for BluOS players on the LAN (`_musc._tcp`, per
 * `docs/bluos-api.md`). Emits the current set of resolved players as they're
 * found/lost. Manual IP entry (Settings, not built yet) is the documented
 * fallback for networks where mDNS doesn't reach — this is never the only
 * way to reach a player.
 */
@Singleton
class BluOsDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Android 13+ gates [NsdManager] behind this — see the manifest for why nothing is added pre-33. */
    private fun hasNearbyWifiPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun discover(): Flow<List<BluOsPlayer>> = callbackFlow {
        val nsdManager = context.getSystemService(NsdManager::class.java)
        if (nsdManager == null || !hasNearbyWifiPermission()) {
            Timber.w("BluOS discovery unavailable (nsdManager=%s, permission=%s)", nsdManager != null, hasNearbyWifiPermission())
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
        val multicastLock = wifiManager?.createMulticastLock("spindle-bluos-discovery")?.apply {
            setReferenceCounted(true)
            acquire()
        }

        val players = LinkedHashMap<String, BluOsPlayer>()
        fun emitCurrent() {
            trySend(players.values.toList())
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Timber.d("BluOS discovery started")
            }

            @Suppress("DEPRECATION") // registerServiceInfoCallback needs API 34; minSdk here is 26.
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                // A fresh listener per call: NsdManager rejects reusing one
                // ResolveListener across concurrent resolveService calls,
                // and several services can be found before any resolves.
                nsdManager.resolveService(
                    serviceInfo,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            Timber.w("BluOS resolve failed for %s: %d", info.serviceName, errorCode)
                        }

                        @Suppress("DEPRECATION") // NsdServiceInfo.host needs API 34's getHostAddresses(); minSdk here is 26.
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val address = (info.host as? Inet4Address)?.hostAddress ?: return
                            players[info.serviceName] = BluOsPlayer(name = info.serviceName, host = address, port = info.port)
                            emitCurrent()
                        }
                    },
                )
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                players.remove(serviceInfo.serviceName)
                emitCurrent()
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Timber.d("BluOS discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Timber.w("BluOS discovery start failed: %d", errorCode)
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Timber.w("BluOS discovery stop failed: %d", errorCode)
            }
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)

        awaitClose {
            runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
            if (multicastLock?.isHeld == true) multicastLock.release()
        }
    }
}
