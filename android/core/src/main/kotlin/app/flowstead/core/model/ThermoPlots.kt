/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.api.BinaryDiagramKinds
import app.flowstead.core.api.BinaryDiagramResult
import app.flowstead.core.api.Phases
import app.flowstead.core.api.PropertySweepResult
import app.flowstead.core.api.SweepVariables
import app.flowstead.core.api.TernaryResult
import app.flowstead.core.units.Quantity

/** Plots of the thermodynamics tools (D100): only what the engine returned is drawn. */
object ThermoPlots {
    /** Txy or Pxy: bubble (solid) and dew (dashed) curves, and the two liquids' compositions where they split. */
    fun binary(r: BinaryDiagramResult, first: String): Plot? {
        val txy = r.kind == BinaryDiagramKinds.TXY
        val y = if (txy) Quantity.Temperature else Quantity.Pressure
        fun s(label: String, pts: List<app.flowstead.core.api.XY>, dashed: Boolean = false) =
            pts.sortedBy { it.x }.takeIf { it.size >= 2 }?.let { p -> PlotSeries(label, p.map { it.x }, p.map { it.y }, dashed) }
        // The liquid–liquid branches are each liquid's composition against T (or P): sorted by T so the line follows the dome.
        fun lle(label: String, pts: List<app.flowstead.core.api.XY>) =
            pts.sortedBy { it.y }.takeIf { it.size >= 2 }?.let { p -> PlotSeries(label, p.map { it.x }, p.map { it.y }, dashed = true) }
        val series = listOfNotNull(
            s("Bubble point", r.bubble), s("Dew point", r.dew, dashed = true),
            lle("Liquid 1", r.liquidLiquid1), lle("Liquid 2", r.liquidLiquid2),
        )
        if (series.isEmpty()) return null
        return Plot(
            if (txy) "T–x–y" else "P–x–y", "Mole fraction of $first", Quantity.Fraction,
            if (txy) "Temperature" else "Pressure", y, series,
        )
    }

    /** Keys a property chart can plot, with the label and quantity; per phase where the engine reports them so. */
    data class SweepProperty(val key: String, val label: String, val quantity: Quantity, val phase: String)

    val SWEEP_PROPERTIES: List<SweepProperty> = listOf(
        SweepProperty("vapourFraction", "Vapour fraction", Quantity.Fraction, ""),
        SweepProperty("density", "Mixture density", Quantity.Density, Phases.OVERALL),
        SweepProperty("massEnthalpy", "Mass enthalpy", Quantity.MassEnthalpy, ""),
        SweepProperty("massHeatCapacity", "Mixture Cp", Quantity.MassHeatCapacity, Phases.OVERALL),
        SweepProperty("density", "Vapour density", Quantity.Density, Phases.VAPOUR),
        SweepProperty("density", "Liquid density", Quantity.Density, Phases.LIQUID1),
        SweepProperty("viscosity", "Vapour viscosity", Quantity.Viscosity, Phases.VAPOUR),
        SweepProperty("viscosity", "Liquid viscosity", Quantity.Viscosity, Phases.LIQUID1),
        SweepProperty("compressibilityFactor", "Vapour Z factor", Quantity.Ratio, Phases.VAPOUR),
        SweepProperty("thermalConductivity", "Liquid thermal conductivity", Quantity.ThermalConductivity, Phases.LIQUID1),
    )

    /** A property along the sweep; points where the engine gave no value (no such phase, no convergence) are gaps left out. */
    fun sweep(r: PropertySweepResult, variable: String, p: SweepProperty): Plot? {
        val xs = mutableListOf<Double>()
        val ys = mutableListOf<Double>()
        r.values.zip(r.results).forEach { (x, o) ->
            val v = if (p.phase.isEmpty()) o?.properties?.get(p.key) else o?.phases?.get(p.phase)?.properties?.get(p.key)
            if (v != null && v.isFinite()) { xs += x; ys += v }
        }
        if (xs.size < 2) return null
        val t = variable == SweepVariables.TEMPERATURE
        return Plot(p.label, if (t) "Temperature" else "Pressure", if (t) Quantity.Temperature else Quantity.Pressure, p.label, p.quantity, listOf(PlotSeries(p.label, xs, ys)))
    }

    /**
     * A ternary composition on an equilateral triangle of side 1: the first compound at the top, the second bottom left,
     * the third bottom right; (x, y) with y up.
     */
    fun triangle(z: List<Double>): Pair<Double, Double> {
        val s = z.sum().takeIf { it > 0 } ?: 1.0
        val a = z[0] / s
        val c = z[2] / s
        return (c + a / 2) to (a * Math.sqrt(3.0) / 2)
    }

    /** The two-liquid points' tie lines (liquid 1 to liquid 2), each once. */
    fun tieLines(r: TernaryResult): List<Pair<List<Double>, List<Double>>> =
        r.points.filter { it.liquid1 != null && it.liquid2 != null && (it.liquid2Fraction ?: 0.0) in 1e-6..(1 - 1e-6) }
            .map { it.liquid1!! to it.liquid2!! }
            .distinctBy { (a, b) -> a.map { Math.round(it * 1000) } + b.map { Math.round(it * 1000) } }
}
