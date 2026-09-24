package io.github.eladimany.spindle.data.library

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.eladimany.spindle.data.db.entity.AlbumEntity
import io.github.eladimany.spindle.data.db.entity.ArtistEntity
import io.github.eladimany.spindle.data.db.entity.FolderEntity
import io.github.eladimany.spindle.data.db.entity.TrackEntity
import io.github.eladimany.spindle.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject

sealed interface ScanProgress {
    data class InProgress(val scanned: Int) : ScanProgress
    data class Complete(val result: MediaStoreScanner.ScanResult, val elapsedMs: Long) : ScanProgress
}

private class AlbumAccumulator(
    val id: Long,
    val name: String,
    val artistId: Long,
    val artistName: String,
    val year: Int?,
) {
    var trackCount = 0
}

private class ArtistAccumulator(val id: Long, val name: String) {
    val albumIds = mutableSetOf<Long>()
    var trackCount = 0
}

private class FolderAccumulator(val id: Long, val name: String) {
    var trackCount = 0
}

/**
 * Scans MediaStore's audio collection in one cursor pass. MediaStore is the
 * primary and only scanner for v1 — see docs/PLAN.md §5.1.
 *
 * Every folder is recorded in [ScanResult.folders] regardless of [excludedFolderIds]
 * so the folder picker in Settings can still show and re-include one later. Tracks
 * whose folder is excluded are skipped from tracks/albums/artists entirely — this is
 * how chat-app voice notes and similar noise stay out of the library.
 */
class MediaStoreScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    data class ScanResult(
        val tracks: List<TrackEntity>,
        val albums: List<AlbumEntity>,
        val artists: List<ArtistEntity>,
        val folders: List<FolderEntity>,
    )

    fun scan(excludedFolderIds: Set<Long>): Flow<ScanProgress> = flow {
        val startedAt = System.currentTimeMillis()
        val tracks = mutableListOf<TrackEntity>()
        val albums = LinkedHashMap<Long, AlbumAccumulator>()
        val artists = LinkedHashMap<Long, ArtistAccumulator>()
        val folders = LinkedHashMap<Long, FolderAccumulator>()

        // GENRE is a plain column from API 30; before that it lives in the Genres table.
        val genreColumn = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val genresById = if (genreColumn) emptyMap() else readGenresPre30()

        val projection = listOfNotNull(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ARTIST_ID,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.BUCKET_ID,
            MediaStore.Audio.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Audio.Media.DATE_ADDED,
            if (genreColumn) MediaStore.Audio.Media.GENRE else null,
        ).toTypedArray()
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val artistIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val yearCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.BUCKET_ID)
            val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
            val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val genreCol = if (genreColumn) cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.GENRE) else -1

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val title = cursor.getString(titleCol) ?: "Unknown"
                val artistId = cursor.getLong(artistIdCol)
                val artistName = cursor.getString(artistCol) ?: "Unknown artist"
                val albumId = cursor.getLong(albumIdCol)
                val albumName = cursor.getString(albumCol) ?: "Unknown album"
                val durationMs = cursor.getLong(durationCol)
                val year = cursor.getInt(yearCol).takeIf { it > 0 }
                val folderId = cursor.getLong(bucketIdCol)
                val folderName = cursor.getString(bucketNameCol) ?: "Unknown folder"
                val dateAddedMs = cursor.getLong(dateAddedCol) * 1000
                val genre = (if (genreCol >= 0) cursor.getString(genreCol) else genresById[id])
                    ?.takeIf { it.isNotBlank() }

                // MediaStore packs disc+track as disc*1000+track when disc info exists.
                val rawTrack = cursor.getInt(trackCol)
                val discNumber = if (rawTrack >= 1000) rawTrack / 1000 else 1
                val trackNumber = (if (rawTrack >= 1000) rawTrack % 1000 else rawTrack).takeIf { it > 0 }

                var folderAcc = folders[folderId]
                if (folderAcc == null) {
                    folderAcc = FolderAccumulator(folderId, folderName)
                    folders[folderId] = folderAcc
                }
                folderAcc.trackCount += 1

                if (folderId in excludedFolderIds) continue

                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()

                tracks += TrackEntity(
                    id = id,
                    uri = uri,
                    title = title,
                    titleSortKey = title.lowercase(),
                    artistId = artistId,
                    artistName = artistName,
                    albumId = albumId,
                    albumName = albumName,
                    trackNumber = trackNumber,
                    discNumber = discNumber,
                    durationMs = durationMs,
                    year = year,
                    genre = genre,
                    folderId = folderId,
                    folderName = folderName,
                    dateAddedMs = dateAddedMs,
                )

                var albumAcc = albums[albumId]
                if (albumAcc == null) {
                    albumAcc = AlbumAccumulator(albumId, albumName, artistId, artistName, year)
                    albums[albumId] = albumAcc
                }
                albumAcc.trackCount += 1

                var artistAcc = artists[artistId]
                if (artistAcc == null) {
                    artistAcc = ArtistAccumulator(artistId, artistName)
                    artists[artistId] = artistAcc
                }
                artistAcc.trackCount += 1
                artistAcc.albumIds.add(albumId)

                if (tracks.size % 200 == 0) {
                    emit(ScanProgress.InProgress(tracks.size))
                }
            }
        }

        val albumEntities = albums.values.map { acc ->
            AlbumEntity(
                id = acc.id,
                name = acc.name,
                nameSortKey = acc.name.lowercase(),
                artistId = acc.artistId,
                artistName = acc.artistName,
                year = acc.year,
                trackCount = acc.trackCount,
                artworkUri = null,
            )
        }
        val artistEntities = artists.values.map { acc ->
            ArtistEntity(
                id = acc.id,
                name = acc.name,
                nameSortKey = acc.name.lowercase(),
                albumCount = acc.albumIds.size,
                trackCount = acc.trackCount,
            )
        }
        val folderEntities = folders.values.map { acc ->
            FolderEntity(id = acc.id, name = acc.name, trackCount = acc.trackCount)
        }

        emit(
            ScanProgress.Complete(
                result = ScanResult(tracks, albumEntities, artistEntities, folderEntities),
                elapsedMs = System.currentTimeMillis() - startedAt,
            ),
        )
    }.flowOn(ioDispatcher)

    /** API 26–29: audio id → its genre name(s), joined with "; " when a track has several. */
    private fun readGenresPre30(): Map<Long, String> {
        val byId = HashMap<Long, String>()
        val genres = context.contentResolver.query(
            MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME),
            null, null, null,
        ) ?: return byId
        genres.use {
            while (it.moveToNext()) {
                val genreId = it.getLong(0)
                val name = it.getString(1)?.takeIf { n -> n.isNotBlank() } ?: continue
                context.contentResolver.query(
                    MediaStore.Audio.Genres.Members.getContentUri("external", genreId),
                    arrayOf(MediaStore.Audio.Genres.Members.AUDIO_ID),
                    null, null, null,
                )?.use { members ->
                    while (members.moveToNext()) {
                        val audioId = members.getLong(0)
                        byId[audioId] = byId[audioId]?.let { prev -> "$prev; $name" } ?: name
                    }
                }
            }
        }
        return byId
    }
}
