package io.github.eladimany.spindle.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.github.eladimany.spindle.core.model.OutputCapabilities
import io.github.eladimany.spindle.core.model.PlaybackState
import io.github.eladimany.spindle.core.model.QueueItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Wraps Media3/ExoPlayer for the phone-speaker/headphones output. */
@Singleton
class LocalOutput @Inject constructor(
    private val player: ExoPlayer,
) : AudioOutput {

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    override val capabilities = OutputCapabilities(canSeek = true, canSetVolume = true, isGapless = true)

    private var currentItem: QueueItem? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) = updateState()
            override fun onIsPlayingChanged(isPlaying: Boolean) = updateState()
            override fun onPlayerError(error: PlaybackException) {
                _state.value = PlaybackState.Error(currentItem, error.message ?: "Playback error")
            }
        })
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
        player.volume = (percent / 100f).coerceIn(0f, 1f)
    }
}
