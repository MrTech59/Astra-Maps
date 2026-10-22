package com.astramaps.app.data.map

import android.content.Context
import android.net.ConnectivityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The Phase 2 offline acceptance harness: with the device's radios off, a
 * prebuilt archive still yields real map tiles.
 *
 * This is deliberately *not* a rendering test — map rendering is Phase 3. It
 * proves the layer that rendering depends on: a pack installed into app-private
 * storage is a valid PMTiles archive, and every tile it advertises can be read
 * and decompressed on the device with no network at all.
 *
 * The archive is `app/src/androidTest/assets/berlin_mitte.pmtiles`, produced by
 * the same planetiler pipeline that makes production packs, over a 0.04° × 0.03°
 * window of central Berlin at z0–14 (20 tiles):
 *
 * ```
 * planetiler generate-custom --schema=schemas/astra_schema.yml \
 *   --bounds=13.38,52.50,13.42,52.53 --maxzoom=14 --render_maxzoom=14 \
 *   --output=app/src/androidTest/assets/berlin_mitte.pmtiles
 * ```
 *
 * Airplane mode is a *precondition*, and the test refuses to pretend otherwise:
 * with no network it proves something; with a live network it would prove
 * nothing, so it either skips loudly or fails. The choice is the instrumentation
 * argument `astra.requireOffline`:
 *
 *  * absent / `false` — skip with an actionable message (a developer running
 *    the whole suite on a desk should not get a red build);
 *  * `true` — fail, which is what CI and
 *    `scripts/run-instrumented-tests.ps1 -AirplaneMode` set.
 */
@RunWith(AndroidJUnit4::class)
class OfflineTileServingTest {

    private lateinit var installed: File
    private lateinit var archive: PmtilesArchive

    private val targetContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun installArchiveAndGoOffline() {
        requireOfflineOrSkip()

        // Pack installation is a file copy into app-private storage; do exactly
        // that, so the test reads the same place the app will.
        val packDir = File(targetContext.filesDir, "packs/astra-test").apply { mkdirs() }
        installed = File(packDir, FIXTURE)
        InstrumentationRegistry.getInstrumentation().context.assets.open(FIXTURE).use { input ->
            installed.outputStream().use { output -> input.copyTo(output) }
        }
        archive = PmtilesArchive.open(installed)
    }

    @After
    fun closeArchive() {
        if (::archive.isInitialized) archive.close()
        if (::installed.isInitialized) installed.delete()
    }

    @Test
    fun headerDescribesAVectorArchive() {
        val header = archive.header
        assertEquals(3, header.version)
        assertEquals(1, header.tileType) // MVT
        assertEquals("gzip", header.internalCompressionName)
        assertEquals("gzip", header.tileCompressionName)
        assertEquals(0, header.minZoom)
        assertEquals(14, header.maxZoom)
        assertEquals(20L, header.addressedTiles)
        assertEquals(FIXTURE_BYTES, installed.length())

        val bounds = header.bounds
        assertEquals(13.38, bounds.minLon, 1e-6)
        assertEquals(52.50, bounds.minLat, 1e-6)
        assertEquals(13.42, bounds.maxLon, 1e-6)
        assertEquals(52.53, bounds.maxLat, 1e-6)
    }

    @Test
    fun archivePassesItsOwnIntegrityCheck() {
        val problems = archive.check()
        assertTrue("archive reported problems: $problems", problems.isEmpty())
    }

    @Test
    fun metadataListsEveryStyleLayer() {
        val document = Json.parseToJsonElement(archive.metadataJson()).jsonObject
        val layers = document.getValue("vector_layers").jsonArray
            .map { it.jsonObject.getValue("id").jsonPrimitive.content }
            .toSet()

        assertEquals(
            "the fixture must carry every layer the style draws",
            setOf(
                "barriers", "bike", "boundaries", "cycle_network", "landcover",
                "places", "poi", "roads", "transit", "water", "waterway",
            ),
            layers,
        )
    }

