package com.astramaps.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Bookkeeping for a downloaded regional pack.
 *
 * One row per installed pack. The actual payloads (PMTiles archive, POI index,
 * BRouter `.rd5` cells) live on the filesystem; this table records what is
 * present, which version, and enough metadata to decide whether an update is
 * needed without re-reading the archives.
 */
@Entity(tableName = "installed_pack")
data class InstalledPackEntity(
    /** Stable pack identifier, e.g. `astra-de-01`. */
    @PrimaryKey
    val packId: String,

    /** Pack release version, e.g. `1.0.0`. */
    val version: String,

    /** Version of the tile schema the pack was built with. */
    val schemaVersion: String,

    /** OSM data build date the pack was generated from (ISO-8601). */
    val osmBuildDate: String,

    val bboxWest: Double,
    val bboxSouth: Double,
    val bboxEast: Double,
    val bboxNorth: Double,

    /** Absolute path to the PMTiles map archive. */
    val mapArchivePath: String,

    /** Absolute path to the local POI/place/street SQLite index. */
    val poiIndexPath: String?,

    /** Lookup version of the installed `lookups.dat`, for staleness checks. */
    val routingLookupVersion: Int?,

    val installedAtEpochMillis: Long,
    val totalBytes: Long,
)
