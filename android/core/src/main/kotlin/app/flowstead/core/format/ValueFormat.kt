/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.format

import app.flowstead.core.units.EngUnit
import app.flowstead.core.units.UnitContext
import java.math.BigDecimal
import java.math.RoundingMode

/** The dash shown wherever the engine did not return a value. Never replaced by a guess. */
const val MISSING = "—"

/**
 * Formats engineering numbers the way the brand board shows them: grouped thousands,
 * '.' decimal separator, fixed decimals per unit ("1,250.0 kgmol/h", "45.00 °C").
 * Locale-independent on purpose, so a value reads the same on every phone and in exports.
 */
object ValueFormat {
    fun number(value: Double, decimals: Int): String {
        if (!value.isFinite()) return MISSING
        val bd = BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_EVEN)
        val negative = bd.signum() < 0
        val plain = bd.abs().toPlainString()
        val intPart = plain.substringBefore('.')
        val fracPart = plain.substringAfter('.', "")
        val grouped = intPart.reversed().chunked(3).joinToString(",").reversed()
        return buildString {
            if (negative) append('-')
            append(grouped)
            if (decimals > 0) append('.').append(fracPart)
        }
    }

    /** Value in [unit], converted from SI, with its symbol. */
    fun si(si: Double, unit: EngUnit, ctx: UnitContext = UnitContext(), decimals: Int = unit.decimals): String {
        val n = number(unit.fromSi(si, ctx), decimals)
        return if (n == MISSING || unit.symbol.isEmpty()) n else "$n ${unit.symbol}"
    }
}
