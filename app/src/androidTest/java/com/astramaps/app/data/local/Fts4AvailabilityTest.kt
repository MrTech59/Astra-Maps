package com.astramaps.app.data.local

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.astramaps.app.data.local.entity.PoiSearchEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves, on a real Android runtime, that the local search stack can use
 * **FTS4** and that **FTS5 is not available**.
 *
 * This matters because the whole offline place-search design rests on it:
 * AOSP's bundled SQLite is built without the FTS5 extension, so Room's
 * `@Fts4` is the only viable option without shipping a custom SQLite build
 * (which would cost ~1.5 MB per ABI and a lot of maintenance).
 *
 * Robolectric cannot prove this — it runs its own SQLite build — so the
 * assertion has to happen on the device.
 */
@RunWith(AndroidJUnit4::class)
class Fts4AvailabilityTest {

    private var db: AstraDatabase? = null

    @After
    fun tearDown() {
        db?.close()
    }

    @Test
    fun deviceSqliteSupportsFts4() {
        val helper = TestDbHelper()
        try {
            helper.writableDatabase.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS fts4_probe USING fts4(name, kind)",
            )
            helper.writableDatabase.execSQL(
                "INSERT INTO fts4_probe(name, kind) VALUES ('Tempelhofer Feld', 'park')",
            )

            helper.readableDatabase.rawQuery(
                "SELECT name FROM fts4_probe WHERE fts4_probe MATCH 'tempelh*'",
                null,
            ).use { cursor ->
                assertTrue("expected at least one FTS4 hit", cursor.moveToFirst())
                assertEquals("Tempelhofer Feld", cursor.getString(0))
            }
        } finally {
            helper.close()
        }
    }

    @Test
    fun deviceSqliteDoesNotSupportFts5() {
        val helper = TestDbHelper()
        try {
            val failure = runCatching {
                helper.writableDatabase.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS fts5_probe USING fts5(name, kind)",
                )
            }.exceptionOrNull()

            assertNotNull(
                "AOSP's SQLite is expected NOT to provide FTS5. If this now " +
                    "passes by compiling, Astra Maps can move from FTS4 to " +
                    "FTS5 and drop the pre-built-index compatibility shim.",
                failure,
            )
            assertTrue(
                "expected a 'no such module: fts5' SQLiteException but got: $failure",
                failure is SQLiteException &&
                    failure.message?.contains("no such module") == true,
            )
        } finally {
            helper.close()
        }
    }

    @Test
    fun roomPoiSearchWorksOnDevice() = runBlocking {
        val database = Room
            .inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                AstraDatabase::class.java,
            )
            .allowMainThreadQueries()
            .build()
        db = database

        val dao = database.poiSearchDao()
        dao.insertAll(
            listOf(
                PoiSearchEntity(1, "Tempelhofer Feld", "Tempelhofer Feld", "park"),
                PoiSearchEntity(2, "Mauerpark", "Mauerpark", "park"),
                PoiSearchEntity(3, "Berghain", "Berghain", "bar"),
            ),
        )

        val hits = dao.search("mauer*", limit = 10)
        assertEquals(1, hits.size)
        assertEquals("Mauerpark", hits.first().name)
    }

    @Test
    fun reportSqliteVersion() {
        val helper = TestDbHelper()
        try {
            helper.readableDatabase.rawQuery("SELECT sqlite_version()", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                val version = cursor.getString(0)
                println("AstraMaps device SQLite version: $version")
            }
        } finally {
            helper.close()
        }
    }

    /** Minimal helper so the probe tables live in their own throwaway database. */
    private class TestDbHelper : android.database.sqlite.SQLiteOpenHelper(
        ApplicationProvider.getApplicationContext(),
        "fts_probe.db",
        null,
        1,
    ) {
        override fun onCreate(db: SQLiteDatabase) = Unit
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
