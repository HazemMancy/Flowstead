/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.core.model

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/** One tray's flooding check: what sets the diameter there. */
data class TrayLoad(val stage: Int, val flowParameter: Double, val floodVelocity: Double, val diameter: Double)

data class TraySizing(
    val trays: List<TrayLoad>,
    /** The largest tray's required diameter, and the diameter rounded up to 50 mm. */
    val requiredDiameter: Double,
    val diameter: Double,
    val controllingStage: Int,
    val actualTrays: Int,
    val height: Double,
    val warnings: List<String>,
)

/**
 * Sieve or valve tray sizing at flooding (D65), as HYSYS's tray sizing utility: Fair's flooding correlation in the
 * Lygeros–Magoulas fit, C_sb (m/s) = 0.0105 + 0.1496 · TS^0.755 · exp(−1.463 · F_LV^0.842) with TS the tray
 * spacing in m and F_LV = (L/V)·√(ρV/ρL) on a mass basis, corrected (σ/20)^0.2 for surface tension (dyn/cm) and
 * by a system (foaming) factor. Net area at the design fraction of flood, plus the downcomers; every stage between
 * the condenser and the reboiler is checked with its own solved traffic and properties.
 */
object ColumnSizing {
    const val DEFAULT_TRAY_SPACING = 0.6096 // 24 in
    const val DEFAULT_FLOOD = 0.80
    const val DEFAULT_DOWNCOMER = 0.12
    const val DEFAULT_EFFICIENCY = 0.70
    const val TOP_SPACE = 1.2
    const val BOTTOM_SPACE = 1.8

    /** Fair's capacity parameter at flooding (m/s) at σ = 20 dyn/cm; [traySpacing] in m. */
    fun csbFlood(flowParameter: Double, traySpacing: Double): Double =
        0.0105 + 0.1496 * traySpacing.pow(0.755) * exp(-1.463 * flowParameter.pow(0.842))

    fun size(
        type: ObjectType,
        profile: Map<String, List<Double>>,
        traySpacing: Double = DEFAULT_TRAY_SPACING,
        flood: Double = DEFAULT_FLOOD,
        downcomer: Double = DEFAULT_DOWNCOMER,
        efficiency: Double = DEFAULT_EFFICIENCY,
        systemFactor: Double = 1.0,
    ): TraySizing? {
        val v = profile["vapourFlow"] ?: return null
        val l = profile["liquidFlow"] ?: return null
        val rv = profile["vapourDensity"] ?: return null
        val rl = profile["liquidDensity"] ?: return null
        val mv = profile["vapourMolarMass"] ?: return null
        val ml = profile["liquidMolarMass"] ?: return null
        val st = profile["surfaceTension"] ?: return null
        val n = v.size
        if (listOf(l, rv, rl, mv, ml, st).any { it.size != n }) return null
        // Trays only: the condenser (first) and reboiler (last) stages are not trays.
        val first = if (Columns.hasCondenser(type)) 1 else 0
        val last = n - 1 - (if (Columns.hasReboiler(type)) 1 else 0)
        if (last < first) return null
        val warnings = mutableListOf<String>()
        val trays = (first..last).mapNotNull { i ->
            val wv = v[i] * mv[i] / 1000 // kg/s
            val wl = l[i] * ml[i] / 1000
            if (wv <= 0) return@mapNotNull null
            val flv = (wl / wv) * sqrt(rv[i] / rl[i])
            val c = csbFlood(flv, traySpacing) * (st[i] * 1000 / 20).pow(0.2) * systemFactor
            val uf = c * sqrt((rl[i] - rv[i]) / rv[i])
            val area = wv / rv[i] / (flood * uf) / (1 - downcomer)
            TrayLoad(i + 1, flv, uf, sqrt(4 * area / PI))
        }
        if (trays.isEmpty()) return null
        val worst = trays.maxBy { it.diameter }
        if (trays.any { it.flowParameter > 1.0 || it.flowParameter < 0.01 }) {
            warnings += "The flow parameter leaves Fair's range (0.01–1) on some trays: check the diameter against a vendor's rating."
        }
        val d = ceil(worst.diameter / 0.05 - 1e-9) * 0.05
        if (d < 0.6) warnings += "Under 0.6 m: trays are hard to install; a packed column is usual."
        val actual = ceil(trays.size / efficiency - 1e-9).toInt()
        val height = (actual - 1) * traySpacing + TOP_SPACE + BOTTOM_SPACE
        return TraySizing(trays, worst.diameter, d, worst.stage, actual, height, warnings)
    }
}
