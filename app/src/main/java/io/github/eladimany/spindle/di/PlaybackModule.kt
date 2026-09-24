package io.github.eladimany.spindle.di

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.eladimany.spindle.data.smartplaylists.AiPlaylistEngine
import io.github.eladimany.spindle.data.smartplaylists.GeminiNanoEngine
import io.github.eladimany.spindle.playback.AudioOutput
import io.github.eladimany.spindle.playback.AudioOutputSwitcher
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PlaybackModule {

    @Provides
    @Singleton
    fun provideExoPlayer(@ApplicationContext context: Context): ExoPlayer {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        return ExoPlayer.Builder(context)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackBindingsModule {
    @Binds
    abstract fun bindAudioOutput(impl: AudioOutputSwitcher): AudioOutput

    @Binds
    abstract fun bindAiPlaylistEngine(impl: GeminiNanoEngine): AiPlaylistEngine
}
