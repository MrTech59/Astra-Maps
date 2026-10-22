package com.astramaps.app.map

import com.astramaps.app.ui.theme.ThemePalette
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Builds the MapLibre style for a local vector pack.
 *
 * Three rules hold here:
 *
 *  * **No colour literals.** Every colour comes from [ThemePalette], which is
 *    loaded from `assets/palette.json` and gated by `PaletteContrastTest`. The
 *    style is therefore dark or daylight by construction, not by a second
 *    hand-maintained copy of the palette.
 *  * **Offline only.** The sole source is the pack itself, addressed as
 *    `pmtiles://file://…`; MapLibre's native file source reads the archive and
 *    derives its TileJSON locally. There is no raster fallback and no remote
 *    style, so nothing here can make a network request.
 *  * **No remote glyphs.** Text layers are deliberately absent until the pack
 *    carries a glyph set (Phase 5). A symbol layer without `glyphs` would either
 *    render nothing or tempt the app into fetching fonts, which the offline
 *    promise forbids. [ThemePalette.labels] is already gated, so the colour is
 *    settled before the first label is drawn.
 */
object MapStyleFactory {

    const val SOURCE_ID = "astra"

    /** OSM attribution, required by the ODbL. Rendered by the map surface itself. */
    const val ATTRIBUTION =
        "<a href=\"https://www.openstreetmap.org/copyright\">&copy; OpenStreetMap contributors</a>"