    @Test
    fun tileIdsDecodeBackToTheSameCoordinates() {
        // A transcription error in the Hilbert arithmetic would not show up in
        // a single lookup; it shows up here, over every entry in the archive.
        val entries = archive.entries()
        assertTrue("fixture has no entries", entries.isNotEmpty())
        for (entry in entries) {
            val (z, x, y) = SlippyTile.fromTileId(entry.tileId)
            assertEquals(
                "tile id ${entry.tileId} decoded to $z/$x/$y but did not encode back",
                entry.tileId,
                SlippyTile.tileId(z, x, y),
            )
        }
    }

    @Test
    fun everyAdvertisedTileCanBeReadOnDevice() {
        val entries = archive.entries()
        var read = 0
        for (entry in entries) {
            if (entry.runLength != 1L) continue
            val (z, x, y) = SlippyTile.fromTileId(entry.tileId)
            val bytes = archive.tile(z, x, y)
            assertNotNull("tile $z/$x/$y is listed but returned nothing", bytes)
            assertTrue("tile $z/$x/$y decompressed to nothing", bytes!!.isNotEmpty())
            assertEquals(
                "tile $z/$x/$y does not start with a MVT layer field",
                0x1A.toByte(),
                bytes[0],
            )
            read++
        }
        assertEquals("every entry in the fixture is a single-tile run", entries.size, read)
    }

    @Test
    fun z12TileCarriesRoadAndWaterFeatures() {
        val bounds = archive.header.bounds
        val lon = (bounds.minLon + bounds.maxLon) / 2
        val lat = (bounds.minLat + bounds.maxLat) / 2
        val z = 12
        val x = SlippyTile.lonToX(lon, z)
        val y = SlippyTile.latToY(lat, z)

        val bytes = archive.tile(z, x, y)
        assertNotNull(
            "the fixture's centre ($lon,$lat) should have a z$z tile at $z/$x/$y",
            bytes,
        )
        val text = bytes!!.toString(Charsets.ISO_8859_1)
        assertTrue("z$z/$x/$y should contain roads", text.contains("roads"))
        assertTrue("z$z/$x/$y should contain water", text.contains("water"))
        assertTrue("z$z/$x/$y is implausibly small (${bytes.size} B)", bytes.size > 1024)
    }

    @Test
    fun everyZoomLevelInThePyramidHasATile() {
        // planetiler writes z6..14 for this schema; z0..5 have no tiles and the
        // header's minZoom is the schema's floor, not the first populated zoom.
        val populated = archive.entries()
            .map { SlippyTile.fromTileId(it.tileId).first }
            .toSet()
        assertEquals("populated zooms", (6..14).toSet(), populated)

        val bounds = archive.header.bounds
        val lon = (bounds.minLon + bounds.maxLon) / 2
        val lat = (bounds.minLat + bounds.maxLat) / 2
        for (z in populated.sorted()) {
            val x = SlippyTile.lonToX(lon, z)
            val y = SlippyTile.latToY(lat, z)
            assertNotNull("zoom $z has no tile at $z/$x/$y", archive.tile(z, x, y))
        }
    }

    /**
     * Airplane mode cannot be switched on from inside the app, and switching it
     * on over wireless ADB would drop the very connection carrying the test
     * results. So the host enables it (CI does, and so does
     * `scripts/run-instrumented-tests.ps1 -AirplaneMode`), and the test checks
     * that it actually happened.
     */
    private fun requireOfflineOrSkip() {
        val connectivity = targetContext.getSystemService(ConnectivityManager::class.java)
        val active = connectivity.activeNetwork
        if (active == null) return

        val detail = buildString {
            append("the device still has an active network (")
            append(connectivity.getNetworkCapabilities(active))
            append("); this harness only proves something with the radios off. ")
            append("Run `adb shell cmd connectivity airplane-mode enable`, or use ")
            append("scripts/run-instrumented-tests.ps1 -AirplaneMode.")
        }
        val strict = InstrumentationRegistry.getArguments()
            .getString(REQUIRE_OFFLINE_ARG)
            .toBoolean()
        if (strict) {
            assertNull(detail, active)
        } else {
            Assume.assumeTrue(detail, false)
        }
    }

    private companion object {
        const val FIXTURE = "berlin_mitte.pmtiles"
        const val FIXTURE_BYTES = 2_864_441L
        const val REQUIRE_OFFLINE_ARG = "astra.requireOffline"
    }
}
