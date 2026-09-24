package io.github.eladimany.spindle.data.prefs

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether Samsung (or stock Android) may put Spindle to sleep in the background.
 * While streaming to the Node there's no local audio, so a sleeping app means the
 * Node's HTTP stream stalls with the screen off.
 *
 * Deliberately never uses ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (the
 * one-tap system prompt): Play only allows it for a short list of app kinds and a
 * music player isn't one. The user flips it themselves on the App info page
 * (One UI: Battery → Unrestricted), which is what [settingsIntent] opens.
 */
@Singleton
class BatteryGuidance @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val powerManager = context.getSystemService(PowerManager::class.java)

    /** True for One UI's "Unrestricted"; "Optimized" and "Restricted" both read false. */
    fun isUnrestricted(): Boolean = powerManager.isIgnoringBatteryOptimizations(context.packageName)

    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // "Not now" on the Play on card hides it until the next app start — in memory
    // on purpose, so the card comes back while the setting is still wrong.
    private val _cardDismissed = MutableStateFlow(false)
    val cardDismissed: StateFlow<Boolean> = _cardDismissed.asStateFlow()

    fun dismissCard() {
        _cardDismissed.value = true
    }
}
