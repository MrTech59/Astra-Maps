package com.astramaps.app.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import com.astramaps.app.data.local.entity.InstalledPackEntity
import com.astramaps.app.data.local.entity.InstalledRoutingCellEntity
import com.astramaps.app.data.local.entity.PoiSearchEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PackDao {

    @Query("SELECT * FROM installed_pack")
    fun allPacks(): Flow<List<InstalledPackEntity>>

    @Query("SELECT * FROM installed_pack WHERE packId = :packId")
    suspend fun pack(packId: String): InstalledPackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPack(pack: InstalledPackEntity)

    @Query("DELETE FROM installed_pack WHERE packId = :packId")
    suspend fun deletePack(packId: String)

    @Query("SELECT * FROM installed_routing_cell WHERE packId = :packId")
    fun routingCells(packId: String): Flow<List<InstalledRoutingCellEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRoutingCell(cell: InstalledRoutingCellEntity)

    @Query("SELECT * FROM installed_routing_cell WHERE cellName = :cellName LIMIT 1")
    suspend fun routingCell(cellName: String): InstalledRoutingCellEntity?

    @Transaction
    suspend fun replacePack(pack: InstalledPackEntity, cells: List<InstalledRoutingCellEntity>) {
        deletePack(pack.packId)
        upsertPack(pack)
        cells.forEach { upsertRoutingCell(it.copy(packId = pack.packId)) }
    }
}

@Dao
interface PoiSearchDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<PoiSearchEntity>)

    @Query("DELETE FROM poi_search")
    suspend fun clear()

    /**
     * Prefix match on the FTS4 index.
     *
     * `MATCH` treats its argument as a query expression, so a raw user string
     * must be sanitised by the caller before it reaches this method.
     *
     * FTS4 has **no `rank` column** — that is an FTS5 feature, and AOSP's
     * SQLite does not ship FTS5. Results are therefore returned in rowid
     * order; Phase 2 adds an FTS4 `matchinfo()`-based relevance score.
     */
    @Query(
        "SELECT rowid, name, nameEn, kind FROM poi_search " +
            "WHERE poi_search MATCH :query LIMIT :limit"
    )
    suspend fun search(query: String, limit: Int): List<PoiSearchEntity>
}

/**
 * The on-device database.
 *
 * **Schema policy (pre-1.0).** There are no `Migration`s: a version bump makes
 * [com.astramaps.app.di.AppContainer] drop and recreate every table. That is
 * deliberate, because all three tables are rebuildable — the pack registry can
 * be reconstructed by scanning the packs directory, the routing-cell rows from
 * the `.rd5` files on disk, and `poi_search` from a pack's place index.
 *
 * Version 2 is not a format change. Version 1 was never released: builds from
 * before the entities were finalised wrote a v1 file whose identity hash no
 * longer matches the generated one, and Room does **not** migrate a same-version
 * file with a foreign hash — it throws `Room cannot verify the data integrity`
 * from `checkIdentity()` and the app crashes on launch. Bumping the version
 * routes those files through the destructive fallback instead.
 *
 * Once packs ship this must change: dropping the registry would make the app
 * forget packs the user already downloaded until the directory is rescanned,
 * so later schema changes need a real `Migration` (or a rescan-on-open step).
 */
@Database(
    entities = [
        InstalledPackEntity::class,
        InstalledRoutingCellEntity::class,
        PoiSearchEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AstraDatabase : RoomDatabase() {

    abstract fun packDao(): PackDao

    abstract fun poiSearchDao(): PoiSearchDao

    companion object {
        const val NAME = "astra.db"
    }
}