    fun build(theme: ThemePalette, archiveUrl: String): String {
        require(archiveUrl.startsWith("pmtiles://")) {
            "Astra styles only address local PMTiles archives, got '$archiveUrl'"
        }

        val water = requireNotNull(theme.waterFill) { "palette theme has no waterFill" }
        val coast = requireNotNull(theme.coastlineStroke) { "palette theme has no coastlineStroke" }
        val labels = requireNotNull(theme.labels) { "palette theme has no labels colour" }
        val tint = requireNotNull(theme.landcoverTint) { "palette theme has no landcoverTint" }
        val coastAlpha = requireNotNull(coast.alpha) { "coastlineStroke has no alpha" }.toDouble()

        fun road(name: String): String = theme.roads.getValue(name).hex

        val style = buildJsonObject {
            put("version", 8)
            put("name", "Astra Maps")

            putJsonObject("sources") {
                putJsonObject(SOURCE_ID) {
                    put("type", "vector")
                    put("url", archiveUrl)
                    put("attribution", ATTRIBUTION)
                }
            }

            putJsonArray("layers") {
                add(background(theme))
                add(landcover(tint.hex, requireNotNull(tint.alpha).toDouble()))
                add(waterFill(water.hex))
                add(coastline(coast.hex, coastAlpha))
                add(waterway(coast.hex))
                add(boundaries(labels.hex))
                add(transit(labels.hex))
                add(cycleNetwork(road("motorway")))
                add(
                    roadLayer(
                        id = "roads-residential",
                        classes = listOf("residential"),
                        colour = road("residential"),
                        widthAtTen = 0.5,
                        widthAtSixteen = 2.4,
                    ),
                )
                add(
                    roadLayer(
                        id = "roads-minor",
                        classes = listOf(
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
                        colour = road("minor"),
                        widthAtTen = 0.6,
                        widthAtSixteen = 3.0,
                    ),
                )
                add(
                    roadLayer(
                        id = "roads-major",
                        classes = listOf("trunk", "primary", "secondary", "tertiary"),
                        colour = road("major"),
                        widthAtTen = 0.8,
                        widthAtSixteen = 4.5,
                    ),
                )
                add(
                    roadLayer(
                        id = "roads-motorway",
                        classes = listOf("motorway"),
                        colour = road("motorway"),
                        widthAtTen = 1.0,
                        widthAtSixteen = 6.0,
                    ),
                )
                add(bikeParking(road("motorway"), theme.land.hex))
                add(poiDots(labels.hex))
                add(barriers(road("major")))
            }
        }

        return Json.encodeToString(JsonObject.serializer(), style)
    }

    // --- layers -----------------------------------------------------------------

    private fun background(theme: ThemePalette): JsonObject =
        buildJsonObject {
            put("id", "background")
            put("type", "background")
            putJsonObject("paint") {
                put("background-color", theme.land.hex)
            }
        }

    private fun landcover(hex: String, alpha: Double): JsonObject =
        sourceLayer(
            id = "landcover",
            type = "fill",
            sourceLayer = "landcover",
            paint = buildJsonObject {
                put("fill-color", hex)
                put("fill-opacity", alpha)
            },
        )

    private fun waterFill(hex: String): JsonObject =
        sourceLayer(
            id = "water",
            type = "fill",
            sourceLayer = "water",
            paint = buildJsonObject {
                put("fill-color", hex)
            },
        )

    private fun coastline(hex: String, alpha: Double): JsonObject =
        sourceLayer(
            id = "coastline",
            type = "line",
            sourceLayer = "water",
            paint = buildJsonObject {
                put("line-color", hex)
                put("line-opacity", alpha)
                put("line-width", interpolate(10, 0.4, 16, 1.6))
            },
            layout = buildJsonObject {
                put("line-cap", "round")
                put("line-join", "round")
            },
        )

    private fun waterway(hex: String): JsonObject =
        sourceLayer(
            id = "waterway",
            type = "line",
            sourceLayer = "waterway",
            paint = buildJsonObject {
                put("line-color", hex)
                put("line-opacity", 0.9)
                put("line-width", interpolate(10, 0.4, 16, 2.2))
            },
            layout = buildJsonObject {
                put("line-cap", "round")
                put("line-join", "round")
            },
        )

    private fun boundaries(hex: String): JsonObject =
        sourceLayer(
            id = "boundaries",
            type = "line",
            sourceLayer = "boundaries",
            paint = buildJsonObject {
                put("line-color", hex)
                put("line-opacity", 0.30)
                put("line-width", interpolate(6, 0.4, 12, 1.0))
                put("line-dasharray", dash(3.0, 2.0))
            },
        )

    private fun transit(hex: String): JsonObject =
        sourceLayer(
            id = "transit",
            type = "line",
            sourceLayer = "transit",
            paint = buildJsonObject {
                put("line-color", hex)
                put("line-opacity", 0.25)
                put("line-width", interpolate(11, 0.4, 16, 2.0))
                put("line-dasharray", dash(4.0, 4.0))
            },
        )

    private fun cycleNetwork(hex: String): JsonObject =
        sourceLayer(
            id = "cycle-network",
            type = "line",
            sourceLayer = "cycle_network",
            paint = buildJsonObject {
                put("line-color", hex)
                put("line-opacity", 0.85)
                put("line-width", interpolate(10, 0.6, 16, 2.0))
                put("line-dasharray", dash(2.0, 1.5))
            },
            layout = buildJsonObject {
                put("line-cap", "round")
                put("line-join", "round")
            },
        )

    private fun roadLayer(
        id: String,
        classes: List<String>,
        colour: String,
        widthAtTen: Double,
        widthAtSixteen: Double,
    ): JsonObject =
        sourceLayer(
            id = id,
            type = "line",
            sourceLayer = "roads",
            filter = classFilter(classes),
            paint = buildJsonObject {
                put("line-color", colour)
                put("line-width", interpolate(10, widthAtTen, 16, widthAtSixteen))
            },
            layout = buildJsonObject {
                put("line-cap", "round")
                put("line-join", "round")
            },
        )

    private fun bikeParking(hex: String, landHex: String): JsonObject =
        sourceLayer(
            id = "bike-parking",
            type = "circle",
            sourceLayer = "bike",
            minZoom = 13,
            paint = buildJsonObject {
                put("circle-color", hex)
                put("circle-radius", interpolate(13, 1.5, 16, 4.0))
                put("circle-stroke-color", landHex)
                put("circle-stroke-width", 1.0)
            },
        )

    private fun poiDots(hex: String): JsonObject =
        sourceLayer(
            id = "poi",
            type = "circle",
            sourceLayer = "poi",
            minZoom = 13,
            paint = buildJsonObject {
                put("circle-color", hex)
                put("circle-opacity", 0.45)
                put("circle-radius", interpolate(13, 1.0, 16, 3.0))
            },
        )

    private fun barriers(hex: String): JsonObject =
        sourceLayer(
            id = "barriers",
            type = "circle",
            sourceLayer = "barriers",
            minZoom = 14,
            paint = buildJsonObject {
                put("circle-color", hex)
                put("circle-radius", interpolate(14, 1.5, 16, 3.0))
            },
        )

    // --- expressions and helpers -------------------------------------------------

    private fun sourceLayer(
        id: String,
        type: String,
        sourceLayer: String,
        paint: JsonObject,
        filter: JsonArray? = null,
        layout: JsonObject? = null,
        minZoom: Int? = null,
    ): JsonObject =
        buildJsonObject {
            put("id", id)
            put("type", type)
            put("source", SOURCE_ID)
            put("source-layer", sourceLayer)
            filter?.let { put("filter", it) }
            minZoom?.let { put("minzoom", it) }
            layout?.let { put("layout", it) }
            put("paint", paint)
        }

    /**
     * `["in", ["get", "class"], ["literal", [ … ]]]`
     *
     * The `["literal", …]` wrapper is load-bearing: a bare array in that
     * position is parsed as an expression, so every class name is read as an
     * operator and the filter is rejected.
     */
    private fun classFilter(classes: List<String>): JsonArray =
        buildJsonArray {
            add("in")
            add(
                buildJsonArray {
                    add("get")
                    add("class")
                },
            )
            add(
                buildJsonArray {
                    add("literal")
                    add(
                        buildJsonArray {
                            classes.forEach { add(it) }
                        },
                    )
                },
            )
        }

    /**
     * `["interpolate", ["exponential", 1.3], ["zoom"], z0, v0, z1, v1]`
     *
     * The `["zoom"]` input is required. Without it the first stop is read as
     * the input value and the remaining stops are an odd list, which MapLibre
     * rejects with "Expected an even number of arguments" — and then quietly
     * drops the layer's width.
     */
    private fun interpolate(
        zoomA: Int,
        valueA: Double,
        zoomB: Int,
        valueB: Double,
    ): JsonArray =
        buildJsonArray {
            add("interpolate")
            add(
                buildJsonArray {
                    add("exponential")
                    add(1.3)
                },
            )
            add(
                buildJsonArray {
                    add("zoom")
                },
            )
            add(zoomA)
            add(valueA)
            add(zoomB)
            add(valueB)
        }

    private fun dash(on: Double, off: Double): JsonArray =
        buildJsonArray {
            add(on)
            add(off)
        }

    /** Exposed for tests: every colour literal in a built style. */
    fun coloursIn(styleJson: String): Set<String> =
        Regex("#[0-9A-Fa-f]{6}").findAll(styleJson).map { it.value.uppercase() }.toSet()

    /** Exposed for tests: every `source-layer` the style references. */
    fun sourceLayersIn(styleJson: String): Set<String> =
        Regex("\"source-layer\":\"([^\"]+)\"")
            .findAll(styleJson)
            .map { it.groupValues[1] }
            .toSet()
}
