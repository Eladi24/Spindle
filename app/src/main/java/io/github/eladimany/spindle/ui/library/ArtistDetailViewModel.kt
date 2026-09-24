package io.github.eladimany.spindle.ui.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.eladimany.spindle.core.model.Album
import io.github.eladimany.spindle.core.model.Artist
import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.data.artists.ArtistArtworkRepository
import io.github.eladimany.spindle.data.history.PlayEventDao
import io.github.eladimany.spindle.data.history.trackKey
import io.github.eladimany.spindle.data.library.ArtworkRepository
import io.github.eladimany.spindle.data.library.LibraryRepository
import io.github.eladimany.spindle.playback.PlaybackController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class ArtistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val libraryRepository: LibraryRepository,
    private val artworkRepository: ArtworkRepository,
    private val artistArtworkRepository: ArtistArtworkRepository,
    private val playEventDao: PlayEventDao,
    private val playbackController: PlaybackController,
) : ViewModel() {

    private val artistId: Long = checkNotNull(savedStateHandle["artistId"])

    private val _artist = MutableStateFlow<Artist?>(null)
    val artist: StateFlow<Artist?> = _artist.asStateFlow()

    val albums: StateFlow<List<Album>> = libraryRepository.albumsForArtist(artistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tracks: StateFlow<List<Track>> = libraryRepository.tracksForArtist(artistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Plays per track, from listening history — loaded once when the screen opens. */
    private val playCounts = MutableStateFlow<Map<Long, Int>>(emptyMap())

    /** Most played first (then title order); [hasHistory] says whether any were played. */
    val tracksByPlays: StateFlow<RankedTracks> = combine(tracks, playCounts) { list, plays ->
        val counts = list.associate { it.id to (plays[trackKey(it)] ?: 0) }
        RankedTracks(
            tracks = list.sortedByDescending { counts[it.id] ?: 0 },
            hasHistory = counts.values.any { it > 0 },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RankedTracks(emptyList(), false))

    /** The artist photo, if the user fetched one (opt-in per artist, never automatic). */
    val photoUrl: StateFlow<String?> = artistArtworkRepository.artworkByArtistId
        .map { it[artistId] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _fetchingPhoto = MutableStateFlow(false)
    val fetchingPhoto: StateFlow<Boolean> = _fetchingPhoto.asStateFlow()

    val currentTrackId: StateFlow<Long?> = playbackController.currentTrackId

    init {
        viewModelScope.launch {
            _artist.value = libraryRepository.artistById(artistId)
        }
        viewModelScope.launch {
            playCounts.value = withContext(Dispatchers.IO) {
                playEventDao.statsByTrackKey().associate { it.trackKey to it.plays }
            }
        }
    }

    /** Same Deezer lookup as the Artists tab's "+" avatar — only ever on the user's tap. */
    fun fetchPhoto() {
        val artist = _artist.value ?: return
        if (_fetchingPhoto.value) return
        _fetchingPhoto.value = true
        viewModelScope.launch {
            try {
                artistArtworkRepository.fetch(artist.id, artist.name)
            } catch (e: Exception) {
                Timber.w(e, "Artist artwork fetch failed for %s", artist.name)
            } finally {
                _fetchingPhoto.value = false
            }
        }
    }

    fun playAll() {
        val all = tracksByPlays.value.tracks
        if (all.isNotEmpty()) playbackController.playTracks(all)
    }

    fun playShuffled() {
        if (tracks.value.isNotEmpty()) playbackController.playTracksShuffled(tracks.value)
    }

    fun playSmartShuffled() {
        if (tracks.value.isNotEmpty()) playbackController.playTracksSmartShuffled(tracks.value)
    }

    fun playTrack(track: Track) {
        val all = tracksByPlays.value.tracks
        playbackController.playTracks(all, all.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
    }

    fun addToQueue(tracks: List<Track>) = playbackController.addToQueue(tracks)

    suspend fun artworkUriForAlbum(album: Album): String? = artworkRepository.artworkUriForAlbum(album)
    suspend fun artworkUriFor(track: Track): String? = artworkRepository.artworkUriFor(track)
}

data class RankedTracks(val tracks: List<Track>, val hasHistory: Boolean)
