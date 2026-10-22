package com.astramaps.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One BRouter routing cell (`.rd5`) belonging to an installed pack.
 *
 * BRouter ships routing data on a fixed 5°×5° global grid. A pack therefore
 * references whole cells, not user-defined areas.
 */
@Entity(
    tableName = "installed_routing_cell",
    foreignKeys = [
        ForeignKey(
            entity = InstalledPackEntity::class,
            parentColumns = ["packId"],
            childColumns = ["packId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("packId"), Index(value = ["cellName"], unique = true)],
)
data class InstalledRoutingCellEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val packId: String,

    /** BRouter cell name, e.g. `E10_N50`. */
    val cellName: String,

    /** Absolute path to the `.rd5` file on disk. */
    val filePath: String,

    /** `readVersion` embedded in the cell; compared against `lookups.dat`. */
    val readVersion: Int,

    /** MD5 of the installed file — used to locate the matching `.df5` delta. */
    val md5: String?,

    val sizeBytes: Long,
)
