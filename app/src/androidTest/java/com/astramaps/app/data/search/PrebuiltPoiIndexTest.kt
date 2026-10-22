package com.astramaps.app.data.search

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.Normalizer

/**
 * Proves that a **prebuilt** place index, written on a build machine and shipped
 * inside a pack, is usable on a real device.
 *
 * The point is not that FTS4 works — [com.astramaps.app.data.local.Fts4AvailabilityTest]
 * already proves that. The point is that the *file format* is portable: the
 * fixture is produced by `tools/build_poi_index.py` running host SQLite, and
 * this test opens those exact bytes with Android's bundled SQLite, read-only,
 * and answers the queries the app will actually run.
 *
 * If Android's SQLite ever stops reading FTS4 segments written by a newer host
 * SQLite, this test fails — which is exactly the signal needed, because the
 * whole "one small download instead of on-device extraction" design rests on it.
 *
 * The fixture is `app/src/androidTest/assets/astra_poi_fixture.db`, a 600-row
 * truncation of the Berlin index built by the same script. `--limit` samples
 * round-robin across kinds, so the fixture carries every kind a full index
 * does and not just the alphabetically-first ones:
 *
 * ```
 * python tools/build_poi_index.py --pbf berlin.osm.pbf \
 *     --limit 600 --out app/src/androidTest/assets/astra_poi_fixture.db
 * ```
 */
@RunWith(AndroidJUnit4::class)
class PrebuiltPoiIndexTest {

    private lateinit var copied: File
    private lateinit var db: SQLiteDatabase

    /** The test APK's assets, not the app under test's. */
    private val testAssets
        get() = InstrumentationRegistry.getInstrumentation().context.assets

    @Before
    fun openFixture() {
        val cache = InstrumentationRegistry.getInstrumentation()
            .targetContext.cacheDir
        copied = File(cache, "astra_poi_fixture.db")

        // SQLite needs a real path, so the asset is copied out first. Pack
        // installation does the same thing with the downloaded file.
        testAssets.open(FIXTURE).use { input ->
            copied.outputStream().use { output -> input.copyTo(output) }
        }

        db = SQLiteDatabase.openDatabase(
            copied.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
    }

    @After
    fun closeFixture() {
        if (::db.isInitialized) db.close()
        if (::copied.isInitialized) copied.delete()
    }

    @Test
    fun headerIdentifiesAnAstraIndex() {
        db.rawQuery("PRAGMA application_id", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(APPLICATION_ID, cursor.getInt(0))
        }
        db.rawQuery("PRAGMA user_version", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(FORMAT_VERSION, cursor.getInt(0))
        }
    }

    @Test
    fun everyIndexedTableReads() {
        val expected = listOf("poi", "poi_fts", "place", "place_fts", "street", "street_fts")
        for (table in expected) {
            val count = countOf("SELECT COUNT(*) FROM $table")
            assertEquals("$table should mirror the fixture", FIXTURE_ROWS, count)
        }
    }

    @Test
    fun nameSearchReturnsUsableCoordinates() {
        val (name, token) = firstSearchablePoi()

        val hits = mutableListOf<Triple<String, String, DoubleArray>>()
        db.rawQuery(
            """
            SELECT p.name, p.kind, p.lat, p.lon
            FROM poi_fts f
            JOIN poi p ON p.id = f.rowid
            WHERE poi_fts MATCH ?
            ORDER BY p.id
            LIMIT 5
            """.trimIndent(),
            arrayOf("${token.lowercase()}*"),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                hits += Triple(
                    cursor.getString(0),
                    cursor.getString(1),
                    doubleArrayOf(cursor.getDouble(2), cursor.getDouble(3)),
                )
            }
        }

        assertTrue("MATCH '${token.lowercase()}*' returned no rows", hits.isNotEmpty())
        assertTrue(
            "'$name' should be among the hits for '${token.lowercase()}*'",
            hits.any { it.first == name },
        )
        for ((_, _, position) in hits) {
            assertTrue(
                "latitude ${position[0]} outside the fixture's area",
                position[0] in -90.0..90.0,
            )
            assertTrue(
                "longitude ${position[1]} outside the fixture's area",
                position[1] in -180.0..180.0,
            )
        }
    }

    @Test
    fun diacriticsFoldSoAsciiTypingStillMatches() {
        val placeName = firstNonAsciiPlaceName()
        assertTrue("fixture should contain a diacritic place name", placeName != null)

        val word = firstLetterRun(placeName!!)
        assertTrue("no word in '$placeName' to probe", word != null)

        val folded = fold(word!!)
        assertNotEquals(
            "'$word' has no diacritic, so this test would prove nothing",
            word.lowercase(),
            folded,
        )

        val hits = countOf("SELECT COUNT(*) FROM place_fts WHERE place_fts MATCH ?", "${folded}*")
        assertTrue(
            "typing '$folded' should find '$word', but place_fts returned nothing",
            hits > 0,
        )
    }

