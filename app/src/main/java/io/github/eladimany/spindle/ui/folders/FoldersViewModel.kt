package io.github.eladimany.spindle.ui.folders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.eladimany.spindle.core.model.Folder
import io.github.eladimany.spindle.data.library.LibraryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FoldersViewModel @Inject constructor(
    private val repository: LibraryRepository,
) : ViewModel() {

    val folders: StateFlow<List<Folder>> = repository.folders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFolderExcluded(folderId: Long, excluded: Boolean) {
        viewModelScope.launch {
            repository.setFolderExcluded(folderId, excluded)
            repository.rescan().collect { }
        }
    }
}
