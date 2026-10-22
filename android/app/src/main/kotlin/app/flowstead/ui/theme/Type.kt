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

/** Geist for the interface and headings (Vercel, SIL OFL 1.1). */
val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
    Font(R.font.geist_bold, FontWeight.Bold),
)

/** Geist Mono for numbers, tags and units. */
val GeistMono = FontFamily(
    Font(R.font.geist_mono_regular, FontWeight.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium),
    Font(R.font.geist_mono_semibold, FontWeight.SemiBold),
)

private fun geist(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = Geist, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

// Tight, confident headings (negative tracking as size grows); calm regular body text.
val FlowsteadTypography = Typography(
    displayLarge = geist(57, 64, FontWeight.Bold, -2.0),
    displayMedium = geist(45, 52, FontWeight.Bold, -1.4),
    displaySmall = geist(36, 44, FontWeight.Bold, -1.0),
    headlineLarge = geist(32, 40, FontWeight.Bold, -0.8),
    headlineMedium = geist(28, 36, FontWeight.Bold, -0.6),
    headlineSmall = geist(24, 32, FontWeight.SemiBold, -0.4),
    titleLarge = geist(22, 28, FontWeight.SemiBold, -0.3),
    titleMedium = geist(16, 24, FontWeight.SemiBold, -0.1),
    titleSmall = geist(14, 20, FontWeight.SemiBold),
    bodyLarge = geist(16, 24, FontWeight.Normal),
    bodyMedium = geist(14, 20, FontWeight.Normal),
    bodySmall = geist(12, 16, FontWeight.Normal),
    labelLarge = geist(14, 20, FontWeight.Medium),
    labelMedium = geist(12, 16, FontWeight.Medium, 0.2),
    labelSmall = geist(11, 16, FontWeight.Medium, 0.3),
)

/** Engineering values: "1,250.0 kgmol/h". */
object NumberStyles {
    val value = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp)
    val valueSpecified = value.copy(fontWeight = FontWeight.SemiBold)
    val tag = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp)
    val large = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 34.sp)
}
