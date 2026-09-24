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
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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

    @Inject lateinit var player: ExoPlayer

    // Injected only so its init{} (the auto-advance listener) runs for the service's
    // lifetime even if no UI is bound.
    @Inject lateinit var playbackController: PlaybackController

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        // ExoPlayer only ever holds the one MediaItem currently playing (see
        // LocalOutput.play()) — QueueManager, not ExoPlayer's own Timeline, owns
        // next/previous. Without this wrapper, the raw player reports no next/previous
        // media item, so system surfaces (lock screen, notification, Bluetooth/Android
        // Auto) hide the skip button entirely and "previous" just restarts the current
        // track via ExoPlayer's own single-item fallback — reported by the user testing
        // the A73's lock screen widget.
        val queueAwarePlayer = QueueAwareForwardingPlayer(player, playbackController)
        mediaSession = MediaSession.Builder(this, queueAwarePlayer)
            // Belt-and-suspenders alongside QueueAwareForwardingPlayer: a physical
            // Bluetooth/wired headset button sends a raw KEYCODE_MEDIA_NEXT/PREVIOUS
            // here rather than going through a controller's transport-control calls
            // (which is what the ForwardingPlayer overrides actually catch), and
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

/**
 * Advertises next/previous as always available and routes them to [controller]'s own
 * queue instead of ExoPlayer's single-item Timeline — see the comment in [onCreate]
 * above. Mirrors the in-app Now Playing screen, which also always enables its
 * previous/next buttons rather than trying to mirror QueueManager's exact
 * at-the-boundary edge cases.
 */
private class QueueAwareForwardingPlayer(
    player: Player,
    private val controller: PlaybackController,
) : ForwardingPlayer(player) {

    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .build()

    override fun hasNextMediaItem(): Boolean = true
    override fun hasPreviousMediaItem(): Boolean = true

    override fun seekToNext() = controller.next()
    override fun seekToNextMediaItem() = controller.next()
    override fun seekToPrevious() = controller.previous()
    override fun seekToPreviousMediaItem() = controller.previous()
}
