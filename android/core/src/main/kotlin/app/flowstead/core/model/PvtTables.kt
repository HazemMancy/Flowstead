/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.api.PvtExperiments
import app.flowstead.core.api.PvtResult
import app.flowstead.core.units.Quantity

/** How PVT results (D53) are labelled, which quantity each is, and which plots each experiment gives. */
object PvtTables {
    fun experimentLabel(e: String): String = when (e) {
        PvtExperiments.SATURATION -> "Saturation pressure"
        PvtExperiments.CCE -> "Constant composition expansion"
        PvtExperiments.CVD -> "Constant volume depletion"
        PvtExperiments.DL -> "Differential liberation"
        PvtExperiments.SEPARATOR -> "Separator test"
        else -> e
    }

    fun label(key: String): String = when (key) {
        "pressure" -> "Pressure"
        "temperature" -> "Temperature"
        "relativeVolume" -> "Relative volume V/Vsat"
        "liquidDropout" -> "Liquid dropout (of Vsat)"
        "vapourFraction" -> "Vapour fraction (molar)"
        "gasZ" -> "Gas Z"
        "twoPhaseZ" -> "Two-phase Z"
        "density" -> "Density"
        "gasDensity" -> "Gas density"
        "yFunction" -> "Y-function"
        "gasProduced" -> "Gas produced (of initial moles)"
        "oilFvf" -> "Oil formation volume factor Bo"
        "solutionGor" -> "Solution GOR Rs (Sm³/m³)"
        "gor" -> "GOR (Sm³/m³ stock-tank oil)"
        "gasGravity" -> "Gas gravity (air = 1)"
        "oilDensity" -> "Oil density"
        "saturationPressure" -> "Saturation pressure"
        "totalGor" -> "Total GOR (Sm³/m³ stock-tank oil)"
        "stockTankOilDensity" -> "Stock-tank oil density"
        "api" -> "Stock-tank oil °API"
        "residualOilDensity" -> "Residual oil density (60 °F)"
        else -> key
    }

    fun quantity(key: String): Quantity = when (key) {
        "pressure", "saturationPressure" -> Quantity.Pressure
        "temperature" -> Quantity.Temperature
        "liquidDropout", "vapourFraction", "gasProduced" -> Quantity.Fraction
        "density", "gasDensity", "oilDensity", "stockTankOilDensity", "residualOilDensity" -> Quantity.Density
        else -> Quantity.Ratio
    }

    /** The plots against pressure that tell the story of each experiment. */
    fun plots(r: PvtResult): List<Plot> {
        val p = r.table["pressure"] ?: return emptyList()
        val keys = when (r.experiment) {
            PvtExperiments.CCE -> listOf("relativeVolume", "liquidDropout", "gasZ", "yFunction", "density")
            PvtExperiments.CVD -> listOf("liquidDropout", "gasProduced", "gasZ", "twoPhaseZ")
            PvtExperiments.DL -> listOf("oilFvf", "solutionGor", "oilDensity", "gasGravity")
            else -> emptyList()
        }
        return keys.mapNotNull { k ->
            val ys = r.table[k] ?: return@mapNotNull null
            // Steps without a value are left out, never bridged.
            val pts = p.indices.filter { p[it] != null && ys.getOrNull(it) != null }
            if (pts.size < 2) return@mapNotNull null
            Plot(label(k), "Pressure", Quantity.Pressure, label(k), quantity(k), listOf(PlotSeries(label(k), pts.map { p[it]!! }, pts.map { ys[it]!! })))
        }
    }
}
