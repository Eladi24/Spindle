package io.github.eladimany.spindle.data.bluos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * The runtime permissions for finding and talking to a Node on the LAN:
 * NEARBY_WIFI_DEVICES gates NSD on 33+, and ACCESS_LOCAL_NETWORK gates all LAN
 * traffic on 37+ for apps targeting 37. Without the latter, Android answers our
 * discovery with its own "Choose a device to connect" picker every time — the
 * user's 2026-10-29 S25+ report. Both are in the Nearby devices group, so asking
 * for them together is one dialog.
 */
object NetworkPermissions {
    fun required(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }

    fun missing(context: Context): List<String> = required().filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

    fun granted(context: Context): Boolean = missing(context).isEmpty()
}
