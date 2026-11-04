/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A sieve tray's layout (SI): spacing, weir height, hole size and share of the active area, downcomer share, C0. */
data class TrayLayout(
    val diameter: Double,
    val traySpacing: Double = ColumnSizing.DEFAULT_TRAY_SPACING,
    val weirHeight: Double = 0.050,
    val holeDiameter: Double = 0.005,
    /** Hole area over active (bubbling) area. */
    val holeFraction: Double = 0.10,
    /** One downcomer's area over the column's. */
    val downcomerFraction: Double = ColumnSizing.DEFAULT_DOWNCOMER,
    /** Dry-plate orifice coefficient (Sinnott Fig. 11.34: about 0.84 for 10 % holes and a plate as thick as the hole). */
    val orificeCoefficient: Double = 0.84,
    val systemFactor: Double = 1.0,
)

/** One tray's rating; heads in m of hot liquid, pressure drop in Pa, residence time in s. */
data class TrayRating(
    val stage: Int,
    val percentFlood: Double,
    val weirCrest: Double,
    val dryPlate: Double,
    val residual: Double,
    val totalHead: Double,
    val pressureDrop: Double,
    val downcomerBackup: Double,
    val downcomerResidence: Double,
)

data class TrayHydraulicsResult(
    val weirLength: Double,
    val trays: List<TrayRating>,
    val columnPressureDrop: Double,
    val warnings: List<String>,
)

/**
 * Column internals (D106): a sieve-tray rating at a chosen diameter, tray by tray from the engine's solved traffic and
 * properties, by the methods of Sinnott (Coulson & Richardson's Chemical Engineering Design, Vol. 6, §11.13): flooding
 * by Fair (as the sizing, D65), weir crest by Francis how = 750 (Lw / ρL·lw)^⅔ mm, dry-plate drop hd = 51 (uh/C0)² ρV/ρL mm,
 * residual head 12.5·10³/ρL mm, downcomer backup hb = hw + how + ht + hdc with hdc = 166 (Lwd / ρL·Am)² mm and
 * Am = (hw − 10 mm)·lw, and the downcomer residence time Ad·hb·ρL / Lwd. Weeping and entrainment are not checked.
 */
object TrayHydraulics {
    /** Weir (chord) length for a segmental downcomer taking [fraction] of the column area. */
    fun weirLength(diameter: Double, fraction: Double): Double {
        // Segment area over circle area = (θ − sin θ)/2π for the chord's central angle θ.
        var lo = 0.0; var hi = PI
        repeat(80) { val mid = (lo + hi) / 2; if ((mid - sin(mid)) / (2 * PI) < fraction) lo = mid else hi = mid }
        val theta = (lo + hi) / 2
        return diameter * sin(theta / 2)
    }

    /** Downcomer area fraction for a weir of length lw (the inverse of [weirLength]). */
    fun downcomerFraction(diameter: Double, weirLength: Double): Double {
        val theta = 2 * asin((weirLength / diameter).coerceIn(0.0, 1.0))
        return (theta - sin(theta)) / (2 * PI)
    }

    fun rate(type: ObjectType, profile: Map<String, List<Double>>, layout: TrayLayout): TrayHydraulicsResult? {
        val v = profile["vapourFlow"] ?: return null
        val l = profile["liquidFlow"] ?: return null
        val rv = profile["vapourDensity"] ?: return null
        val rl = profile["liquidDensity"] ?: return null
        val mv = profile["vapourMolarMass"] ?: return null
        val ml = profile["liquidMolarMass"] ?: return null
        val st = profile["surfaceTension"] ?: return null
        val n = v.size
        if (listOf(l, rv, rl, mv, ml, st).any { it.size != n } || !(layout.diameter > 0)) return null
        val first = if (Columns.hasCondenser(type)) 1 else 0
        val last = n - 1 - (if (Columns.hasReboiler(type)) 1 else 0)
        if (last < first) return null
        val ac = PI * layout.diameter * layout.diameter / 4
        val ad = layout.downcomerFraction * ac
        val an = ac - ad
        val aa = ac - 2 * ad
        val ah = layout.holeFraction * aa
        val lw = weirLength(layout.diameter, layout.downcomerFraction)
        val hap = layout.weirHeight - 0.010
        val am = hap * lw
        val warnings = mutableListOf<String>()
        val g = 9.81
        val trays = (first..last).mapNotNull { i ->
            val wv = v[i] * mv[i] / 1000 // kg/s
            val wl = l[i] * ml[i] / 1000
            if (wv <= 0 || rl[i] <= rv[i]) return@mapNotNull null
            val flv = (wl / wv) * sqrt(rv[i] / rl[i])
            val uf = ColumnSizing.csbFlood(flv, layout.traySpacing) * (st[i] * 1000 / 20).pow(0.2) * layout.systemFactor * sqrt((rl[i] - rv[i]) / rv[i])
            val flood = wv / rv[i] / an / uf
            val how = 750 * (wl / (rl[i] * lw)).pow(2.0 / 3) / 1000
            val uh = wv / rv[i] / ah
            val hd = 51 * (uh / layout.orificeCoefficient).pow(2) * rv[i] / rl[i] / 1000
            val hr = 12.5e3 / rl[i] / 1000
            val ht = hd + layout.weirHeight + how + hr
            val hdc = if (am > 0) 166 * (wl / (rl[i] * am)).pow(2) / 1000 else Double.NaN
            val hb = layout.weirHeight + how + ht + hdc
            val tr = if (wl > 0) ad * hb * rl[i] / wl else Double.POSITIVE_INFINITY
            TrayRating(i + 1, flood * 100, how, hd, hr, ht, ht * rl[i] * g, hb, tr)
        }
        if (trays.isEmpty()) return null
        if (trays.any { it.percentFlood > 85 }) warnings += "Above 85 % of flood on some trays: enlarge the diameter or the tray spacing."
        if (trays.any { it.downcomerBackup > 0.5 * (layout.traySpacing + layout.weirHeight) }) warnings += "Downcomer backup above half the tray spacing plus weir height: risk of downcomer flooding."
        if (trays.any { it.downcomerResidence < 3 }) warnings += "Downcomer residence under 3 s on some trays: vapour may be carried under the downcomer."
        if (hap <= 0) warnings += "The weir is too low for the 10 mm downcomer clearance the backup method assumes."
        warnings += "Weeping and entrainment are not checked."
        return TrayHydraulicsResult(lw, trays, trays.sumOf { it.pressureDrop }, warnings)
    }
}
