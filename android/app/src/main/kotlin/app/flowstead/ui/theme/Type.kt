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

/** Inter for everything (Rasmus Andersson, SIL OFL 1.1), decision D64. */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** Numbers, tags and units use Inter's tabular figures, so columns of values line up. */
const val TABULAR = "tnum"

private fun inter(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = Inter, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

// Tight, confident headings (negative tracking as size grows); calm regular body text.
val FlowsteadTypography = Typography(
    displayLarge = inter(57, 64, FontWeight.Bold, -2.0),
    displayMedium = inter(45, 52, FontWeight.Bold, -1.4),
    displaySmall = inter(36, 44, FontWeight.Bold, -1.0),
    headlineLarge = inter(32, 40, FontWeight.Bold, -0.8),
    headlineMedium = inter(28, 36, FontWeight.Bold, -0.6),
    headlineSmall = inter(24, 32, FontWeight.SemiBold, -0.4),
    titleLarge = inter(22, 28, FontWeight.SemiBold, -0.3),
    titleMedium = inter(16, 24, FontWeight.SemiBold, -0.1),
    titleSmall = inter(14, 20, FontWeight.SemiBold),
    bodyLarge = inter(16, 24, FontWeight.Normal),
    bodyMedium = inter(14, 20, FontWeight.Normal),
    bodySmall = inter(12, 16, FontWeight.Normal),
    labelLarge = inter(14, 20, FontWeight.Medium),
    labelMedium = inter(12, 16, FontWeight.Medium, 0.2),
    labelSmall = inter(11, 16, FontWeight.Medium, 0.3),
)

/** Engineering values: "1,250.0 kgmol/h". */
object NumberStyles {
    val value = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp, fontFeatureSettings = TABULAR)
    val valueSpecified = value.copy(fontWeight = FontWeight.SemiBold)
    val tag = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, fontFeatureSettings = TABULAR)
    val large = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, fontFeatureSettings = TABULAR)
}
