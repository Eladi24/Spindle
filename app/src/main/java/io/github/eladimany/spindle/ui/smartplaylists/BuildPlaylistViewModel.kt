package io.github.eladimany.spindle.ui.smartplaylists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.eladimany.spindle.data.smartplaylists.AiEngineStatus
import io.github.eladimany.spindle.data.smartplaylists.AiPlaylistEngine
import io.github.eladimany.spindle.data.smartplaylists.LibraryFacets
import io.github.eladimany.spindle.data.smartplaylists.PlaylistCriteria
import io.github.eladimany.spindle.data.smartplaylists.PlaylistLength
import io.github.eladimany.spindle.data.smartplaylists.SmartPlaylistGenerator
import io.github.eladimany.spindle.data.smartplaylists.TrackTags
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BuildPlaylistUiState(
    /** Null while the tags load. */
    val facets: LibraryFacets? = null,
    val criteria: PlaylistCriteria = PlaylistCriteria(),
    /** Library tracks the current filters match, before the length cut. */
    val matchCount: Int = 0,
    val making: Boolean = false,
)

@HiltViewModel
class BuildPlaylistViewModel @Inject constructor(
    private val generator: SmartPlaylistGenerator,
    engine: AiPlaylistEngine,
) : ViewModel() {

    private val _state = MutableStateFlow(BuildPlaylistUiState())
    val state: StateFlow<BuildPlaylistUiState> = _state.asStateFlow()

    val engineStatus: StateFlow<AiEngineStatus> = engine.status

    private var tags: List<TrackTags> = emptyList()

    /** Called each time the sheet opens: fresh tags (a rescan may have run), [initial] filters. */
    fun start(initial: PlaylistCriteria?) {
        _state.value = BuildPlaylistUiState(criteria = initial ?: PlaylistCriteria())
        viewModelScope.launch {
            tags = generator.tags()
            _state.update { it.copy(facets = LibraryFacets.from(tags), matchCount = count(it.criteria)) }
        }
    }

    fun toggleDecade(decade: Int) = edit { it.copy(decades = it.decades.toggle(decade)) }

    fun toggleGenre(genre: String) = edit { it.copy(genres = it.genres.toggle(genre)) }

    fun setLength(length: PlaylistLength) = edit { it.copy(length = length) }

    fun setLeanOnHistory(lean: Boolean) = edit { it.copy(leanOnHistory = lean) }

    /** Generates the draft, then [onDone] (navigate to it / close the sheet). */
    fun make(onDone: () -> Unit) {
        if (_state.value.making) return
        _state.update { it.copy(making = true) }
        viewModelScope.launch {
            generator.generate(_state.value.criteria)
            _state.update { it.copy(making = false) }
            onDone()
        }
    }

    private fun edit(change: (PlaylistCriteria) -> PlaylistCriteria) {
        _state.update { s ->
            val criteria = change(s.criteria)
            s.copy(criteria = criteria, matchCount = count(criteria))
        }
    }

    private fun count(criteria: PlaylistCriteria) = tags.count { criteria.matches(it.genre, it.year) }

    private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value
}
