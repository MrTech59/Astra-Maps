package com.astramaps.app.map

import androidx.test.core.app.ApplicationProvider
import com.astramaps.app.ui.theme.Palette
import com.astramaps.app.ui.theme.PaletteFile
import com.astramaps.app.ui.theme.ThemePalette
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The style is generated, not authored, so these tests police the generator:
 * it may only use the gated palette, it may only read the local pack, and it
 * must not acquire a remote dependency by accident.
 */
@RunWith(RobolectricTestRunner::class)
class MapStyleFactoryTest {

    private companion object {
        const val PACK_URL =
            "pmtiles://file:///data/user/0/com.astramaps.app/files/packs/berlin_mitte.pmtiles"

        /** The layers `schemas/astra_schema.yml` emits. */
        val SCHEMA_LAYERS = setOf(
            "landcover",
            "water",
            "waterway",
            "roads",
            "cycle_network",
            "barriers",
            "bike",
            "poi",
            "places",
            "transit",
            "boundaries",
        )
    }

    private fun palette() =
        Palette.load(ApplicationProvider.getApplicationContext<android.content.Context>())

    private fun theme(name: String): ThemePalette = palette().theme(name)

    private fun style(name: String): String = MapStyleFactory.build(theme(name), PACK_URL)

    private fun layers(name: String) =
        Json.parseToJsonElement(style(name)).jsonObject.getValue("layers").jsonArray

    @Test
    fun `every colour in the style comes from the gated palette`() {
        for (themeName in listOf(PaletteFile.DARK, PaletteFile.DAYLIGHT)) {
            val theme = theme(themeName)
            val paletteColours = buildSet {
                add(theme.land.hex)
                theme.waterFill?.hex?.let { add(it) }
                theme.coastlineStroke?.hex?.let { add(it) }
                theme.labels?.hex?.let { add(it) }
                theme.landcoverTint?.hex?.let { add(it) }
                theme.roads.values.forEach { add(it.hex) }
            }.map { it.uppercase() }.toSet()

            val used = MapStyleFactory.coloursIn(style(themeName))
            assertEquals(
                "$themeName style uses colours that are not in palette.json, so they are not " +
                    "covered by the contrast gate",
                emptySet<String>(),
                used - paletteColours,
            )
            assertTrue("$themeName style should use more than one colour", used.size >= 4)
        }
    }

    @Test
    fun `the style reads the pack and nothing else`() {
        for (themeName in listOf(PaletteFile.DARK, PaletteFile.DAYLIGHT)) {
            val json = Json.parseToJsonElement(style(themeName)).jsonObject

            val sources = json.getValue("sources").jsonObject
            assertEquals("exactly one source: the local pack", setOf(MapStyleFactory.SOURCE_ID), sources.keys)
            val source = sources.getValue(MapStyleFactory.SOURCE_ID).jsonObject
            assertEquals("vector", source.getValue("type").jsonPrimitive.content)
            assertEquals(PACK_URL, source.getValue("url").jsonPrimitive.content)

            assertFalse(
                "a glyphs endpoint would make the map fetch fonts; text is deferred until " +
                    "the pack carries glyphs",
                json.containsKey("glyphs"),
            )
            assertFalse("a sprite endpoint would make the map fetch images", json.containsKey("sprite"))

            val types = layers(themeName).map { it.jsonObject.getValue("type").jsonPrimitive.content }
            assertFalse(
                "symbol layers without bundled glyphs cannot render and tempt a remote fetch",
                types.contains("symbol"),
            )
        }
    }

    @Test
    fun `the style covers every geometry layer the schema emits`() {
        val used = MapStyleFactory.sourceLayersIn(style(PaletteFile.DARK))

        assertEquals(
            "every source-layer must exist in schemas/astra_schema.yml",
            emptySet<String>(),
            used - SCHEMA_LAYERS,
        )
        // places is the one layer deferred with text; everything geometric is used.
        assertEquals(
            "the style stopped using a schema layer without a decision being recorded",
            SCHEMA_LAYERS - setOf("places"),
            used,
        )
    }

