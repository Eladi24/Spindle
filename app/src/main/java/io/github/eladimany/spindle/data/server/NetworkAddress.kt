package io.github.eladimany.spindle.data.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

/**
 * Finds the phone's WLAN IPv4 address so [MediaHttpServer] can bind to it
 * specifically — never `0.0.0.0` — per the hard constraint that the embedded
 * server must not be reachable over any other interface (VPN, hotspot, etc).
 */
object NetworkAddress {
    fun wlanIpv4(context: Context): String? {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = connectivityManager.activeNetwork ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val linkProperties = connectivityManager.getLinkProperties(network) ?: return null
        return linkProperties.linkAddresses
            .mapNotNull { it.address as? Inet4Address }
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }
}
