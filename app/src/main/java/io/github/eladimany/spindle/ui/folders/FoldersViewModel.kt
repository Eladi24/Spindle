package io.github.eladimany.spindle.ui.folders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.eladimany.spindle.core.model.Folder
import io.github.eladimany.spindle.data.library.LibraryRepository
import io.github.eladimany.spindle.data.library.ScanProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FoldersViewModel @Inject constructor(
    private val repository: LibraryRepository,
) : ViewModel() {

    val folders: StateFlow<List<Folder>> = repository.folders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    fun setFolderExcluded(folderId: Long, excluded: Boolean) {
        viewModelScope.launch {
            repository.setFolderExcluded(folderId, excluded)
            runRescan()
        }
    }

    /** The manual "rescan" action — same underlying scan as permission-grant/exclusion-toggle,
     * for when a stuck or stale scan needs an explicit kick. */
    fun rescanLibrary() {
        viewModelScope.launch { runRescan() }
    }

    private suspend fun runRescan() {
        _isScanning.value = true
        repository.rescan().collect { progress ->
            if (progress is ScanProgress.Complete) _isScanning.value = false
        }
    }
}
