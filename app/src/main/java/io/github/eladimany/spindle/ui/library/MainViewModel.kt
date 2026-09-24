package io.github.eladimany.spindle.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.eladimany.spindle.data.library.LibraryRepository
import io.github.eladimany.spindle.data.library.ScanProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

data class MainUiState(
    val isScanning: Boolean = false,
    val scannedCount: Int = 0,
    val trackCount: Int = 0,
    val albumCount: Int = 0,
    val artistCount: Int = 0,
    val lastScanMs: Long? = null,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: LibraryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var isObservingChanges = false

    init {
        viewModelScope.launch {
            combine(repository.tracks, repository.albums, repository.artists) { tracks, albums, artists ->
                Triple(tracks.size, albums.size, artists.size)
            }.collect { (trackCount, albumCount, artistCount) ->
                _uiState.update { it.copy(trackCount = trackCount, albumCount = albumCount, artistCount = artistCount) }
            }
        }
    }

    @OptIn(FlowPreview::class)
    fun onPermissionGranted() {
        rescan()
        if (!isObservingChanges) {
            isObservingChanges = true
            viewModelScope.launch {
                repository.observeMediaStoreChanges()
                    .debounce(2.seconds)
                    .collect { rescan() }
            }
        }
    }

    fun rescan() {
        viewModelScope.launch {
            repository.rescan().collect { progress ->
                when (progress) {
                    is ScanProgress.InProgress ->
                        _uiState.update { it.copy(isScanning = true, scannedCount = progress.scanned) }

                    is ScanProgress.Complete -> {
                        Timber.i("Scan complete: ${progress.result.tracks.size} tracks in ${progress.elapsedMs}ms")
                        _uiState.update { it.copy(isScanning = false, scannedCount = progress.result.tracks.size, lastScanMs = progress.elapsedMs) }
                    }
                }
            }
        }
    }
}
