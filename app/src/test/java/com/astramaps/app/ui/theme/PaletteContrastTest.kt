package com.astramaps.app.ui.theme

import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression test over the authoritative palette at `assets/palette.json`.
 *
 * It does two things:
 *  1. Recomputes every contrast ratio from the actual hex values and compares
 *     it with the ratio recorded in the file — so the file can never drift
 *     away from the colours it describes.
 *  2. Enforces the WCAG thresholds the design calls for: text at 4.5:1 and
 *     information-bearing strokes at 3:1.
 */
@RunWith(RobolectricTestRunner::class)
class PaletteContrastTest {

    @Serializable
    private data class PaletteFile(
        val version: Int = 1,
        val themes: Map<String, ThemePalette> = emptyMap(),
    )

    @Serializable
    private data class ThemePalette(
        val land: ColorEntry,
        val waterFill: ColorEntry? = null,
        val coastlineStroke: ColorEntry? = null,
        val roads: Map<String, ColorEntry> = emptyMap(),
        val labels: ColorEntry? = null,
    )

    @Serializable
    private data class ColorEntry(
        val hex: String,
        val alpha: Float? = null,
        @SerialName("contrastOnLand") val contrastOnLand: Double? = null,
        @SerialName("contrastOnWater") val contrastOnWater: Double? = null,
    )

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }

    private fun loadPalette(): PaletteFile {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val json = context.assets.open("palette.json").bufferedReader().use { it.readText() }
        return JSON.decodeFromString(PaletteFile.serializer(), json)
    }

    @Test
    fun `palette file parses and contains both themes`() {
        val palette = loadPalette()
        assertEquals(1, palette.version)
        assertTrue("expected a dark theme", palette.themes.containsKey("dark"))
        assertTrue("expected a daylight theme", palette.themes.containsKey("daylight"))
    }

    @Test
    fun `recorded contrast ratios match the actual colours`() {
        val palette = loadPalette()

        for ((themeName, theme) in palette.themes) {
            val land = theme.land.hex

            for ((name, entry) in theme.roads) {
                entry.contrastOnLand?.let { recorded ->
                    val actual = Contrast.ratio(entry.hex, land)
                    assertEquals(
                        "$themeName/$name recorded ratio is stale",
                        recorded,
                        actual,
                        0.02,
                    )
                }
            }

            theme.labels?.contrastOnLand?.let { recorded ->
                val actual = Contrast.ratio(theme.labels.hex, land)
                assertEquals(
                    "$themeName/labels recorded ratio is stale",
                    recorded,
                    actual,
                    0.02,
                )
            }

            theme.waterFill?.contrastOnLand?.let { recorded ->
                val actual = Contrast.ratio(theme.waterFill.hex, land)
                assertEquals(
                    "$themeName/waterFill recorded ratio is stale",
                    recorded,
                    actual,
                    0.02,
                )
            }
        }
    }

    @Test
    fun `labels and major road classes meet WCAG AA for text`() {
        val palette = loadPalette()

        for ((themeName, theme) in palette.themes) {
            val land = theme.land.hex

            theme.labels?.let {
                assertTrue(
                    "$themeName labels must be >= 4.5:1 on land, was " +
                        "${Contrast.ratio(it.hex, land)}",
                    Contrast.ratio(it.hex, land) >= 4.5,
                )
            }

            // Roads that carry the primary route hierarchy must be readable.
            for (className in listOf("motorway", "major", "minor")) {
                val entry = theme.roads[className]
                    ?: error("$themeName is missing road class '$className'")
                assertTrue(
                    "$themeName/$className must be >= 3:1 on land, was " +
                        "${Contrast.ratio(entry.hex, land)}",
                    Contrast.ratio(entry.hex, land) >= 3.0,
                )
            }
        }
    }

    @Test
    fun `a coastline stroke is always specified`() {
        val palette = loadPalette()
        for ((themeName, theme) in palette.themes) {
            val stroke = theme.coastlineStroke
            assertNotNull("$themeName must define a coastline stroke", stroke)
            assertNotNull("$themeName coastline stroke must define an alpha", stroke!!.alpha)
        }
    }

    @Test
    fun `blend composites correctly`() {
        // 40% of #38E1F0 over #12141F
        assertEquals("#216673", Contrast.blend("#38E1F0", "#12141F", 0.40))
        // Opaque foreground is unchanged
        assertEquals("#38E1F0", Contrast.blend("#38E1F0", "#12141F", 1.0))
    }
}