    @Test
    fun kindFilteredProximityQueryReturnsPlaces() {
        // The app's "what is near me" query: a kind filter plus a bounding box.
        // The kind and the window come from the fixture itself, so this runs
        // against any extract rather than assuming Berlin has bike parking in
        // a hard-coded window.
        val kind = mostCommonKind()
        val anchor = firstPoiOfKind(kind)
        val margin = 0.05
        val box = arrayOf(
            kind,
            (anchor.lat - margin).toString(),
            (anchor.lat + margin).toString(),
            (anchor.lon - margin).toString(),
            (anchor.lon + margin).toString(),
        )

        val sql = """
            SELECT name, lat, lon
            FROM poi
            WHERE kind = ? AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?
            LIMIT 10
        """.trimIndent()

        val plan = mutableListOf<String>()
        db.rawQuery("EXPLAIN QUERY PLAN $sql", box).use { c ->
            while (c.moveToNext()) plan += c.getString(c.getColumnCount() - 1)
        }

        var rows = 0
        db.rawQuery(sql, box).use { cursor ->
            while (cursor.moveToNext()) {
                rows++
                val lat = cursor.getDouble(1)
                val lon = cursor.getDouble(2)
                assertTrue("lat $lat outside the queried box", lat in box[1].toDouble()..box[2].toDouble())
                assertTrue("lon $lon outside the queried box", lon in box[3].toDouble()..box[4].toDouble())
            }
        }

        assertTrue(
            "a ${margin}° window around the fixture's first '$kind' should contain it",
            rows > 0,
        )
        assertTrue(
            "the query should be index-assisted; plan was $plan",
            plan.any { it.contains("poi_kind") || it.contains("poi_lat_lon") },
        )
    }

    @Test
    fun readOnlyFlagIsEnforced() {
        val failure = runCatching {
            db.execSQL("INSERT INTO poi (osm_type, osm_id, kind, name, lat, lon) " +
                "VALUES ('node', 1, 'food', 'x', 52.5, 13.4)")
        }.exceptionOrNull()

        assertTrue(
            "a read-only prebuilt index must reject writes, but got: $failure",
            failure is SQLiteException,
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun countOf(sql: String, vararg args: String): Int =
        db.rawQuery(sql, args).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    /**
     * First named POI whose name carries a token at least four letters long.
     *
     * Scanning instead of taking row 1 matters: real names like "360Rad" or
     * "7/8" have no four-letter run, so a single-row probe would fail for
     * reasons that have nothing to do with search.
     */
    private fun firstSearchablePoi(): Pair<String, String> =
        db.rawQuery("SELECT id, name FROM poi WHERE name <> '' ORDER BY id", null).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1)
                val token = firstLetterRun(name)
                if (token != null) return@use name to token
            }
            throw AssertionError("fixture has no named POI with a four-letter token")
        }

    private fun mostCommonKind(): String =
        db.rawQuery(
            "SELECT kind, COUNT(*) AS n FROM poi GROUP BY kind ORDER BY n DESC, kind LIMIT 1",
            null,
        ).use { c ->
            assertTrue("fixture has no POIs", c.moveToFirst())
            c.getString(0)
        }

    private fun firstPoiOfKind(kind: String): Position =
        db.rawQuery(
            "SELECT lat, lon FROM poi WHERE kind = ? ORDER BY id LIMIT 1",
            arrayOf(kind),
        ).use { c ->
            assertTrue("fixture has no '$kind' POI", c.moveToFirst())
            Position(c.getDouble(0), c.getDouble(1))
        }

    private data class Position(val lat: Double, val lon: Double)

    private fun firstNonAsciiPlaceName(): String? =
        db.rawQuery("SELECT name FROM place WHERE name <> '' ORDER BY id", null).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0)
                if (name.any { it.code > 127 }) return@use name
            }
            null
        }

    /** First run of at least four letters, mirroring the Python verifier. */
    private fun firstLetterRun(text: String, minimum: Int = 4): String? =
        Regex("\\p{L}+").findAll(text)
            .map { it.value }
            .firstOrNull { it.length >= minimum }

    /** Same folding the unicode61 tokenizer applies with remove_diacritics=2. */
    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()

    private companion object {
        const val FIXTURE = "astra_poi_fixture.db"
        const val APPLICATION_ID = 0x52545341
        const val FORMAT_VERSION = 1
        const val FIXTURE_ROWS = 600
    }
}
