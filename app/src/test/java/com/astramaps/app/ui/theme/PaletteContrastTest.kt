package com.astramaps.app.ui.theme

import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/**
 * The palette contrast gate.
 *
 * `assets/palette.json` is the authoritative palette; this test is the thing
 * that makes it true rather than aspirational. It:
 *
 *  1. Recomputes every contrast ratio from the hex values — including the
 *     coastline, which is measured *as rendered*, composited over the fill it
 *     sits on — and fails if a recorded ratio has drifted.
 *  2. Applies the WCAG 2.x thresholds the design commits to: text at 4.5:1,
 *     non-text at 3:1.
 *  3. Refuses to let a colour slip below a threshold silently. Anything that
 *     cannot meet 3:1 must be declared under the theme's `exemptions` with a
 *     reason; the exemption must name a real element, must not be dead (the
 *     element must genuinely still be below the threshold), and a `waterFill`
 *     exemption is only accepted if the coastline stroke that is supposed to
 *     carry the boundary clears 3:1 against the fill.
 *  4. Holds both themes to the same gate, so daylight cannot quietly regress
 *     while dark stays compliant.
 *  5. Re-measures every colour against the landcover wash, because the map
 *     style draws that wash over the land and a tint that quietly eroded the
 *     roads' contrast would otherwise be invisible to this test.
 *  6. Checks that the Compose colour constants in `Color.kt` still equal the
 *     palette file, since the app theme and the map style consume the same
 *     design decision through two different code paths.
 *
 * Point 1 is not theoretical: the daylight coastline was originally `#1A4E8A`,
 * which measures 2.71:1 against the daylight water fill. The old version of
 * this test never measured the coastline at all, so it passed.
 */
@RunWith(RobolectricTestRunner::class)
class PaletteContrastTest {

    /**
     * One colour, the background it is measured against, and the rule it must
     * satisfy. Measured in the theme's own terms so a failure message can say
     * exactly what was compared with what.
     */
    private data class Gate(
        val id: String,
        val ratio: Double,
        val threshold: Double,
        val background: String,
    )

