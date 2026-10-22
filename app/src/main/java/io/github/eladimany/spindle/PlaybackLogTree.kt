package io.github.eladimany.spindle

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import timber.log.Timber

/**
 * Playback and Node events to a small file, in release builds too — they have no
 * Logcat, and Node problems only show up at home, on the user's phone. Pulled with
 * `adb pull /sdcard/Android/data/io.github.eladimany.spindle/files/playback-log.txt`
 * (no root needed). Only playback-related tags; 2 files of ≤ 512 KB, rotated.
 */
class PlaybackLogTree(context: Context) : Timber.DebugTree() {
    private val dir = context.getExternalFilesDir(null)
    private val writer = Executors.newSingleThreadExecutor()
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    override fun isLoggable(tag: String?, priority: Int): Boolean =
        priority >= Log.DEBUG && tag != null && KEEP.any { tag.startsWith(it) }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val dir = dir ?: return
        val level = "VDIWEA".getOrElse(priority - Log.VERBOSE) { '?' }
        val line = buildString {
            append(time.format(Date())).append(' ').append(level).append('/').append(tag).append(": ").append(message)
            t?.let { append(" | ").append(it.javaClass.simpleName).append(": ").append(it.message) }
            append('\n')
        }
        writer.execute {
            try {
                val file = File(dir, FILE)
                if (file.length() > MAX_BYTES) file.renameTo(File(dir, "$FILE.1"))
                file.appendText(line)
            } catch (e: Exception) {
                // Logging must never break playback.
            }
        }
    }

    private companion object {
        const val FILE = "playback-log.txt"
        const val MAX_BYTES = 512 * 1024L
        val KEEP = listOf("NodeOutput", "MediaHttpServer", "PlaybackController", "BluetoothAutoSwitch", "AudioOutputSwitcher", "PlaybackDiagnostics")
    }
}
