package io.github.eladimany.spindle.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.eladimany.spindle.core.model.Folder
import io.github.eladimany.spindle.data.library.LibraryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class FolderBrowseViewModel @Inject constructor(
    libraryRepository: LibraryRepository,
) : ViewModel() {
    val folders: StateFlow<List<Folder>> = libraryRepository.browsableFolders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
