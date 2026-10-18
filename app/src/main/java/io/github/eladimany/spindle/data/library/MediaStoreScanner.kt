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
import io.github.eladimany.spindle.data.trackinfo.FileTags
import io.github.eladimany.spindle.data.trackinfo.TrackInfoFixEntity
import io.github.eladimany.spindle.data.trackinfo.TrackInfoMerge
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

/** One MediaStore row with "missing" tags as null — the scan's first pass. */
private class RawRow(
    val id: Long,
    val title: String,
    val artist: String?,
    val artistId: Long,
    val album: String?,
    /** What MediaStore calls the album even when untagged (usually the folder's name). */
    val albumShown: String,
    val albumId: Long,
    val trackNumber: Int?,
    val discNumber: Int,
    val durationMs: Long,
    val year: Int?,
    val genre: String?,
    val folderId: Long,
    val folderName: String,
    val dateAddedMs: Long,
    val fileName: String,
    val fileKey: String,
)

/**
 * Scans MediaStore's audio collection in one cursor pass. MediaStore is the
 * primary and only scanner for v1 — see docs/PLAN.md §5.1.
 *
 * Every folder is recorded in [ScanResult.folders] regardless of [excludedFolderIds]
 * so the folder picker in Settings can still show and re-include one later. Tracks
 * whose folder is excluded are skipped from tracks/albums/artists entirely — this is
 * how chat-app voice notes and similar noise stay out of the library.
 *
 * The user's track-info fixes are applied here, so albums and artists group by the
 * fixed names: a fix naming an artist or album already in the library joins it; a new
 * one gets an id derived from its name ([nameId]).
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

    /**
     * [fixes] are the user's saved track-info fixes by file key; [TrackInfoMerge]
     * decides how each combines with the file's own tags.
     */
    fun scan(
        excludedFolderIds: Set<Long>,
        fixes: Map<String, TrackInfoFixEntity> = emptyMap(),
    ): Flow<ScanProgress> = flow {
        val startedAt = System.currentTimeMillis()
        val rows = mutableListOf<RawRow>()
        val folders = LinkedHashMap<Long, FolderAccumulator>()

        // GENRE is a plain column from API 30; before that it lives in the Genres table.
        val genreColumn = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val genresById = if (genreColumn) emptyMap() else readGenresPre30()
        // The file's place, for keying fixes: RELATIVE_PATH from API 29, the full path before.
        val relativePathColumn = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        @Suppress("DEPRECATION")
        val pathColumnName = if (relativePathColumn) MediaStore.Audio.Media.RELATIVE_PATH else MediaStore.Audio.Media.DATA

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
            MediaStore.Audio.Media.DISPLAY_NAME,
            pathColumnName,
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
            val displayNameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val pathCol = cursor.getColumnIndexOrThrow(pathColumnName)
            val genreCol = if (genreColumn) cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.GENRE) else -1

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val folderId = cursor.getLong(bucketIdCol)
                val folderName = cursor.getString(bucketNameCol) ?: "Unknown folder"

                var folderAcc = folders[folderId]
                if (folderAcc == null) {
                    folderAcc = FolderAccumulator(folderId, folderName)
                    folders[folderId] = folderAcc
                }
                folderAcc.trackCount += 1

                if (folderId in excludedFolderIds) continue

                val fileName = cursor.getString(displayNameCol).orEmpty()
                val path = cursor.getString(pathCol).orEmpty()
                val artist = cursor.getString(artistCol)?.takeUnless(TrackInfoMerge::isMissing)
                val album = cursor.getString(albumCol)
                // MediaStore packs disc+track as disc*1000+track when disc info exists.
                val rawTrack = cursor.getInt(trackCol)
                rows += RawRow(
                    id = id,
                    title = cursor.getString(titleCol) ?: "Unknown",
                    artist = artist,
                    artistId = cursor.getLong(artistIdCol),
                    // Untagged files get their folder's name as album from MediaStore.
                    album = album?.takeUnless { TrackInfoMerge.isMissing(it) || (artist == null && it == folderName) },
                    albumShown = album?.takeUnless(TrackInfoMerge::isMissing) ?: "Unknown album",
                    albumId = cursor.getLong(albumIdCol),
                    trackNumber = (if (rawTrack >= 1000) rawTrack % 1000 else rawTrack).takeIf { it > 0 },
                    discNumber = if (rawTrack >= 1000) rawTrack / 1000 else 1,
                    durationMs = cursor.getLong(durationCol),
                    year = cursor.getInt(yearCol).takeIf { it > 0 },
                    genre = (if (genreCol >= 0) cursor.getString(genreCol) else genresById[id])?.takeIf { it.isNotBlank() },
                    folderId = folderId,
                    folderName = folderName,
                    dateAddedMs = cursor.getLong(dateAddedCol) * 1000,
                    fileName = fileName,
                    fileKey = if (relativePathColumn) (path + fileName).lowercase() else path.lowercase(),
                )
                if (rows.size % 200 == 0) {
                    emit(ScanProgress.InProgress(rows.size))
                }
            }
        }

        // Names already in the library, so a fix joins the existing artist/album.
        val artistIdByName = HashMap<String, Long>()
        val albumIdByKey = HashMap<String, Long>()
        for (row in rows) {
            val artist = row.artist ?: continue
            artistIdByName.putIfAbsent(TrackInfoMerge.norm(artist), row.artistId)
            row.album?.let { albumIdByKey.putIfAbsent(albumKey(it, artist), row.albumId) }
        }

        val tracks = ArrayList<TrackEntity>(rows.size)
        val albums = LinkedHashMap<Long, AlbumAccumulator>()
        val artists = LinkedHashMap<Long, ArtistAccumulator>()
        for (row in rows) {
            val tags = TrackInfoMerge.merge(FileTags(row.title, row.artist, row.album, row.year, row.genre), fixes[row.fileKey])
            val artistName = tags.artist ?: "Unknown artist"
            val albumName = tags.album ?: row.albumShown
            val artistId = if (tags.artist == row.artist) {
                row.artistId
            } else {
                artistIdByName.getOrPut(TrackInfoMerge.norm(artistName)) { nameId("artist|" + TrackInfoMerge.norm(artistName)) }
            }
            val albumId = if (tags.album == row.album && tags.artist == row.artist) {
                row.albumId
            } else {
                val key = albumKey(albumName, artistName)
                albumIdByKey.getOrPut(key) { nameId("album|$key") }
            }

            tracks += TrackEntity(
                id = row.id,
                uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, row.id).toString(),
                title = tags.title,
                titleSortKey = tags.title.lowercase(),
                artistId = artistId,
                artistName = artistName,
                albumId = albumId,
                albumName = albumName,
                trackNumber = row.trackNumber,
                discNumber = row.discNumber,
                durationMs = row.durationMs,
                year = tags.year,
                genre = tags.genre,
                folderId = row.folderId,
                folderName = row.folderName,
                dateAddedMs = row.dateAddedMs,
                fileKey = row.fileKey,
                fileName = row.fileName,
                fileArtistMissing = row.artist == null,
                infoFixed = tags.fixed,
            )

            val albumAcc = albums.getOrPut(albumId) { AlbumAccumulator(albumId, albumName, artistId, artistName, tags.year) }
            albumAcc.trackCount += 1
            val artistAcc = artists.getOrPut(artistId) { ArtistAccumulator(artistId, artistName) }
            artistAcc.trackCount += 1
            artistAcc.albumIds.add(albumId)
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

    private fun albumKey(album: String, artist: String) = TrackInfoMerge.norm(album) + "|" + TrackInfoMerge.norm(artist)

    /** A stable negative id from a name (FNV-1a 64) — MediaStore's ids are positive. */
    private fun nameId(name: String): Long {
        var hash = -0x340d631b7bdddcdbL
        for (c in name) {
            hash = hash xor c.code.toLong()
            hash *= 0x100000001b3L
        }
        return -(hash and Long.MAX_VALUE) - 1
    }

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
