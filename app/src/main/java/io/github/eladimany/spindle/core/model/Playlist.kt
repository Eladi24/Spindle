package io.github.eladimany.spindle.core.model

data class Playlist(
    val id: Long,
    val name: String,
    val trackCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val totalDurationMs: Long = 0,
)
