package io.github.eladimany.spindle.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.IntentCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dagger.hilt.android.AndroidEntryPoint
import io.github.eladimany.spindle.R
import io.github.eladimany.spindle.core.model.PlaybackState
import io.github.eladimany.spindle.data.history.trackKey
import io.github.eladimany.spindle.data.prefs.SettingsRepository
import io.github.eladimany.spindle.playback.auto.LibraryBrowser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Hosts the MediaSession for system integration — notification, lock screen, media
 * buttons, and Android Auto: a [MediaLibraryService], so Auto lists Spindle and browses
 * the library through [LibraryBrowser] (2026-11-02; before, Auto only showed its
 * generic player). The actual playback engine lives in [LocalOutput]/[PlaybackController],
 * both singletons shared with this service so the UI and the session agree on state.
 *
 * Posts its own notification rather than relying on Media3's automatic promotion,
 * which only fires once a MediaController connects to the session — nothing does in
 * this same-process setup, and startForegroundService() without a timely
 * startForeground() crashes the process (ForegroundServiceDidNotStartInTimeException).
 */
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    // Also keeps its init{} (the auto-advance listener) running for the service's
    // lifetime even if no UI is bound.
    @Inject lateinit var playbackController: PlaybackController
    @Inject lateinit var libraryBrowser: LibraryBrowser
    @Inject lateinit var settings: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSession: MediaLibrarySession? = null
    private lateinit var player: Player
    private lateinit var sessionPlayer: SessionPlayer
    private var boostedKeys: Set<Long> = emptySet()
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        // The session mirrors PlaybackController (SessionPlayer), not ExoPlayer: ExoPlayer
        // is idle whenever the Node is the output, and an idle session let Samsung freeze
        // Spindle mid-album (see SessionPlayer). It also owns next/previous — ExoPlayer only
        // ever held one item, so system controls hid skip and "previous" just restarted.
        sessionPlayer = SessionPlayer(this, playbackController)
        player = sessionPlayer
        mediaSession = MediaLibrarySession.Builder(this, sessionPlayer, LibraryCallback())
            .setMediaButtonPreferences(carButtons())
            .build()
        // Bound by Android Auto with the app in the background, Android may refuse a
        // foreground start; updateNotification() tries again once something plays.
        createNotificationChannel()
        tryStartForeground()

        player.addListener(object : Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                updateNotification()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateNotification()
            }
        })

        scope.launch {
            combine(playbackController.queueState, playbackController.currentTrackId, settings.boostedTrackKeys) { _, _, boosted ->
                boostedKeys = boosted
            }.collect { mediaSession?.setMediaButtonPreferences(carButtons()) }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    /** Extra buttons on Android Auto's player (and the shade's player card): shuffle mode, repeat, boost. */
    private fun carButtons(): List<CommandButton> {
        val queue = playbackController.queueState.value
        val (shuffleIcon, shuffleName) = when (queue.shuffleMode) {
            QueueManager.ShuffleMode.OFF -> CommandButton.ICON_SHUFFLE_OFF to "Shuffle off"
            QueueManager.ShuffleMode.SHUFFLE -> CommandButton.ICON_SHUFFLE_ON to "Shuffle on"
            QueueManager.ShuffleMode.SMART -> CommandButton.ICON_SHUFFLE_STAR to "Smart shuffle"
        }
        val (repeatIcon, repeatName) = when (queue.repeatMode) {
            QueueManager.RepeatMode.OFF -> CommandButton.ICON_REPEAT_OFF to "Repeat off"
            QueueManager.RepeatMode.ALL -> CommandButton.ICON_REPEAT_ALL to "Repeat all"
            QueueManager.RepeatMode.ONE -> CommandButton.ICON_REPEAT_ONE to "Repeat one"
        }
        val boosted = currentTrack()?.let { trackKey(it) in boostedKeys } == true
        return listOf(
            button(shuffleIcon, shuffleName, CMD_SHUFFLE),
            button(repeatIcon, repeatName, CMD_REPEAT),
            button(
                if (boosted) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED,
                if (boosted) "Boosted" else "Boost",
                CMD_BOOST,
            ),
        )
    }

    private fun button(icon: Int, name: String, action: String) = CommandButton.Builder(icon)
        .setDisplayName(name)
        .setSessionCommand(SessionCommand(action, Bundle.EMPTY))
        .setSlots(CommandButton.SLOT_OVERFLOW)
        .build()

    private fun currentTrack() = when (val state = playbackController.playbackState.value) {
        is PlaybackState.Playing -> state.item.track
        is PlaybackState.Paused -> state.item.track
        is PlaybackState.Buffering -> state.item.track
        else -> null
    }

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val result = SettableFuture.create<T>()
        scope.launch {
            try {
                result.set(block())
            } catch (e: Exception) {
                Timber.w(e, "Android Auto request failed")
                result.setException(e)
            }
        }
        return result
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            libraryBrowser.grantArtwork(controller.packageName)
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_SHUFFLE, Bundle.EMPTY))
                .add(SessionCommand(CMD_REPEAT, Bundle.EMPTY))
                .add(SessionCommand(CMD_BOOST, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setMediaButtonPreferences(carButtons())
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val queue = playbackController.queueState.value
            when (customCommand.customAction) {
                CMD_SHUFFLE -> playbackController.setShuffleMode(QueueManager.ShuffleMode.entries.next(queue.shuffleMode))
                CMD_REPEAT -> playbackController.setRepeatMode(QueueManager.RepeatMode.entries.next(queue.repeatMode))
                CMD_BOOST -> currentTrack()?.let { track ->
                    val key = trackKey(track)
                    scope.launch { settings.setBoosted(key, key !in settings.boostedTrackKeys.first()) }
                }
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val extras = Bundle().apply {
                putBoolean(LibraryBrowser.STYLE_SUPPORTED, true)
                putBoolean(LibraryBrowser.SEARCH_SUPPORTED, true)
            }
            return Futures.immediateFuture(
                LibraryResult.ofItem(libraryBrowser.root(), LibraryParams.Builder().setExtras(extras).build()),
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            LibraryResult.ofItemList(libraryBrowser.children(parentId).page(page, pageSize), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = future {
            libraryBrowser.item(mediaId)?.let { LibraryResult.ofItem(it, null) }
                ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = future {
            session.notifySearchResultChanged(browser, query, libraryBrowser.search(query).size, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            LibraryResult.ofItemList(libraryBrowser.search(query).page(page, pageSize), params)
        }

        // A tap on a song or shuffle row in Auto, or a voice request. The queue is ours
        // (PlaybackController), so it's played here and Media3 just gets the request back.
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
            val requested = mediaItems.getOrNull(startIndex) ?: mediaItems.firstOrNull()
            if (requested != null) playFromBrowser(requested)
            MediaSession.MediaItemsWithStartPosition(mediaItems, 0, C.TIME_UNSET)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = future {
            mediaItems.firstOrNull()?.let { playFromBrowser(it) }
            mediaItems
        }

        // Belt-and-suspenders alongside SessionPlayer's seek handling: a physical
        // Bluetooth/wired headset button sends a raw KEYCODE_MEDIA_NEXT/PREVIOUS
        // here rather than going through a controller's transport-control calls
        // (which is what SessionPlayer.handleSeek catches), and
        // Media3's default key-to-player-command translation for that path was
        // still landing on ExoPlayer's own single-item seekToPrevious() fallback
        // (restart) rather than the override — confirmed on-device via `adb shell
        // input keyevent KEYCODE_MEDIA_PREVIOUS` before this was added.
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
    }

    private suspend fun playFromBrowser(item: MediaItem) {
        if (libraryBrowser.play(item.mediaId, item.requestMetadata.searchQuery)) sessionPlayer.startedByBrowser()
    }

    private fun tryStartForeground() {
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
            foreground = true
        } catch (e: Exception) {
            Timber.w(e, "Couldn't start in the foreground yet")
        }
    }

    private fun updateNotification() {
        // A start refused earlier (bound by Auto in the background) is retried once
        // something plays.
        if (!foreground && player.isPlaying) {
            tryStartForeground()
            if (foreground) return
        }
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification()) }
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
        scope.cancel()
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
        private const val CMD_SHUFFLE = "spindle.SHUFFLE_MODE"
        private const val CMD_REPEAT = "spindle.REPEAT_MODE"
        private const val CMD_BOOST = "spindle.BOOST"

        private fun <T : Enum<T>> List<T>.next(current: T): T = this[(current.ordinal + 1) % size]

        private fun <T> List<T>.page(page: Int, pageSize: Int): ImmutableList<T> {
            if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return ImmutableList.copyOf(this)
            val from = (page.toLong() * pageSize).coerceAtMost(size.toLong()).toInt()
            return ImmutableList.copyOf(subList(from, minOf(size, from + pageSize)))
        }
    }
}
