package com.astramaps.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Road classes used by the map style, carrying the measured palette. */
data class RoadPalette(
    val motorway: Color,
    val major: Color,
    val minor: Color,
    val residential: Color,
    val labels: Color,
    val land: Color,
    val waterFill: Color,
    val coastline: Color,
)

private val DarkRoadPalette = RoadPalette(
    motorway = MotorwayDark,
    major = MajorDark,
    minor = MinorDark,
    residential = ResidentialDark,
    labels = LabelDark,
    land = LandDark,
    waterFill = WaterFillDark,
    coastline = CoastlineDark,
)

private val DaylightRoadPalette = RoadPalette(
    motorway = MotorwayDaylight,
    major = MajorDaylight,
    minor = MinorDaylight,
    residential = ResidentialDaylight,
    labels = LabelDaylight,
    land = LandDaylight,
    waterFill = WaterFillDaylight,
    coastline = CoastlineDaylight,
)

val LocalRoadPalette = staticCompositionLocalOf { DarkRoadPalette }

@Composable
fun AstraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val roadPalette = if (darkTheme) DarkRoadPalette else DaylightRoadPalette

    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = MotorwayDark,
            onPrimary = LandDark,
            secondary = MajorDark,
            onSecondary = LandDark,
            tertiary = MinorDark,
            background = LandDark,
            onBackground = LabelDark,
            surface = SurfaceDark,
            onSurface = LabelDark,
        )
    } else {
        lightColorScheme(
            primary = MotorwayDaylight,
            onPrimary = Color.White,
            secondary = MajorDaylight,
            onSecondary = Color.White,
            tertiary = MinorDaylight,
            background = LandDaylight,
            onBackground = LabelDaylight,
            surface = SurfaceDaylight,
            onSurface = LabelDaylight,
        )
    }

    CompositionLocalProvider(LocalRoadPalette provides roadPalette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AstraTypography,
            content = content,
        )
    }
}
