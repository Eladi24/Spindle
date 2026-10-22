package io.github.eladimany.spindle

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.github.eladimany.spindle.playback.BluetoothAutoSwitch
import javax.inject.Inject
import timber.log.Timber

@HiltAndroidApp
class App : Application() {
    @Inject lateinit var bluetoothAutoSwitch: BluetoothAutoSwitch

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.plant(PlaybackLogTree(this))
        bluetoothAutoSwitch.start()
    }
}
