package com.astramaps.app.ui.theme

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * WCAG 2.x relative luminance and contrast ratio.
 *
 * Kept in the app (rather than in a test) so that the palette validator in
 * Phase 3 and the test suite share one implementation.
 */
object Contrast {

    /** Relative luminance of an sRGB colour, per WCAG 2.x. */
    fun relativeLuminance(hex: String): Double {
        val rgb = parseHex(hex)
        return 0.2126 * channel(rgb[0]) + 0.7152 * channel(rgb[1]) + 0.0722 * channel(rgb[2])
    }

    /** Contrast ratio between two sRGB colours, in the range 1.0 .. 21.0. */
    fun ratio(foreground: String, background: String): Double {
        val l1 = relativeLuminance(foreground)
        val l2 = relativeLuminance(background)
        val lighter = max(l1, l2)
        val darker = min(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * Composite a foreground colour over a background at the given alpha and
     * return the resulting hex colour.
     */
    fun blend(foreground: String, background: String, alpha: Double): String {
        val f = parseHex(foreground)
        val b = parseHex(background)
        val out = IntArray(3) { i ->
            Math.round(f[i] * alpha + b[i] * (1.0 - alpha)).toInt().coerceIn(0, 255)
        }
        return "#%02X%02X%02X".format(out[0], out[1], out[2])
    }

    private fun channel(value: Int): Double {
        val c = value / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun parseHex(hex: String): IntArray {
        val h = hex.removePrefix("#").trim()
        require(h.length == 6) { "Expected #RRGGBB but was '$hex'" }
        return intArrayOf(
            h.substring(0, 2).toInt(16),
            h.substring(2, 4).toInt(16),
            h.substring(4, 6).toInt(16),
        )
    }
}
