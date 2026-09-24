package io.github.eladimany.spindle.ui.smartplaylists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.data.library.ArtworkRepository
import io.github.eladimany.spindle.data.playlists.PlaylistRepository
import io.github.eladimany.spindle.data.smartplaylists.PlaylistCriteria
import io.github.eladimany.spindle.data.smartplaylists.PlaylistDraft
import io.github.eladimany.spindle.data.smartplaylists.SmartPlaylistGenerator
import io.github.eladimany.spindle.playback.PlaybackController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DraftPlaylistViewModel @Inject constructor(
    private val generator: SmartPlaylistGenerator,
    private val playlists: PlaylistRepository,
    private val playbackController: PlaybackController,
    private val artworkRepository: ArtworkRepository,
) : ViewModel() {

    /** Null after process death (drafts aren't persisted) — the screen offers a way back. */
    val draft: StateFlow<PlaylistDraft?> = generator.draft

    private val _busy = MutableStateFlow(false)
    /** True while remixing or re-generating after a filter was dropped. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    val currentTrackId: StateFlow<Long?> = playbackController.currentTrackId

    fun play(startIndex: Int = 0) {
        val tracks = draft.value?.tracks.orEmpty()
        if (tracks.isNotEmpty()) playbackController.playTracks(tracks, startIndex)
    }

    fun playSmartShuffled() {
        val tracks = draft.value?.tracks.orEmpty()
        if (tracks.isNotEmpty()) playbackController.playTracksSmartShuffled(tracks)
    }

    fun removeTrack(track: Track) {
        val current = draft.value ?: return
        generator.update(current.copy(tracks = current.tracks.filter { it.id != track.id }))
    }

    fun rename(name: String) {
        val current = draft.value ?: return
        if (name.isNotBlank()) generator.update(current.copy(name = name.trim()))
    }

    /** Same filters, a fresh draw. */
    fun remix() = regenerate { it }

    fun dropDecade(decade: Int) = regenerate { it.copy(decades = it.decades - decade) }

    fun dropGenre(genre: String) = regenerate { it.copy(genres = it.genres - genre) }

    /** Saves as a normal playlist; [onSaved] gets its id. The draft is cleared. */
    fun save(onSaved: (Long) -> Unit) {
        val current = draft.value ?: return
        viewModelScope.launch {
            val id = playlists.create(current.name)
            playlists.addTracks(id, current.tracks.map { it.id })
            generator.clear()
            onSaved(id)
        }
    }

    suspend fun artworkUriFor(track: Track): String? = artworkRepository.artworkUriFor(track)

    private fun regenerate(change: (PlaylistCriteria) -> PlaylistCriteria) {
        val current = draft.value ?: return
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            // A name the user typed survives; a suggested one follows the filters.
            val customName = current.name.takeIf { it != current.criteria.suggestedName() }
            generator.generate(change(current.criteria), customName)
            _busy.value = false
        }
    }
}
