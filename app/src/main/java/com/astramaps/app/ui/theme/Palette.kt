package com.astramaps.app.ui.theme

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The palette model, shared by the app and the contrast gate.
 *
 * `assets/palette.json` is the single authoritative description of the map's
 * colours. The map style is generated from it at runtime, the Compose theme is
 * checked against it in `PaletteContrastTest`, and the gate recomputes every
 * recorded ratio from the hex values. Nothing may hard-code a map colour.
 */
@Serializable
data class PaletteFile(
    val version: Int = 1,
    val themes: Map<String, ThemePalette> = emptyMap(),
) {
    fun theme(name: String): ThemePalette = themes[name]
        ?: error("palette.json has no '$name' theme; it defines ${themes.keys}")

    fun themeFor(darkTheme: Boolean): ThemePalette = theme(if (darkTheme) DARK else DAYLIGHT)

    companion object {
        const val DARK = "dark"
        const val DAYLIGHT = "daylight"
    }
}

@Serializable
data class ThemePalette(
    val land: PaletteColor,
    val waterFill: PaletteColor? = null,
    val coastlineStroke: PaletteColor? = null,
    val roads: Map<String, PaletteColor> = emptyMap(),
    val labels: PaletteColor? = null,
    /**
     * A wash drawn over land areas (woods, parks, farmland) so they read as
     * landcover without introducing a colour outside the palette. It is applied
     * at [PaletteColor.alpha] over [land]; the gate re-measures every road and
     * label against the resulting background.
     */
    val landcoverTint: PaletteColor? = null,
    /**
     * Colours that sit below their WCAG threshold on purpose, keyed by the gate
     * element they belong to, each with the reason it is acceptable. The test
     * refuses sub-threshold colours that are not listed here, refuses entries
     * that no longer correspond to a real element, and refuses entries whose
     * element has since started passing.
     */
    val exemptions: Map<String, String> = emptyMap(),
)

@Serializable
data class PaletteColor(
    val hex: String,
    val alpha: Float? = null,
    @SerialName("contrastOnLand") val contrastOnLand: Double? = null,
    @SerialName("contrastOnWater") val contrastOnWater: Double? = null,
)

/** Loads the packaged palette. Assets are immutable, so the result is cached. */
object Palette {

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cached: PaletteFile? = null

    fun load(context: Context): PaletteFile =
        cached ?: synchronized(this) {
            cached ?: context.assets
                .open("palette.json")
                .bufferedReader()
                .use { json.decodeFromString(PaletteFile.serializer(), it.readText()) }
                .also { cached = it }
        }

    /** Test hook: forget the cache after mutating assets. */
    fun clearCache() {
        synchronized(this) { cached = null }
    }
}
