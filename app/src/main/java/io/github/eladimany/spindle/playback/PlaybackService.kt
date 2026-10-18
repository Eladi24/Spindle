package io.github.eladimany.spindle.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.IntentCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaStyleNotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import io.github.eladimany.spindle.R
import javax.inject.Inject

/**
 * Hosts the MediaSession for system integration — notification, lock screen, media
 * buttons. The actual playback engine lives in [LocalOutput]/[PlaybackController],
 * both singletons shared with this service so the UI and the session agree on state.
 *
 * Posts its own notification rather than relying on Media3's automatic promotion,
 * which only fires once a MediaController connects to the session — nothing does in
 * this same-process setup, and startForegroundService() without a timely
 * startForeground() crashes the process (ForegroundServiceDidNotStartInTimeException).
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    // Injected only so its init{} (the auto-advance listener) runs for the service's
    // lifetime even if no UI is bound.
    @Inject lateinit var playbackController: PlaybackController

    private var mediaSession: MediaSession? = null
    private lateinit var player: Player

    override fun onCreate() {
        super.onCreate()
        // The session mirrors PlaybackController (SessionPlayer), not ExoPlayer: ExoPlayer
        // is idle whenever the Node is the output, and an idle session let Samsung freeze
        // Spindle mid-album (see SessionPlayer). It also owns next/previous — ExoPlayer only
        // ever held one item, so system controls hid skip and "previous" just restarted.
        val sessionPlayer = SessionPlayer(playbackController)
        player = sessionPlayer
        mediaSession = MediaSession.Builder(this, sessionPlayer)
            // Belt-and-suspenders alongside SessionPlayer's seek handling: a physical
            // Bluetooth/wired headset button sends a raw KEYCODE_MEDIA_NEXT/PREVIOUS
            // here rather than going through a controller's transport-control calls
            // (which is what SessionPlayer.handleSeek catches), and
            // Media3's default key-to-player-command translation for that path was
            // still landing on ExoPlayer's own single-item seekToPrevious() fallback
            // (restart) rather than the override — confirmed on-device via `adb shell
            // input keyevent KEYCODE_MEDIA_PREVIOUS` before this was added.
            .setCallback(object : MediaSession.Callback {
                override fun onMediaButtonEvent(
                    session: MediaSession,
                    controllerInfo: MediaSession.ControllerInfo,
                    intent: Intent,
                ): Boolean {
                    val keyEvent = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    if (keyEvent?.action == KeyEvent.ACTION_DOWN) {
                        when (keyEvent.keyCode) {
                            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                                playbackController.next()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                                playbackController.previous()
                                return true
                            }
                        }
                    }
                    return super.onMediaButtonEvent(session, controllerInfo, intent)
                }
            })
            .build()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        player.addListener(object : Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                updateNotification()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateNotification()
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    private fun updateNotification() {
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val session = mediaSession
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(player.mediaMetadata.title?.toString() ?: "Spindle")
            .setContentText(player.mediaMetadata.artist?.toString() ?: "")
            .setOngoing(player.isPlaying)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (session != null) {
            builder.setStyle(MediaStyleNotificationHelper.MediaStyle(session))
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
    }
}