    @Test
    fun `both themes produce a background from their own land colour`() {
        for (themeName in listOf(PaletteFile.DARK, PaletteFile.DAYLIGHT)) {
            val theme = theme(themeName)
            val background = layers(themeName)
                .first { it.jsonObject.getValue("id").jsonPrimitive.content == "background" }
                .jsonObject
                .getValue("paint")
                .jsonObject
                .getValue("background-color")
                .jsonPrimitive
                .content
            assertEquals("$themeName background", theme.land.hex, background)
        }

        assertTrue(
            "the two themes must not produce the same background",
            theme(PaletteFile.DARK).land.hex != theme(PaletteFile.DAYLIGHT).land.hex,
        )
    }

    @Test
    fun `road layers filter by class so the palette hierarchy reaches the tile data`() {
        val classes = layers(PaletteFile.DARK)
            .map { it.jsonObject }
            .filter {
                it["source-layer"]?.jsonPrimitive?.content == "roads"
            }
            .flatMap { layer ->
                // filter = ["in", ["get","class"], ["literal", [class, …]]]
                layer.getValue("filter").jsonArray[2].jsonArray[1].jsonArray
                    .map { cls -> cls.jsonPrimitive.content }
            }

        assertEquals(
            "every road class in the schema must be drawn by exactly one palette class",
            setOf(
                "motorway",
                "trunk",
                "primary",
                "secondary",
                "tertiary",
                "residential",
                "unclassified",
                "service",
                "cycleway",
                "track",
                "path",
                "footway",
                "bridleway",
                "steps",
                "other",
            ),
            classes.toSet(),
        )
        assertEquals("a class is drawn twice", classes.size, classes.toSet().size)
    }

    @Test
    fun `road class filters wrap their class list in literal`() {
        // Two separate MapLibre rejections were found on a device, not by
        // inspection: a bare array in the third argument of `in` is parsed as
        // an expression, so every class name was read as an operator. The
        // style still loaded; the filters were simply dropped.
        for (themeName in listOf(PaletteFile.DARK, PaletteFile.DAYLIGHT)) {
            val filters = layers(themeName)
                .map { it.jsonObject }
                .mapNotNull { it["filter"]?.jsonArray }

            assertTrue("$themeName produced no filters at all", filters.isNotEmpty())
            for (filter in filters) {
                assertEquals("$themeName filter operator", "in", filter[0].jsonPrimitive.content)
                val classes = filter[2].jsonArray
                assertEquals(
                    "$themeName filter class list must be wrapped in a literal",
                    "literal",
                    classes[0].jsonPrimitive.content,
                )
                assertTrue("$themeName filter has an empty class list", classes.size > 1)
            }
        }
    }

    @Test
    fun `every interpolated width and radius names its zoom input`() {
        // Without ["zoom"] as the interpolation input, MapLibre read the first
        // stop as the input value and rejected the rest with "Expected an even
        // number of arguments", silently dropping the width.
        for (themeName in listOf(PaletteFile.DARK, PaletteFile.DAYLIGHT)) {
            val paintObjects = layers(themeName).map { it.jsonObject.getValue("paint").jsonObject }
            val expressions = paintObjects.mapNotNull { paint ->
                (paint["line-width"] ?: paint["circle-radius"])?.jsonArray
            }

            assertTrue("$themeName produced no interpolated widths", expressions.isNotEmpty())
            for (expression in expressions) {
                assertEquals(
                    "$themeName interpolation operator",
                    "interpolate",
                    expression[0].jsonPrimitive.content,
                )
                assertEquals(
                    "$themeName interpolation must name its input",
                    "zoom",
                    expression[2].jsonArray[0].jsonPrimitive.content,
                )
                assertEquals(
                    "$themeName interpolation needs (zoom, stop, value) triples",
                    0,
                    (expression.size - 3) % 2,
                )
            }
        }
    }

    @Test
    fun `a remote archive url is rejected`() {
        val theme = theme(PaletteFile.DARK)
        assertThrows(IllegalArgumentException::class.java) {
            MapStyleFactory.build(theme, "https://example.com/tiles.json")
        }
        assertThrows(IllegalArgumentException::class.java) {
            MapStyleFactory.build(theme, "/data/user/0/packs/berlin.pmtiles")
        }
    }
}
