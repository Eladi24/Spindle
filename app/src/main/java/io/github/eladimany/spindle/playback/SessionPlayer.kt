package io.github.eladimany.spindle.playback

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.eladimany.spindle.core.model.PlaybackState
import io.github.eladimany.spindle.core.model.elapsedMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The player the media session (lock screen, notification, headset and Bluetooth
 * controls) talks to: a mirror of [PlaybackController]'s state, whichever output is
 * active, rather than the phone's ExoPlayer.
 *
 * It used to be ExoPlayer, which sits idle while the Node plays — so Android saw an app
 * playing nothing, and after ~10 min with the screen off the S25+ froze Spindle: the Node
 * stalled on the last seconds of the song (the rest of the file was on the frozen phone),
 * nothing advanced the queue, and the half-dead stream then played as noise (2026-10-14).
 * Reporting "playing" keeps the foreground service treated as active media playback.
 */
class SessionPlayer(
    private val controller: PlaybackController,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        scope.launch {
            combine(controller.playbackState, controller.queueState) { _, _ -> }.collect { invalidateState() }
        }
    }

    override fun getState(): State {
        val playback = controller.playbackState.value
        val builder = State.Builder().setAvailableCommands(COMMANDS)
        val item = when (playback) {
            is PlaybackState.Playing -> playback.item
            is PlaybackState.Paused -> playback.item
            is PlaybackState.Buffering -> playback.item
            else -> null
        } ?: return builder
            .setPlaybackState(Player.STATE_IDLE)
            .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build()

        val durationMs = when (playback) {
            is PlaybackState.Playing -> playback.durationMs
            is PlaybackState.Paused -> playback.durationMs
            else -> item.track.durationMs
        }
        val track = item.track
        val mediaItem = MediaItem.Builder()
            .setMediaId(item.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artistName)
                    .setAlbumTitle(track.albumName)
                    .build(),
            )
            .build()
        return builder
            .setPlaylist(
                listOf(
                    MediaItemData.Builder(item.id)
                        .setMediaItem(mediaItem)
                        .setDurationUs(durationMs * 1000)
                        .setIsSeekable(true)
                        .build(),
                ),
            )
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(if (playback is PlaybackState.Buffering) Player.STATE_BUFFERING else Player.STATE_READY)
            .setPlayWhenReady(playback !is PlaybackState.Paused, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs { controller.playbackState.value.elapsedMs() ?: 0L }
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        val playing = when (controller.playbackState.value) {
            is PlaybackState.Playing, is PlaybackState.Buffering -> true
            else -> false
        }
        // togglePlayPause also starts a finished queue again (PlaybackController.replay).
        if (playWhenReady != playing) controller.togglePlayPause()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        if (controller.playbackState.value is PlaybackState.Playing) controller.togglePlayPause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        // The queue lives in PlaybackController, not in this one-item playlist — so next and
        // previous arrive here as seeks Media3 couldn't place, and go to the controller.
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> controller.next()
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> controller.previous()
            else -> if (positionMs >= 0) controller.seekTo((positionMs / 1000).toInt())
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        return Futures.immediateVoidFuture()
    }

    private companion object {
        val COMMANDS: Player.Commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_RELEASE,
            )
            .build()
    }
}
