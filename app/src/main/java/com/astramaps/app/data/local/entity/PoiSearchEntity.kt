package com.astramaps.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

/**
 * Full-text search index for points of interest.
 *
 * Room's `@Fts4` maps onto SQLite's **FTS4** extension, which is present in
 * Android's bundled SQLite (API 11+). FTS5 is *not* compiled into AOSP's
 * SQLite, so it is not an option without bundling a custom SQLite build —
 * see `docs/ARCHITECTURE.md`.
 *
 * FTS4 requires the primary key column to be named `rowid` and to be an
 * integer.
 */
@Fts4
@Entity(tableName = "poi_search")
data class PoiSearchEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Int,

    val name: String,
    val nameEn: String,
    val kind: String,
)
