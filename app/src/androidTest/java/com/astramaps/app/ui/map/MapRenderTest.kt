package com.astramaps.app.ui.map

import android.app.UiModeManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.astramaps.app.MainActivity
import com.astramaps.app.map.MapStyleFactory
import com.astramaps.app.ui.theme.Palette
import com.astramaps.app.ui.theme.PaletteFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Phase 3's rendering gate, on a real device with a real GL surface.
 *
 * This is the only test that can prove the claim the whole app rests on: that
 * MapLibre resolves `pmtiles://file://…` into a TileJSON and paints the archive's
 * vector tiles. A unit test can inspect the style JSON, but only the renderer
 * can say whether the style survives being attached to a map.
 *
 * The test drives the real app: it installs the committed fixture into the same
 * `filesDir/packs` directory the pack locator scans, launches `MainActivity`,
 * and asserts on what the map actually painted. Both themes are exercised by
 * setting the application night mode, so "the daylight theme renders" is a
 * measured statement rather than a code path nobody ran.
 */
@RunWith(AndroidJUnit4::class)
class MapRenderTest {

    private data class Frame(
        val bitmap: Bitmap,
        val sourceIds: Set<String>,
        val roadFeatures: Int,
        val waterFeatures: Int,
    )

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext: Context get() = instrumentation.targetContext
    private val packsDir: File get() = File(targetContext.filesDir, "packs")

    @Before
    fun installFixture() {
        packsDir.mkdirs()
        // A stale archive from another test must not become "the pack".
        packsDir.listFiles()?.forEach { it.delete() }

        val installed = File(packsDir, FIXTURE)
        instrumentation.context.assets.open(FIXTURE).use { input ->
            installed.outputStream().use { output -> input.copyTo(output) }
        }
        assertEquals("fixture copy is incomplete", FIXTURE_BYTES, installed.length())
    }

