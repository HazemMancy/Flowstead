/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * HYSYS-style value semantics: every value shows where it came from.
 * Light values are the brief's; dark values are lifted to keep ≥ 4.5:1 on dark surfaces.
 *
 * The brief's under-specified (#B7791F, 3.6:1 on white) and converged (#1F8A4C, 4.4:1) colours
 * fail the 4.5:1 text rule, so they are used as-is for swatches, badges and borders (3:1 is
 * enough there) and the `…Text` variants are used whenever they colour text.
 */
@Immutable
data class ValueColors(
    val specified: Color,
    val calculated: Color,
    val underSpecified: Color,
    val underSpecifiedText: Color,
    val error: Color,
    val converged: Color,
    val convergedText: Color,
    val convergedContainer: Color,
    val vapour: Color,
    val liquid: Color,
    val energy: Color,
    /** Two-phase streams on the canvas (violet, from the mark). */
    val twoPhase: Color,
)

val LightValueColors = ValueColors(
    specified = Color(0xFF2563C9),
    calculated = Brand.Ink,
    underSpecified = Color(0xFFB7791F),
    underSpecifiedText = Color(0xFF8F5E12),
    error = Color(0xFFC8372D),
    converged = Color(0xFF1F8A4C),
    convergedText = Color(0xFF19763F),
    convergedContainer = Color(0xFFDDF3E6),
    vapour = Color(0xFFC77700),
    liquid = Color(0xFF0E7490),
    energy = Color(0xFFE8461A),
    twoPhase = Color(0xFF7C5CBF),
)

val DarkValueColors = ValueColors(
    specified = Color(0xFF8AB4F8),
    calculated = Brand.Chalk,
    underSpecified = Color(0xFFF2B866),
    underSpecifiedText = Color(0xFFF2B866),
    error = Color(0xFFFF8A80),
    converged = Color(0xFF6FE0A1),
    convergedText = Color(0xFF6FE0A1),
    convergedContainer = Color(0xFF123D2A),
    vapour = Color(0xFFF5A524),
    liquid = Color(0xFF22B8D6),
    energy = Color(0xFFFF7A45),
    twoPhase = Color(0xFFA78BFA),
)

val LocalValueColors = staticCompositionLocalOf { LightValueColors }
