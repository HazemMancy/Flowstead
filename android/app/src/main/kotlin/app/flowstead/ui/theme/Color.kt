/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Flowstead palette (decision D64): midnight navy and engineering teal, technical blue for selection, warm amber
 * and coral for warnings and errors, slate for borders. Teal is too light to carry white text (2.3:1), so filled
 * teal buttons carry navy text (7.6:1) and teal used as text on light surfaces is [TealText] (5.1:1).
 */
object Brand {
    val Navy = Color(0xFF0B1930)
    val NavyRaised = Color(0xFF11233F)
    val NavyHigh = Color(0xFF1A2F50)
    val NavyHairline = Color(0xFF263B5E)
    val Teal = Color(0xFF19BFAE)
    val TealText = Color(0xFF0E7C72)
    val TealContainer = Color(0xFFD5F4F0)
    val TealContainerDark = Color(0xFF0D3B3A)
    val Blue = Color(0xFF5A9FF2)
    val BlueText = Color(0xFF2A68CF)
    val Canvas = Color(0xFFF3F6FA)
    val CanvasRaised = Color(0xFFE9EEF4)
    val CanvasHigh = Color(0xFFDFE6EE)
    val Hairline = Color(0xFFD3DBE5)
    val Warning = Color(0xFFF2B84B)
    /** Amber dark enough for a 3:1 badge or swatch on white. */
    val WarningSwatch = Color(0xFFB97E10)
    val Error = Color(0xFFE55868)
    val ErrorText = Color(0xFFC23A4B)
    val Slate = Color(0xFF8A98A8)
    val SlateText = Color(0xFF5B6878)

    // The mark: teal lines and a white node on the navy tile.
    val MarkTile = Navy
}
