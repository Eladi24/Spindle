package io.github.eladimany.spindle.core.model

data class Album(
    val id: Long,
    val name: String,
    val artistId: Long,
    val artistName: String,
    val year: Int?,
    val trackCount: Int,
    val artworkUri: String?,
)
