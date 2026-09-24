package io.github.eladimany.spindle.data.bluos

/**
 * A parsed `/Status` response — field set and meaning measured against real
 * hardware, see `docs/bluos-api.md`. Only what's actually verified there is
 * represented; do not add a field by guessing the schema.
 */
data class BluOsStatus(
    /** Feed back into the next long-poll's `etag=` param. Absent means the response couldn't be parsed as expected. */
    val etag: String?,
    /** Raw `<state>` value — e.g. "stream", "stop", "pause". Never assume the full set is known; compare against string constants, don't exhaustively branch. */
    val state: String,
    val secs: Int,
    /** Null when nothing is loaded — `totlen` (and `streamUrl`, `streamFormat`, `service`) disappear entirely once `state` collapses to "stop". */
    val totalSeconds: Int?,
    val canSeek: Boolean,
    val streamUrl: String?,
    /** What the Node displays — only populated when we send an `icy-name` header ourselves; see `MediaHttpServer`. */
    val title1: String?,
    val quality: String?,
    val streamFormat: String?,
    val serviceType: String?,
) {
    /** True when something else (Bluetooth, an analog input, ...) has taken over the Node — the HTTP stream is dead, don't fight it. */
    val isHijacked: Boolean get() = serviceType == "AudioInputs"
}
