package io.github.eladimany.spindle.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.eladimany.spindle.core.model.Album
import io.github.eladimany.spindle.data.library.ArtworkRepository
import io.github.eladimany.spindle.data.library.LibraryRepository
import io.github.eladimany.spindle.data.library.toDomain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@HiltViewModel
class AlbumsViewModel @Inject constructor(
    libraryRepository: LibraryRepository,
    private val artworkRepository: ArtworkRepository,
) : ViewModel() {
    val albums: Flow<PagingData<Album>> =
        Pager(PagingConfig(pageSize = 60, enablePlaceholders = false)) {
            libraryRepository.albumsPagingSource()
        }.flow.map { it.map { entity -> entity.toDomain() } }.cachedIn(viewModelScope)

    suspend fun artworkUriForAlbum(album: Album): String? = artworkRepository.artworkUriForAlbum(album)
}
