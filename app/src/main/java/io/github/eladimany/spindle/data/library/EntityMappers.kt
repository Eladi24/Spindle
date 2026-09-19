package io.github.eladimany.spindle.data.library

import io.github.eladimany.spindle.core.model.Album
import io.github.eladimany.spindle.core.model.Artist
import io.github.eladimany.spindle.core.model.Folder
import io.github.eladimany.spindle.core.model.Track
import io.github.eladimany.spindle.data.db.entity.AlbumEntity
import io.github.eladimany.spindle.data.db.entity.ArtistEntity
import io.github.eladimany.spindle.data.db.entity.FolderEntity
import io.github.eladimany.spindle.data.db.entity.TrackEntity

fun TrackEntity.toDomain() = Track(
    id = id,
    uri = uri,
    title = title,
    artistId = artistId,
    artistName = artistName,
    albumId = albumId,
    albumName = albumName,
    trackNumber = trackNumber,
    discNumber = discNumber,
    durationMs = durationMs,
    year = year,
    genre = genre,
    dateAddedMs = dateAddedMs,
)

fun AlbumEntity.toDomain() = Album(
    id = id,
    name = name,
    artistId = artistId,
    artistName = artistName,
    year = year,
    trackCount = trackCount,
    artworkUri = artworkUri,
)

fun ArtistEntity.toDomain() = Artist(
    id = id,
    name = name,
    albumCount = albumCount,
    trackCount = trackCount,
)

fun FolderEntity.toDomain(isExcluded: Boolean) = Folder(
    id = id,
    name = name,
    trackCount = trackCount,
    isExcluded = isExcluded,
)
