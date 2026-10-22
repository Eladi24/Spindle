package io.github.eladimany.spindle

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.github.eladimany.spindle.playback.BluetoothAutoSwitch
import io.github.eladimany.spindle.playback.PlaybackDiagnostics
import javax.inject.Inject
import timber.log.Timber

@HiltAndroidApp
class App : Application() {
    @Inject lateinit var bluetoothAutoSwitch: BluetoothAutoSwitch
    @Inject lateinit var playbackDiagnostics: PlaybackDiagnostics

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.plant(PlaybackLogTree(this))
        bluetoothAutoSwitch.start()
        playbackDiagnostics.start()
    }
}
