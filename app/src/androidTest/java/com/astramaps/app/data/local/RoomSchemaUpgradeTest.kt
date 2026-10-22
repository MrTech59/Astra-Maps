package com.astramaps.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Regression test for the crash that greeted the first launch of the Phase 2
 * build on a phone that had already run an earlier development build:
 *
 * ```
 * java.lang.IllegalStateException: Room cannot verify the data integrity.
 * Expected identity hash: 66407fe8402f03034cb8f8cdf0404ca7,
 *                 found: ee846209683c68a73de5e276a335d8be
 * ```
 *
 * Room's destructive fallback only runs when the *version* changes. A file
 * written at the same version with a different identity hash is refused
 * outright by `checkIdentity()`, so a teammate upgrading across a schema edit
 * gets a crash on launch, not a migration.
 *
 * [AstraDatabase] therefore declares version 2, which routes any pre-release
 * v1 file through the destructive fallback. This test builds exactly that
 * stale file — v1, foreign hash, a `poi_search` with the wrong columns — and
 * proves the app's configuration replaces it instead of throwing.
 *
 * The stale file is synthetic on purpose: it must stay reproducible even after
 * every real v1 database is long gone.
 */
@RunWith(AndroidJUnit4::class)
class RoomSchemaUpgradeTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(NAME)
    }

    @Test
    fun aVersionOneFileWithAForeignIdentityHashIsReplacedNotFatal() {
        writeStaleVersionOneDatabase()

        val room = Room.databaseBuilder(context, AstraDatabase::class.java, NAME)
            // Mirrors AppContainer. If that policy changes, this test should
            // change with it — it is asserting the policy, not Room's defaults.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

        try {
            val opened = room.openHelper.writableDatabase

            // Deliberately literal: when the schema version moves on, this test
            // should be revisited rather than silently following it.
            assertEquals(
                "the stale file should have been upgraded to the current schema",
                2,
                opened.version,
            )

            val poiColumns = columns(opened, "poi_search")
            assertTrue(
                "poi_search should be recreated as the FTS4 table; columns were $poiColumns",
                poiColumns.containsAll(listOf("name", "nameEn", "kind")),
            )
            assertFalse(
                "the stale table's bogus column survived; columns were $poiColumns",
                poiColumns.contains("obsolete_column"),
            )

            val packColumns = columns(opened, "installed_pack")
            assertTrue(
                "installed_pack should be recreated; columns were $packColumns",
                packColumns.contains("mapArchivePath"),
            )

            val packs = runBlocking { room.packDao().allPacks().first() }
            assertTrue("a discarded registry should read as empty, got $packs", packs.isEmpty())
        } finally {
            room.close()
        }
    }

    /**
     * A database that looks like one left by a build from before the entities
     * were finalised: version 1, a `room_master_table` whose hash belongs to no
     * schema this code can produce, and an FTS table with the wrong columns.
     */
    private fun writeStaleVersionOneDatabase() {
        val file: File = context.getDatabasePath(NAME).also { it.parentFile?.mkdirs() }
        file.delete()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE poi_search (rowid INTEGER PRIMARY KEY, name TEXT, obsolete_column TEXT)")
            db.execSQL("CREATE TABLE installed_pack (packId TEXT NOT NULL PRIMARY KEY)")
            db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            db.execSQL(
                "INSERT INTO room_master_table (id, identity_hash) VALUES (42, ?)",
                arrayOf(STALE_IDENTITY_HASH),
            )
            db.version = 1
        }
    }

    private fun columns(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA table_info($table)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(1))
            }
        }

    private companion object {
        const val NAME = "room-schema-upgrade-test.db"
        const val STALE_IDENTITY_HASH = "ee846209683c68a73de5e276a335d8be"
    }
}
