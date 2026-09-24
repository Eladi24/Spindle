package io.github.eladimany.spindle.ui.playlists

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.eladimany.spindle.core.model.Playlist
import io.github.eladimany.spindle.core.model.PlaylistEntry
import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.data.history.trackKey
import io.github.eladimany.spindle.data.library.ArtworkRepository
import io.github.eladimany.spindle.data.playlists.PlaylistRepository
import io.github.eladimany.spindle.data.prefs.SettingsRepository
import io.github.eladimany.spindle.playback.PlaybackController
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: PlaylistRepository,
    private val artworkRepository: ArtworkRepository,
    private val playbackController: PlaybackController,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val playlistId: Long = checkNotNull(savedStateHandle["playlistId"])

    private val _playlist = MutableStateFlow<Playlist?>(null)
    val playlist: StateFlow<Playlist?> = _playlist.asStateFlow()

    val entries: StateFlow<List<PlaylistEntry>> = repository.entriesFor(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val currentTrackId: StateFlow<Long?> = playbackController.currentTrackId

    init {
        refreshPlaylist()
    }

    private fun refreshPlaylist() {
        viewModelScope.launch { _playlist.value = repository.getById(playlistId) }
    }

    fun playTrack(track: Track) {
        val all = entries.value.map { it.track }
        val startIndex = all.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        playbackController.playTracks(all, startIndex)
    }

    fun playAll() {
        val all = entries.value.map { it.track }
        if (all.isNotEmpty()) playbackController.playTracks(all)
    }

    fun playShuffled() {
        val all = entries.value.map { it.track }
        if (all.isNotEmpty()) playbackController.playTracksShuffled(all)
    }

    fun playSmartShuffled() {
        val all = entries.value.map { it.track }
        if (all.isNotEmpty()) playbackController.playTracksSmartShuffled(all)
    }

    fun rename(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.rename(playlistId, name.trim())
            refreshPlaylist()
        }
    }

    fun removeEntry(crossRefId: Long) {
        viewModelScope.launch { repository.removeEntry(playlistId, crossRefId) }
    }

    fun restoreEntry(entry: PlaylistEntry) {
        viewModelScope.launch { repository.restoreEntry(playlistId, entry) }
    }

    /** Tracks swiped right — they come up sooner in the next smart shuffle. */
    val boostedTrackKeys: StateFlow<Set<Long>> = settings.boostedTrackKeys
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun toggleBoost(track: Track) {
        val key = trackKey(track)
        viewModelScope.launch { settings.setBoosted(key, key !in boostedTrackKeys.value) }
    }

    fun reorder(orderedEntries: List<PlaylistEntry>) {
        viewModelScope.launch { repository.reorder(playlistId, orderedEntries) }
    }

    fun addToQueue(tracks: List<Track>) = playbackController.addToQueue(tracks)

    suspend fun artworkUriFor(track: Track): String? = artworkRepository.artworkUriFor(track)

    suspend fun exportM3u(): String = repository.exportM3u(playlistId)
}
