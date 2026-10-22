package com.astramaps.app.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.astramaps.app.data.local.entity.InstalledPackEntity
import com.astramaps.app.data.local.entity.InstalledRoutingCellEntity
import com.astramaps.app.data.local.entity.PoiSearchEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises the Room database end-to-end on the JVM.
 *
 * This is the test that proves the KSP + Room 2.8.5 code generation round trip
 * actually works: the DAOs below only exist because KSP generated the
 * `AstraDatabase_Impl` from the annotated entities.
 */
@RunWith(RobolectricTestRunner::class)
class AstraDatabaseTest {

    private lateinit var db: AstraDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AstraDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `pack and routing cell can be stored and read back`() = runTest {
        val dao = db.packDao()

        dao.replacePack(
            pack = InstalledPackEntity(
                packId = "astra-de-01",
                version = "1.0.0",
                schemaVersion = "1.0.0",
                osmBuildDate = "2026-10-20",
                bboxWest = 13.08283,
                bboxSouth = 52.33446,
                bboxEast = 13.762245,
                bboxNorth = 52.6783,
                mapArchivePath = "/data/astra/astra-de-01.pmtiles",
                poiIndexPath = "/data/astra/astra-de-01.poi.db",
                routingLookupVersion = 11,
                installedAtEpochMillis = 1_700_000_000_000,
                totalBytes = 161_347_847,
            ),
            cells = listOf(
                InstalledRoutingCellEntity(
                    packId = "astra-de-01",
                    cellName = "E10_N50",
                    filePath = "/data/astra/E10_N50.rd5",
                    readVersion = 11,
                    md5 = null,
                    sizeBytes = 125_081_401,
                ),
            ),
        )

        val pack = dao.pack("astra-de-01")
        assertNotNull(pack)
        assertEquals("astra-de-01", pack!!.packId)
        assertEquals(11, pack.routingLookupVersion)

        val cell = dao.routingCell("E10_N50")
        assertNotNull(cell)
        assertEquals(11, cell!!.readVersion)
        assertEquals(125_081_401L, cell.sizeBytes)

        assertEquals(1, dao.allPacks().first().size)
        assertEquals(1, dao.routingCells("astra-de-01").first().size)
    }

    @Test
    fun `deleting a pack cascades to its routing cells`() = runTest {
        val dao = db.packDao()
        dao.upsertPack(
            InstalledPackEntity(
                packId = "astra-de-01",
                version = "1.0.0",
                schemaVersion = "1.0.0",
                osmBuildDate = "2026-10-20",
                bboxWest = 0.0,
                bboxSouth = 0.0,
                bboxEast = 1.0,
                bboxNorth = 1.0,
                mapArchivePath = "/data/astra/a.pmtiles",
                poiIndexPath = null,
                routingLookupVersion = null,
                installedAtEpochMillis = 0,
                totalBytes = 0,
            ),
        )
        dao.upsertRoutingCell(
            InstalledRoutingCellEntity(
                packId = "astra-de-01",
                cellName = "E10_N50",
                filePath = "/data/astra/E10_N50.rd5",
                readVersion = 11,
                md5 = null,
                sizeBytes = 1,
            ),
        )

        assertNotNull(dao.routingCell("E10_N50"))

        dao.deletePack("astra-de-01")

        assertNull(dao.pack("astra-de-01"))
        assertNull("routing cells must cascade-delete with their pack", dao.routingCell("E10_N50"))
    }

    @Test
    fun `poi search index answers a full text match`() = runTest {
        val dao = db.poiSearchDao()
        dao.insertAll(
            listOf(
                PoiSearchEntity(1, "Tempelhofer Feld", "Tempelhofer Feld", "park"),
                PoiSearchEntity(2, "Mauerpark", "Mauerpark", "park"),
                PoiSearchEntity(3, "Berghain", "Berghain", "bar"),
                PoiSearchEntity(4, "Café am Neuen See", "Cafe am Neuen See", "cafe"),
            ),
        )

        val hits = dao.search("mauer*", limit = 10)
        assertEquals(1, hits.size)
        assertEquals("Mauerpark", hits.first().name)

        val byKind = dao.search("park", limit = 10)
        assertEquals(2, byKind.size)

        assertEquals(0, dao.search("nonexistentplace", limit = 10).size)
    }
}
