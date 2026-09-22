package io.github.eladimany.spindle.core.model

/** A BluOS player found on the LAN (via [io.github.eladimany.spindle.data.bluos.BluOsDiscovery] or manual entry). */
data class BluOsPlayer(
    val name: String,
    val host: String,
    val port: Int = 11000,
)
