package io.github.eladimany.spindle.ui.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.eladimany.spindle.core.model.Playlist
import io.github.eladimany.spindle.data.playlists.M3uImportResult
import io.github.eladimany.spindle.data.playlists.PlaylistRepository
import io.github.eladimany.spindle.data.smartplaylists.AiEngineStatus
import io.github.eladimany.spindle.data.smartplaylists.AiPlaylistEngine
import io.github.eladimany.spindle.data.smartplaylists.AiRequestState
import io.github.eladimany.spindle.data.smartplaylists.PlaylistCriteria
import io.github.eladimany.spindle.data.smartplaylists.SmartPlaylistGenerator
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val repository: PlaylistRepository,
    private val generator: SmartPlaylistGenerator,
    engine: AiPlaylistEngine,
) : ViewModel() {

    val aiStatus: StateFlow<AiEngineStatus> = engine.status
    val aiRequest: StateFlow<AiRequestState?> = generator.aiRequest

    /** Sends a free-text request to the AI; the answer shows up as the in-progress row. */
    fun makeFromRequest(request: String, default: PlaylistCriteria = PlaylistCriteria()) =
        generator.makeFromRequest(request, default)

    fun retryAiRequest() = generator.retryAiRequest()

    fun dismissAiRequest() = generator.dismissAiRequest()

    val playlists: StateFlow<List<Playlist>> = repository.playlists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _importResults = Channel<M3uImportResult>(Channel.BUFFERED)
    val importResults: Flow<M3uImportResult> = _importResults.receiveAsFlow()

    fun importM3u(suggestedName: String, content: String) {
        viewModelScope.launch {
            val result = repository.importM3u(suggestedName, content)
            _importResults.send(result)
        }
    }

    fun create(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.create(name.trim()) }
    }

    fun rename(playlist: Playlist, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.rename(playlist.id, name.trim()) }
    }

    fun delete(playlist: Playlist) {
        viewModelScope.launch { repository.delete(playlist.id) }
    }

    /** Creates a playlist and immediately adds [trackIds] to it — the "New playlist" path
     * from the add-to-playlist sheet, where there's no existing playlist to add to yet. */
    fun createAndAddTracks(name: String, trackIds: List<Long>) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = repository.create(name.trim())
            repository.addTracks(id, trackIds)
        }
    }

    fun addTracks(playlistId: Long, trackIds: List<Long>) {
        viewModelScope.launch { repository.addTracks(playlistId, trackIds) }
    }
}
