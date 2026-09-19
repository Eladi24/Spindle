package io.github.eladimany.spindle.core.model

data class Folder(
    val id: Long,
    val name: String,
    val trackCount: Int,
    val isExcluded: Boolean,
)
