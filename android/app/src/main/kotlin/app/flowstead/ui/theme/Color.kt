/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Brand palette (decision D37): "Precision" colours — paper, ink and one signal orange — with the
 * "Aurora" mark, a violet-to-cyan flow line on a dark tile. Signal orange fills and highlights; text
 * that must read as orange uses [SignalText] (light) or [SignalLight] (dark).
 */
object Brand {
    val Paper = Color(0xFFF6F6F3)
    val PaperRaised = Color(0xFFEFEFEA)
    val PaperHigh = Color(0xFFE8E8E2)
    val Hairline = Color(0xFFDADAD3)
    val Ink = Color(0xFF111113)
    val InkRaised = Color(0xFF18181B)
    val InkHigh = Color(0xFF222226)
    val InkHairline = Color(0xFF2E2E33)
    val Graphite = Color(0xFF5F5F66)
    val Fog = Color(0xFFA8A8B0)
    val Chalk = Color(0xFFF2F2EE)
    val Signal = Color(0xFFFF5B1F)
    val SignalText = Color(0xFFB83A0A)
    val SignalLight = Color(0xFFFF8A5C)

    // The mark's gradient and tile.
    val Violet = Color(0xFF8B5CF6)
    val Cyan = Color(0xFF22D3EE)
    val MarkTile = Color(0xFF0E1322)
}
