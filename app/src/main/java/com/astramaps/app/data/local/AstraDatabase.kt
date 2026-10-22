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

@Database(
    entities = [
        InstalledPackEntity::class,
        InstalledRoutingCellEntity::class,
        PoiSearchEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AstraDatabase : RoomDatabase() {

    abstract fun packDao(): PackDao

    abstract fun poiSearchDao(): PoiSearchDao

    companion object {
        const val NAME = "astra.db"
    }
}
