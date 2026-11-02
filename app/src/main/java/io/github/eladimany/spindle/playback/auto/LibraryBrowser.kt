package io.github.eladimany.spindle.playback.auto

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.data.db.dao.AlbumDao
import io.github.eladimany.spindle.data.db.dao.ArtistDao
import io.github.eladimany.spindle.data.db.dao.PlaylistDao
import io.github.eladimany.spindle.data.db.dao.TrackDao
import io.github.eladimany.spindle.data.library.toDomain
import io.github.eladimany.spindle.di.IoDispatcher
import io.github.eladimany.spindle.playback.PlaybackController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The library as Android Auto browses it. Auto draws its own screens from this tree:
 * root children are its tabs (at most 4), browsable items open lists or grids, playable
 * ones start playback through [play].
 *
 * Ids: tabs `mix` `playlists` `albums` `artists`; lists `recent`, `playlist/<id>`,
 * `album/<id>`, `artist/<id>`; a song `t|<list>|<trackId>` (tapping it plays the
 * whole list from there); `shuffle|<list>` and `smart|<list>`, where `<list>` may
 * be `all` (the library).
 */
@Singleton
class LibraryBrowser @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trackDao: TrackDao,
    private val albumDao: AlbumDao,
    private val artistDao: ArtistDao,
    private val playlistDao: PlaylistDao,
    private val controller: PlaybackController,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    fun root(): MediaItem = browsable(ROOT, "Spindle", extras = styleExtras(LIST, LIST))

    /** Auto may only open the album grid once it may read the covers. */
    fun grantArtwork(packageName: String) {
        runCatching {
            context.grantUriPermission(
                packageName,
                ArtworkProvider.albumPrefix(context),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
        }
    }

    suspend fun children(parentId: String): List<MediaItem> = withContext(io) {
        when {
            parentId == ROOT -> listOf(
                browsable(MIX, "For you", extras = styleExtras(LIST, LIST)),
                browsable(PLAYLISTS, "Playlists", extras = styleExtras(LIST, LIST)),
                browsable(ALBUMS, "Albums", extras = styleExtras(GRID, LIST)),
                browsable(ARTISTS, "Artists", extras = styleExtras(LIST, LIST)),
            )
            parentId == MIX -> listOf(
                action("$SMART|$ALL", "Smart shuffle", "Your whole library, from what you like"),
                action("$SHUFFLE|$ALL", "Shuffle all", null),
                browsable(RECENT, "Recently added", subtitle = "The newest 100 songs"),
            )
            parentId == PLAYLISTS -> playlistDao.observeAllWithCounts().first().map {
                browsable("playlist/${it.id}", it.name, subtitle = songs(it.trackCount), mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST)
            }
            parentId == ALBUMS -> albumDao.observeAll().first().map {
                browsable(
                    "album/${it.id}", it.name,
                    subtitle = it.artistName,
                    artwork = ArtworkProvider.albumUri(context, it.id),
                    mediaType = MediaMetadata.MEDIA_TYPE_ALBUM,
                )
            }
            parentId == ARTISTS -> artistDao.observeAll().first().map {
                browsable("artist/${it.id}", it.name, subtitle = songs(it.trackCount), mediaType = MediaMetadata.MEDIA_TYPE_ARTIST)
            }
            else -> {
                val tracks = tracksOf(parentId) ?: return@withContext emptyList()
                val head = if (parentId.startsWith("album/")) emptyList() else listOf(action("$SHUFFLE|$parentId", "Shuffle", songs(tracks.size)))
                head + tracks.map { song(parentId, it) }
            }
        }
    }

    suspend fun item(mediaId: String): MediaItem? = withContext(io) {
        val trackId = mediaId.split('|').takeIf { it.size == 3 && it[0] == TRACK }?.get(2)?.toLongOrNull()
        when {
            trackId != null -> trackDao.getById(trackId)?.toDomain()?.let { song(mediaId.split('|')[1], it) }
            mediaId == ROOT -> root()
            else -> MediaItem.Builder().setMediaId(mediaId).build()
        }
    }

    suspend fun search(query: String): List<MediaItem> = withContext(io) {
        // '|' separates the parts of a song's id.
        val cleaned = query.replace('|', ' ').trim()
        trackDao.search(cleaned, SEARCH_LIMIT).first().map { song("$SEARCH/$cleaned", it.toDomain()) }
    }

    /**
     * Plays what Auto asked for: a song (its list from there), a shuffle action, or a
     * voice request ("play … on Spindle") — search results, or a smart shuffle of
     * everything when nothing was named. Returns false when there's nothing to play.
     */
    suspend fun play(mediaId: String, searchQuery: String?): Boolean {
        if (mediaId.isEmpty()) {
            val query = searchQuery?.trim().orEmpty()
            if (query.isEmpty()) return play("$SMART|$ALL", null)
            val found = withContext(io) { trackDao.search(query, SEARCH_LIMIT).first().map { it.toDomain() } }
            if (found.isEmpty()) return false
            controller.playTracks(found, 0)
            return true
        }
        val parts = mediaId.split('|')
        val list = parts.getOrNull(1) ?: return false
        val tracks = withContext(io) { tracksOf(list) }.orEmpty()
        if (tracks.isEmpty()) return false
        when (parts[0]) {
            TRACK -> {
                val id = parts.getOrNull(2)?.toLongOrNull()
                controller.playTracks(tracks, tracks.indexOfFirst { it.id == id }.coerceAtLeast(0))
            }
            SHUFFLE -> controller.playTracksShuffled(tracks)
            SMART -> controller.playTracksSmartShuffled(tracks)
            else -> return false
        }
        return true
    }

    private suspend fun tracksOf(list: String): List<Track>? {
        val id = list.substringAfter('/').toLongOrNull()
        return when {
            list == ALL -> trackDao.getAllByTitle()
            list == RECENT -> trackDao.getAllByDateAdded().take(RECENT_COUNT)
            list.startsWith("$SEARCH/") -> trackDao.search(list.substringAfter('/'), SEARCH_LIMIT).first()
            id == null -> return null
            list.startsWith("playlist/") -> playlistDao.observeTracks(id).first()
            list.startsWith("album/") -> trackDao.observeByAlbum(id).first()
            list.startsWith("artist/") -> trackDao.observeByArtist(id).first()
            else -> return null
        }.map { it.toDomain() }
    }

    private fun song(list: String, track: Track): MediaItem = MediaItem.Builder()
        .setMediaId("$TRACK|$list|${track.id}")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artistName)
                .setAlbumTitle(track.albumName)
                .setArtworkUri(ArtworkProvider.albumUri(context, track.albumId))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build(),
        )
        .build()

    private fun action(mediaId: String, title: String, subtitle: String?): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                .build(),
        )
        .build()

    private fun browsable(
        mediaId: String,
        title: String,
        subtitle: String? = null,
        artwork: android.net.Uri? = null,
        mediaType: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
        extras: Bundle? = null,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtworkUri(artwork)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(mediaType)
                .setExtras(extras)
                .build(),
        )
        .build()

    private fun songs(count: Int) = if (count == 1) "1 song" else "$count songs"

    /** Auto's content-style hints: how this item's children are drawn. */
    private fun styleExtras(browsable: Int, playable: Int) = Bundle().apply {
        putInt(STYLE_BROWSABLE, browsable)
        putInt(STYLE_PLAYABLE, playable)
    }

    companion object {
        const val ROOT = "root"
        private const val MIX = "mix"
        private const val PLAYLISTS = "playlists"
        private const val ALBUMS = "albums"
        private const val ARTISTS = "artists"
        private const val RECENT = "recent"
        private const val ALL = "all"
        private const val SEARCH = "search"
        private const val TRACK = "t"
        private const val SHUFFLE = "shuffle"
        private const val SMART = "smart"
        private const val RECENT_COUNT = 100
        private const val SEARCH_LIMIT = 50

        // androidx.media.utils.MediaConstants values (that artifact isn't a dependency).
        private const val STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        private const val STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        const val STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        const val SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
        private const val LIST = 1
        private const val GRID = 2
    }
}
