package io.github.eladimany.spindle.core.model

data class OutputCapabilities(
    val canSeek: Boolean,
    val canSetVolume: Boolean,
    val isGapless: Boolean,
)