    @After
    fun removeFixture() {
        packsDir.listFiles()?.forEach { it.delete() }
        packsDir.delete()
        if (Build.VERSION.SDK_INT >= 31) {
            requireNotNull(targetContext.getSystemService(UiModeManager::class.java))
                .setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO)
        }
    }

    @Test
    fun theLocalPackRendersInTheDarkTheme() {
        assumeTrue("night mode control needs API 31", Build.VERSION.SDK_INT >= 31)
        val theme = Palette.load(targetContext).theme(PaletteFile.DARK)

        val frame = render(nightMode = true)

        assertTileJsonResolved(frame)
        assertPainted(frame, theme.land.hex, theme.waterFill!!.hex, "dark")
    }

    @Test
    fun theLocalPackRendersInTheDaylightTheme() {
        assumeTrue("night mode control needs API 31", Build.VERSION.SDK_INT >= 31)
        val theme = Palette.load(targetContext).theme(PaletteFile.DAYLIGHT)

        val frame = render(nightMode = false)

        assertTileJsonResolved(frame)
        assertPainted(frame, theme.land.hex, theme.waterFill!!.hex, "daylight")
    }

    // --- rendering ---------------------------------------------------------------

    /**
     * Renders the map and returns what it actually drew.
     *
     * Deliberately *polls* rather than waiting on `MapView` listeners. A test
     * only gets a handle on the activity after it has been created, by which
     * time the style may already have loaded and the first frame may already
     * have been drawn — and a listener registered after the event waits
     * forever. Polling the state the renderer exposes is immune to that race,
     * and it is also what distinguishes "the style parsed" from "pixels exist".
     */
    private fun render(nightMode: Boolean): Frame {
        val uiModeManager = requireNotNull(targetContext.getSystemService(UiModeManager::class.java))
        uiModeManager.setApplicationNightMode(
            if (nightMode) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO,
        )
        // The configuration change is asynchronous; give the app process a
        // moment to pick it up before the activity is created.
        instrumentation.waitForIdleSync()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val map = awaitSourceResolved(scenario)
            return awaitPaintedFrame(scenario, map)
        }
    }

    /**
     * Waits until the `astra` source exists.
     *
     * A vector source that carries `pmtiles://file://…` only appears once the
     * native file source has answered the TileJSON request, so this is the
     * TileJSON gate rather than a readiness hint.
     */
    private fun awaitSourceResolved(scenario: ActivityScenario<MainActivity>): MapLibreMap {
        val deadline = SystemClock.uptimeMillis() + 90_000
        var resolved: MapLibreMap? = null

        while (SystemClock.uptimeMillis() < deadline && resolved == null) {
            scenario.onActivity { activity ->
                val mapView = requireNotNull(activity.window.decorView.findMapView()) {
                    "MainActivity did not host a MapView; the pack was not picked up"
                }
                mapView.getMapAsync { map ->
                    if (map.style?.sources?.any { it.id == MapStyleFactory.SOURCE_ID } == true) {
                        resolved = map
                    }
                }
            }
            if (resolved == null) SystemClock.sleep(200)
        }

        return requireNotNull(resolved) {
            "the '${MapStyleFactory.SOURCE_ID}' source never appeared within 90 s, so " +
                "pmtiles:// did not resolve into a TileJSON"
        }
    }

    /**
     * Snapshots the map until the snapshot contains real map content.
     *
     * The first frame after a style load is usually background-only, so a
     * single snapshot would assert on a blank map. Retrying until the frame has
     * both road and water features makes the pixel assertions meaningful and
     * keeps the test independent of exactly when the renderer finishes.
     */
    private fun awaitPaintedFrame(
        scenario: ActivityScenario<MainActivity>,
        map: MapLibreMap,
    ): Frame {
        val deadline = SystemClock.uptimeMillis() + 90_000
        var last: Frame? = null

        while (SystemClock.uptimeMillis() < deadline) {
            var candidate: Frame? = null
            val snapshot = CountDownLatch(1)

            scenario.onActivity { activity ->
                val mapView = requireNotNull(activity.window.decorView.findMapView())
                val viewport = RectF(
                    0f,
                    0f,
                    mapView.width.toFloat(),
                    mapView.height.toFloat(),
                )

                val roads = map.queryRenderedFeatures(viewport, *ROAD_LAYERS)
                val water = map.queryRenderedFeatures(viewport, "water", "coastline")

                map.snapshot { bitmap ->
                    candidate = Frame(
                        bitmap = bitmap,
                        sourceIds = map.style?.sources?.map { it.id }?.toSet().orEmpty(),
                        roadFeatures = roads.size,
                        waterFeatures = water.size,
                    )
                    snapshot.countDown()
                }
            }

            if (snapshot.await(20, TimeUnit.SECONDS)) {
                candidate?.let { frame ->
                    last = frame
                    val painted = distinctColours(frame.bitmap) > 20 &&
                        frame.roadFeatures > 0 &&
                        frame.waterFeatures > 0
                    if (painted) return frame
                }
            }
            SystemClock.sleep(400)
        }

        return requireNotNull(last) {
            "the map never produced a snapshot within 90 s"
        }
    }

    private fun distinctColours(bitmap: Bitmap, stride: Int = 7): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val distinct = HashSet<Int>()
        for (index in pixels.indices step stride) distinct.add(pixels[index])
        return distinct.size
    }

    // --- assertions --------------------------------------------------------------

    /**
     * A vector source that carries `pmtiles://file://…` only appears once the
     * native file source has answered the TileJSON request, and features can
     * only be queried once its tiles have been decoded.
     */
    private fun assertTileJsonResolved(frame: Frame) {
        assertTrue(
            "the style has no '${MapStyleFactory.SOURCE_ID}' source, so the pmtiles URL did " +
                "not resolve; sources were ${frame.sourceIds}",
            frame.sourceIds.contains(MapStyleFactory.SOURCE_ID),
        )
        assertTrue(
            "no road feature was rendered from the pack, so no tile was decoded",
            frame.roadFeatures > 0,
        )
        assertTrue(
            "no water feature was rendered from the pack",
            frame.waterFeatures > 0,
        )
    }

    private fun assertPainted(frame: Frame, landHex: String, waterHex: String, themeName: String) {
        assertNotNull("$themeName snapshot is null", frame.bitmap)
        val bitmap = frame.bitmap
        assertTrue("$themeName snapshot has no area", bitmap.width > 0 && bitmap.height > 0)

        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        val land = countNear(pixels, landHex)
        val water = countNear(pixels, waterHex)

        assertTrue(
            "$themeName: the land colour $landHex should be a large part of the frame, " +
                "but only $land of ${pixels.size} pixels matched",
            land > pixels.size / 20,
        )
        assertTrue(
            "$themeName: the water fill $waterHex was never painted ($water pixels), so the " +
                "tile data did not reach the framebuffer",
            water > 100,
        )

        val distinct = HashSet<Int>()
        for (index in pixels.indices step 7) distinct.add(pixels[index])
        assertTrue(
            "$themeName: the frame looks flat (${distinct.size} distinct sampled colours); " +
                "the map probably painted the background only",
            distinct.size > 20,
        )
    }

    private fun countNear(pixels: IntArray, hex: String, tolerance: Int = 8): Int {
        val target = Color.parseColor(hex)
        var matches = 0
        for (pixel in pixels) {
            if (
                kotlin.math.abs(Color.red(pixel) - Color.red(target)) <= tolerance &&
                kotlin.math.abs(Color.green(pixel) - Color.green(target)) <= tolerance &&
                kotlin.math.abs(Color.blue(pixel) - Color.blue(target)) <= tolerance
            ) {
                matches++
            }
        }
        return matches
    }

    private fun View.findMapView(): MapView? = when (this) {
        is MapView -> this
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findMapView() }
        else -> null
    }

    private companion object {
        const val FIXTURE = "berlin_mitte.pmtiles"
        const val FIXTURE_BYTES = 2_864_441L
        val ROAD_LAYERS = arrayOf(
            "roads-motorway",
            "roads-major",
            "roads-minor",
            "roads-residential",
        )
    }
}
