package io.github.eladimany.spindle.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A fetched artist photo URL, keyed by artist id. Deliberately a **separate
 * table from [ArtistEntity]**, not a column on it: `MediaStoreScanner`
 * upserts a fresh `ArtistEntity` for every artist on every rescan (manual or
 * `ContentObserver`-triggered), which would silently wipe a column living on
 * that same row back to null. This table is never touched by a scan.
 */
@Entity(tableName = "artist_artwork")
data class ArtistArtworkEntity(
    @PrimaryKey val artistId: Long,
    val imageUrl: String,
)
