package io.github.eladimany.spindle.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.eladimany.spindle.core.model.OutputCapabilities
import io.github.eladimany.spindle.core.model.PlaybackState
import io.github.eladimany.spindle.core.model.QueueItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Wraps Media3/ExoPlayer for the phone-speaker/headphones output.
 *
 * Volume is the phone's actual `STREAM_MUSIC` level via [AudioManager] — not an
 * app-internal ExoPlayer gain. A gain multiplier on top of system volume is why
 * an app volume slider can show "100%" while the phone is really at 30%: the two
 * numbers stop meaning the same thing. Using STREAM_MUSIC directly means our
 * slider and the hardware volume buttons are always looking at the same value.
 */
@Singleton
class LocalOutput @Inject constructor(
    private val player: ExoPlayer,
    @ApplicationContext private val context: Context,
) : AudioOutput {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _volume = MutableStateFlow(currentSystemVolumePercent())
    override val volume: StateFlow<Int> = _volume.asStateFlow()

    override val capabilities = OutputCapabilities(canSeek = true, canSetVolume = true, isGapless = true)

    private var currentItem: QueueItem? = null

    // VOLUME_CHANGED_ACTION isn't public API, but the string is stable across
    // Android versions and is the standard way apps notice hardware volume-button
    // presses so an open volume UI can stay in sync without polling.
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(receivedContext: Context, intent: Intent) {
            val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
            if (streamType == AudioManager.STREAM_MUSIC) {
                _volume.value = currentSystemVolumePercent()
            }
        }
    }

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) = updateState()
            override fun onIsPlayingChanged(isPlaying: Boolean) = updateState()
            override fun onPlayerError(error: PlaybackException) {
                _state.value = PlaybackState.Error(currentItem, error.message ?: "Playback error")
            }
        })
        ContextCompat.registerReceiver(
            context,
            volumeReceiver,
            IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun currentSystemVolumePercent(): Int {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 0
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return (current * 100) / max
    }

    private fun updateState() {
        val item = currentItem
        _state.value = when {
            item == null -> PlaybackState.Idle
            player.playbackState == Player.STATE_ENDED -> PlaybackState.Ended(item)
            player.playbackState == Player.STATE_BUFFERING -> PlaybackState.Buffering(item)
            player.playbackState == Player.STATE_IDLE -> PlaybackState.Idle
            player.isPlaying -> PlaybackState.Playing(item, player.currentPosition, player.duration.coerceAtLeast(0))
            else -> PlaybackState.Paused(item, player.currentPosition, player.duration.coerceAtLeast(0))
        }
    }

    override suspend fun play(item: QueueItem) {
        currentItem = item
        val mediaItem = MediaItem.Builder()
            .setUri(item.track.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.track.title)
                    .setArtist(item.track.artistName)
                    .setAlbumTitle(item.track.albumName)
                    .build(),
            )
            .build()
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
    }

    override suspend fun pause() {
        player.pause()
    }

    override suspend fun resume() {
        player.play()
    }

    override suspend fun stop() {
        player.stop()
        currentItem = null
        _state.value = PlaybackState.Idle
    }

    override suspend fun seek(seconds: Int) {
        player.seekTo(seconds * 1000L)
    }

    override suspend fun setVolume(percent: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val index = ((percent.coerceIn(0, 100) / 100f) * max).roundToInt().coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
        _volume.value = currentSystemVolumePercent()
    }
}
