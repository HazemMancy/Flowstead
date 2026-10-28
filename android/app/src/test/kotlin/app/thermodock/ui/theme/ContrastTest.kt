/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Test
import kotlin.test.assertTrue

/** Brief §2b: text contrast ≥ 4.5:1 in light and dark themes. */
class ContrastTest {
    private fun ratio(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun check(name: String, fg: Color, bg: Color, min: Double = 4.5, failures: MutableList<String>) {
        val r = ratio(fg, bg)
        if (r < min) failures += "%s: %.2f:1 (need %.1f)".format(name, r, min)
    }

    @Test
    fun textPairsMeetAa() {
        val f = mutableListOf<String>()
        for ((theme, scheme, values) in listOf(
            Triple("light", ThermoDockLightColors, LightValueColors),
            Triple("dark", ThermoDockDarkColors, DarkValueColors),
        )) {
            for ((bgName, bg) in listOf("surface" to scheme.surface, "background" to scheme.background)) {
                check("$theme onSurface/$bgName", scheme.onSurface, bg, failures = f)
                check("$theme onSurfaceVariant/$bgName", scheme.onSurfaceVariant, bg, failures = f)
                check("$theme secondary/$bgName", scheme.secondary, bg, failures = f)
                check("$theme error/$bgName", scheme.error, bg, failures = f)
                check("$theme specified/$bgName", values.specified, bg, failures = f)
                check("$theme calculated/$bgName", values.calculated, bg, failures = f)
                check("$theme underSpecifiedText/$bgName", values.underSpecifiedText, bg, failures = f)
                check("$theme convergedText/$bgName", values.convergedText, bg, failures = f)
                check("$theme valueError/$bgName", values.error, bg, failures = f)
            }
            check("$theme onPrimary/primary", scheme.onPrimary, scheme.primary, failures = f)
            check("$theme onSecondary/secondary", scheme.onSecondary, scheme.secondary, failures = f)
            check("$theme onTertiary/tertiary", scheme.onTertiary, scheme.tertiary, failures = f)
            check("$theme onError/error", scheme.onError, scheme.error, failures = f)
            // Non-text UI (badges, swatches, outlines) needs 3:1.
            check("$theme underSpecified swatch", values.underSpecified, scheme.surface, 3.0, f)
            check("$theme converged swatch", values.converged, scheme.surface, 3.0, f)
        }
        assertTrue(f.isEmpty(), f.joinToString("\n"))
    }
}
