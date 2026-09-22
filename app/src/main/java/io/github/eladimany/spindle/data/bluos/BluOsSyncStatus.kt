package io.github.eladimany.spindle.data.bluos

/**
 * A parsed `/SyncStatus` response. Field set verified against a real Node
 * (2026-09-22, `NODE` model, firmware 4.16.22):
 *
 * ```xml
 * <SyncStatus etag="66" syncStat="66" version="4.16.22" id="10.0.0.9:11000" db="0"
 *   volume="100" name="Living Room HI-Fi" model="N132" modelName="NODE"
 *   class="streamer" icon="/images/players/N125_nt.png" brand="Bluesound"
 *   schemaVersion="34" initialized="true" mac="90:56:82:98:B4:42">
 *   <pairWithSub/> <bluetoothOutput/>
 * </SyncStatus>
 * ```
 *
 * Unlike `/Status`, every field here is a root-element **attribute**, not a
 * child element. Only `etag`/`volume`/`name` are modeled — the rest
 * (`model`, `mac`, grouping via `pairWithSub`, ...) exist in the real
 * response but aren't needed yet; add them here, verified, when they are.
 */
data class BluOsSyncStatus(
    val etag: String?,
    val volume: Int?,
    val name: String?,
)
