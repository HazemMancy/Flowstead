/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.flowstead.R

/** Manrope for UI and headings. */
val Manrope = FontFamily(
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
    Font(R.font.manrope_extrabold, FontWeight.ExtraBold),
)

/** IBM Plex Mono for numbers, tags and units. */
val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_mono_semibold, FontWeight.SemiBold),
)

private fun manrope(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = Manrope, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

val FlowsteadTypography = Typography(
    displayLarge = manrope(57, 64, FontWeight.ExtraBold, -1.5),
    displayMedium = manrope(45, 52, FontWeight.ExtraBold, -1.0),
    displaySmall = manrope(36, 44, FontWeight.ExtraBold, -0.5),
    headlineLarge = manrope(32, 40, FontWeight.ExtraBold, -0.5),
    headlineMedium = manrope(28, 36, FontWeight.ExtraBold),
    headlineSmall = manrope(24, 32, FontWeight.ExtraBold),
    titleLarge = manrope(22, 28, FontWeight.ExtraBold),
    titleMedium = manrope(16, 24, FontWeight.Bold, 0.1),
    titleSmall = manrope(14, 20, FontWeight.Bold, 0.1),
    bodyLarge = manrope(16, 24, FontWeight.Medium),
    bodyMedium = manrope(14, 20, FontWeight.Medium),
    bodySmall = manrope(12, 16, FontWeight.Medium),
    labelLarge = manrope(14, 20, FontWeight.Bold, 0.1),
    labelMedium = manrope(12, 16, FontWeight.Bold, 0.3),
    labelSmall = manrope(11, 16, FontWeight.Bold, 0.3),
)

/** Engineering values: "1,250.0 kgmol/h". */
object NumberStyles {
    val value = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp)
    val valueSpecified = value.copy(fontWeight = FontWeight.SemiBold)
    val tag = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp)
    val large = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 34.sp)
}
