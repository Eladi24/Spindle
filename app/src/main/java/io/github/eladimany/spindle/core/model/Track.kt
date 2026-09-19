package io.github.eladimany.spindle.core.model

data class Track(
    val id: Long,
    val uri: String,
    val title: String,
    val artistId: Long,
    val artistName: String,
    val albumId: Long,
    val albumName: String,
    val trackNumber: Int?,
    val discNumber: Int?,
    val durationMs: Long,
    val year: Int?,
    val genre: String?,
    val dateAddedMs: Long,
)
