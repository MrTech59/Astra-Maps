package com.astramaps.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Astra Maps palette.
 *
 * These are the measured values from the approved plan. The authoritative copy
 * lives in `assets/palette.json`, which the Phase 3 contrast regression test
 * reads. Keep the two in sync.
 *
 * Contrast ratios below are measured against the relevant background.
 */

// --- Dark theme (default) -----------------------------------------------------
val LandDark = Color(0xFF12141F)
val WaterFillDark = Color(0xFF0E2A5C)
val CoastlineDark = Color(0xFF38E1F0) // 55% alpha over WaterFillDark
val MotorwayDark = Color(0xFF38E1F0) // 11.52:1 on LandDark
val MajorDark = Color(0xFFFF6FD8) //  7.44:1 on LandDark
val MinorDark = Color(0xFF7C6BFF) //  4.72:1 on LandDark
val ResidentialDark = Color(0xFF46527F) //  2.42:1 on LandDark (area fill)
val LabelDark = Color(0xFFC7D2FF) // 12.30:1 on LandDark

// --- Daylight theme -----------------------------------------------------------
val LandDaylight = Color(0xFFEFEDE4)
val WaterFillDaylight = Color(0xFF5B96D6)
val CoastlineDaylight = Color(0xFF1A4E8A) //  7.16:1 on LandDaylight
val MotorwayDaylight = Color(0xFFC2410C) //  4.41:1 on LandDaylight
val MajorDaylight = Color(0xFFB45309) //  4.28:1 on LandDaylight
val MinorDaylight = Color(0xFF7C6BFF) //  3.31:1 on LandDaylight
val ResidentialDaylight = Color(0xFF8E8A80) //  2.94:1 on LandDaylight (area fill)
val LabelDaylight = Color(0xFF1A1A1A) // 14.84:1 on LandDaylight

val SurfaceDark = Color(0xFF1A1D2B)
val SurfaceDaylight = Color(0xFFFFFFFF)