    private companion object {
        const val TEXT = 4.5
        const val NON_TEXT = 3.0

        /** Elements the gate itself knows about; a typo in the JSON cannot hide. */
        val ROAD_CLASSES = listOf("motorway", "major", "minor", "residential")

        fun fmt(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
    }

    private fun loadPalette(): PaletteFile =
        Palette.load(ApplicationProvider.getApplicationContext<android.content.Context>())

    /**
     * Every colour in a theme that a reader has to be able to see, measured as
     * rendered. The coastline is composited over both backgrounds it separates,
     * because a stroke that vanishes into either one is not doing its job.
     *
     * [land] is a parameter rather than always `theme.land` so the same gate can
     * be re-run against the landcover wash.
     */
    private fun gatesOn(theme: ThemePalette, land: String): List<Gate> {
        val labels = requireNotNull(theme.labels) { "theme has no labels entry" }
        val water = requireNotNull(theme.waterFill) { "theme has no waterFill entry" }
        val stroke = requireNotNull(theme.coastlineStroke) { "theme has no coastlineStroke entry" }
        val alpha = requireNotNull(stroke.alpha) { "coastlineStroke has no alpha" }.toDouble()

        return buildList {
            add(Gate("labels", Contrast.ratio(labels.hex, land), TEXT, "land $land"))
            add(Gate("waterFill", Contrast.ratio(water.hex, land), NON_TEXT, "land $land"))
            add(
                Gate(
                    "coastlineStroke over water",
                    Contrast.ratio(Contrast.blend(stroke.hex, water.hex, alpha), water.hex),
                    NON_TEXT,
                    "water ${water.hex}",
                ),
            )
            add(
                Gate(
                    "coastlineStroke over land",
                    Contrast.ratio(Contrast.blend(stroke.hex, land, alpha), land),
                    NON_TEXT,
                    "land $land",
                ),
            )
            for (className in ROAD_CLASSES) {
                val entry = theme.roads[className]
                    ?: error("theme is missing road class '$className'")
                add(
                    Gate(
                        "roads.$className",
                        Contrast.ratio(entry.hex, land),
                        NON_TEXT,
                        "land $land",
                    ),
                )
            }
        }
    }

    private fun gates(theme: ThemePalette): List<Gate> = gatesOn(theme, theme.land.hex)

    /** `coastlineStroke over water` and `coastlineStroke over land` share one exemption. */
    private fun exemptionKey(gateId: String): String = gateId.substringBefore(' ')

    /** Every failing gate must be covered by a stated exemption. */
    private fun assertGateHolds(themeName: String, theme: ThemePalette, gates: List<Gate>) {
        for (gate in gates) {
            if (gate.ratio >= gate.threshold) continue

            val key = exemptionKey(gate.id)
            val reason = theme.exemptions[key]
            assertNotNull(
                "$themeName ${gate.id} measures ${fmt(gate.ratio)}:1 against " +
                    "${gate.background}, below ${gate.threshold}:1, and has no exemption",
                reason,
            )
            assertTrue(
                "$themeName exemption '$key' must state a real reason, was '$reason'",
                reason!!.trim().length >= 20,
            )
        }
    }

    @Test
    fun `palette file parses and contains both themes`() {
        val palette = loadPalette()
        assertEquals(1, palette.version)
        assertEquals(
            "the gate must cover exactly the shipped themes",
            setOf("dark", "daylight"),
            palette.themes.keys,
        )
    }

    @Test
    fun `the gate actually measures every element of every theme`() {
        // Guards against a vacuous pass: the gate would silently stop testing
        // anything if an entry were dropped from the model or the JSON.
        for ((themeName, theme) in loadPalette().themes) {
            val gates = gates(theme)
            assertEquals(
                "$themeName gate count changed; every colour must still be measured",
                8,
                gates.size,
            )
            assertEquals(
                "$themeName has duplicate gate ids",
                gates.size,
                gates.map { it.id }.toSet().size,
            )
        }
    }

    @Test
    fun `recorded contrast ratios match the actual colours`() {
        for ((themeName, theme) in loadPalette().themes) {
            val land = theme.land.hex

            for ((name, entry) in theme.roads) {
                entry.contrastOnLand?.let { recorded ->
                    assertEquals(
                        "$themeName/$name recorded ratio is stale",
                        recorded,
                        Contrast.ratio(entry.hex, land),
                        0.02,
                    )
                }
            }

            theme.labels?.contrastOnLand?.let { recorded ->
                assertEquals(
                    "$themeName/labels recorded ratio is stale",
                    recorded,
                    Contrast.ratio(theme.labels.hex, land),
                    0.02,
                )
            }

            theme.waterFill?.contrastOnLand?.let { recorded ->
                assertEquals(
                    "$themeName/waterFill recorded ratio is stale",
                    recorded,
                    Contrast.ratio(theme.waterFill.hex, land),
                    0.02,
                )
            }

            val stroke = theme.coastlineStroke
            val water = theme.waterFill
            if (stroke != null && water != null) {
                val alpha = requireNotNull(stroke.alpha).toDouble()
                val renderedOverWater = Contrast.blend(stroke.hex, water.hex, alpha)
                val renderedOverLand = Contrast.blend(stroke.hex, land, alpha)

                stroke.contrastOnWater?.let { recorded ->
                    assertEquals(
                        "$themeName/coastlineStroke recorded ratio against water is stale " +
                            "(rendered as $renderedOverWater)",
                        recorded,
                        Contrast.ratio(renderedOverWater, water.hex),
                        0.02,
                    )
                }
                stroke.contrastOnLand?.let { recorded ->
                    assertEquals(
                        "$themeName/coastlineStroke recorded ratio against land is stale " +
                            "(rendered as $renderedOverLand)",
                        recorded,
                        Contrast.ratio(renderedOverLand, land),
                        0.02,
                    )
                }
            }
        }
    }

    @Test
    fun `labels meet WCAG AA for text in both themes`() {
        for ((themeName, theme) in loadPalette().themes) {
            val labels = gates(theme).single { it.id == "labels" }
            assertTrue(
                "$themeName labels must be >= ${TEXT}:1 on ${labels.background}, was " +
                    "${fmt(labels.ratio)}:1",
                labels.ratio >= TEXT,
            )
        }
    }

    @Test
    fun `informative strokes and fills meet 3 to 1, or are exempted with a reason`() {
        for ((themeName, theme) in loadPalette().themes) {
            assertGateHolds(themeName, theme, gates(theme))
        }
    }

    @Test
    fun `the landcover wash does not push any gated colour below its threshold`() {
        for ((themeName, theme) in loadPalette().themes) {
            val tint = requireNotNull(theme.landcoverTint) {
                "$themeName must declare the landcoverTint the style draws"
            }
            val alpha = requireNotNull(tint.alpha) { "$themeName landcoverTint has no alpha" }
            val shade = Contrast.blend(tint.hex, theme.land.hex, alpha.toDouble())

            assertTrue(
                "$themeName landcover wash must actually change the land, otherwise it is " +
                    "untested dead configuration",
                shade != theme.land.hex,
            )
            assertGateHolds(themeName, theme, gatesOn(theme, shade))
        }
    }

    @Test
    fun `exemptions are live and a waterFill exemption is backed by the coastline`() {
        for ((themeName, theme) in loadPalette().themes) {
            val gates = gates(theme)
            val failing = gates.filter { it.ratio < it.threshold }

            for ((key, reason) in theme.exemptions) {
                val named = gates.filter { exemptionKey(it.id) == key }
                assertTrue(
                    "$themeName exemption '$key' names no element the gate measures",
                    named.isNotEmpty(),
                )
                assertTrue(
                    "$themeName exemption '$key' is dead: it now passes the gate and should " +
                        "be removed (${named.first().id} is " +
                        "${fmt(named.first().ratio)}:1)",
                    named.any { gate -> failing.any { it.id == gate.id } },
                )
                assertTrue(
                    "$themeName exemption '$key' must state a real reason, was '$reason'",
                    reason.trim().length >= 20,
                )
            }

            if (theme.exemptions.containsKey("waterFill")) {
                val water = requireNotNull(theme.waterFill) { "theme has no waterFill" }
                val stroke = requireNotNull(theme.coastlineStroke) {
                    "$themeName exempts waterFill but defines no coastline stroke, so nothing " +
                        "carries the water boundary"
                }
                val alpha = requireNotNull(stroke.alpha).toDouble()
                val rendered = Contrast.blend(stroke.hex, water.hex, alpha)
                val ratio = Contrast.ratio(rendered, water.hex)
                assertTrue(
                    "$themeName exempts waterFill (${fmt(Contrast.ratio(water.hex, theme.land.hex))}:1 " +
                        "on land), so the coastline must carry the boundary; it measures " +
                        "${fmt(ratio)}:1 against the fill",
                    ratio >= NON_TEXT,
                )
            }
        }
    }

    @Test
    fun `daylight is held to the same gate as dark`() {
        val daylight = loadPalette().themes.getValue("daylight")
        val land = daylight.land.hex

        // The three information-bearing road classes, explicitly, because a
        // daylight theme that merely "renders" is not what the phase asks for.
        for (className in listOf("motorway", "major", "minor")) {
            val hex = daylight.roads.getValue(className).hex
            assertTrue(
                "daylight/$className must be >= ${NON_TEXT}:1 on land, was " +
                    "${fmt(Contrast.ratio(hex, land))}:1",
                Contrast.ratio(hex, land) >= NON_TEXT,
            )
        }

        val stroke = daylight.coastlineStroke!!
        val water = daylight.waterFill!!
        val alpha = requireNotNull(stroke.alpha).toDouble()
        assertTrue(
            "daylight coastline must be >= ${NON_TEXT}:1 against the water fill, was " +
                "${fmt(Contrast.ratio(Contrast.blend(stroke.hex, water.hex, alpha), water.hex))}:1",
            Contrast.ratio(Contrast.blend(stroke.hex, water.hex, alpha), water.hex) >= NON_TEXT,
        )
    }

    @Test
    fun `compose colour constants match the palette file`() {
        // The map style reads palette.json; the Material theme reads Color.kt.
        // They must describe one palette, not two that happen to start equal.
        val expected = mapOf(
            PaletteFile.DARK to mapOf(
                "land" to LandDark,
                "waterFill" to WaterFillDark,
                "coastlineStroke" to CoastlineDark,
                "roads.motorway" to MotorwayDark,
                "roads.major" to MajorDark,
                "roads.minor" to MinorDark,
                "roads.residential" to ResidentialDark,
                "labels" to LabelDark,
            ),
            PaletteFile.DAYLIGHT to mapOf(
                "land" to LandDaylight,
                "waterFill" to WaterFillDaylight,
                "coastlineStroke" to CoastlineDaylight,
                "roads.motorway" to MotorwayDaylight,
                "roads.major" to MajorDaylight,
                "roads.minor" to MinorDaylight,
                "roads.residential" to ResidentialDaylight,
                "labels" to LabelDaylight,
            ),
        )

        for ((themeName, theme) in loadPalette().themes) {
            val colours = expected.getValue(themeName)

            fun actual(id: String): String = when {
                id == "land" -> theme.land.hex
                id == "waterFill" -> theme.waterFill!!.hex
                id == "coastlineStroke" -> theme.coastlineStroke!!.hex
                id == "labels" -> theme.labels!!.hex
                id.startsWith("roads.") -> theme.roads.getValue(id.removePrefix("roads.")).hex
                else -> error("unknown colour id '$id'")
            }

            for ((id, composeColour) in colours) {
                val composeHex = String.format(
                    Locale.ROOT,
                    "#%06X",
                    composeColour.toArgb() and 0x00FFFFFF,
                )
                assertEquals(
                    "$themeName/$id drifted: palette.json says ${actual(id)}, Color.kt says " +
                        "$composeHex",
                    actual(id),
                    composeHex,
                )
            }
        }
    }

    @Test
    fun `blend composites correctly`() {
        // 40% of #38E1F0 over #12141F
        assertEquals("#216673", Contrast.blend("#38E1F0", "#12141F", 0.40))
        // Opaque foreground is unchanged
        assertEquals("#38E1F0", Contrast.blend("#38E1F0", "#12141F", 1.0))
        // The dark coastline's recorded ratio is measured from this composite.
        assertEquals("#278592", Contrast.blend("#38E1F0", "#12141F", 0.55))
    }
}
