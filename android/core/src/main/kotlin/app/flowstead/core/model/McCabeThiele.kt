/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.api.BinaryDiagramResult
import kotlin.math.abs

/** One theoretical stage on the diagram: the liquid and vapour leaving it (light-key mole fractions). */
data class McCabeStage(val x: Double, val y: Double)

data class McCabeResult(
    /** The equilibrium curve used, (x, y) from the engine's diagram. */
    val equilibrium: List<Pair<Double, Double>>,
    val stages: List<McCabeStage>,
    /** Theoretical stages including the reboiler, with the last one counted as a fraction. */
    val theoreticalStages: Double,
    /** The stage (from the top, 1-based) where the feed enters: the first stepped below the operating lines' crossing. */
    val feedStage: Int,
    /** At total reflux. */
    val minimumStages: Double,
    /** From the pinch where the q-line meets the equilibrium curve. */
    val minimumReflux: Double?,
    val warnings: List<String>,
    /** Where the rectifying line, the stripping line and the q-line meet (x, y); null below the minimum reflux. */
    val intersection: Pair<Double, Double>? = null,
)

/**
 * McCabe–Thiele for a binary column (D106), on the equilibrium curve the engine traces at the column pressure
 * (DWSIM's T–x–y): constant molar overflow, a total condenser and a partial reboiler counted as a stage. The y of each
 * bubble point is the dew curve's composition at the same temperature. The stepping is Flowstead's arithmetic on the
 * engine's equilibrium data.
 */
object McCabeThiele {
    /** y(x) from a T–x–y diagram: each bubble point's vapour is the dew point at its temperature. */
    fun equilibrium(d: BinaryDiagramResult): List<Pair<Double, Double>> {
        val dew = d.dew.sortedBy { it.y }
        if (dew.size < 2 || d.bubble.size < 2) return emptyList()
        fun yAt(t: Double): Double? {
            for (k in 1 until dew.size) {
                val a = dew[k - 1]; val b = dew[k]
                if (t >= a.y && t <= b.y && b.y > a.y) return a.x + (b.x - a.x) * (t - a.y) / (b.y - a.y)
            }
            return null
        }
        val pts = d.bubble.mapNotNull { p -> yAt(p.y)?.let { p.x to it } } + listOf(0.0 to 0.0, 1.0 to 1.0)
        return pts.distinctBy { it.first }.sortedBy { it.first }
    }

    /** A constant relative volatility curve, y = αx / (1 + (α − 1)x), sampled for tests and quick studies. */
    fun constantAlpha(alpha: Double, points: Int = 201): List<Pair<Double, Double>> =
        (0 until points).map { i -> val x = i.toDouble() / (points - 1); x to alpha * x / (1 + (alpha - 1) * x) }

    private fun interp(curve: List<Pair<Double, Double>>, x: Double, byX: Boolean): Double {
        val pts = if (byX) curve else curve.map { it.second to it.first }.sortedBy { it.first }
        if (x <= pts.first().first) return pts.first().second
        for (k in 1 until pts.size) {
            val (x0, y0) = pts[k - 1]; val (x1, y1) = pts[k]
            if (x <= x1) return if (x1 == x0) y1 else y0 + (y1 - y0) * (x - x0) / (x1 - x0)
        }
        return pts.last().second
    }

    /**
     * Steps the stages for distillate [xD], bottoms [xB], feed [zF] of quality [q] (1 saturated liquid, 0 saturated
     * vapour) at reflux ratio [reflux] (L/D), from the top down.
     */
    fun solve(curve: List<Pair<Double, Double>>, xD: Double, xB: Double, zF: Double, q: Double, reflux: Double): McCabeResult? {
        if (curve.size < 3 || !(xD > zF && zF > xB && xB > 0 && xD < 1) || !(reflux > 0)) return null
        val warnings = mutableListOf<String>()
        fun yEq(x: Double) = interp(curve, x, byX = true)
        fun xEq(y: Double) = interp(curve, y, byX = false)
        // q-line ∩ rectifying line.
        val slope = reflux / (reflux + 1)
        val icpt = xD / (reflux + 1)
        val xi = if (abs(q - 1) < 1e-9) zF else (icpt + zF / (q - 1)) / (q / (q - 1) - slope)
        val yi = slope * xi + icpt
        if (yi >= yEq(xi)) {
            warnings += "The operating lines cross above the equilibrium curve: the reflux is below the minimum."
            return McCabeResult(curve, emptyList(), Double.POSITIVE_INFINITY, 0, minimumStages(curve, xD, xB), minimumReflux(curve, xD, zF, q), warnings)
        }
        val stripSlope = (yi - xB) / (xi - xB)
        fun op(x: Double, stripping: Boolean) = if (stripping) xB + stripSlope * (x - xB) else slope * x + icpt
        val stages = mutableListOf<McCabeStage>()
        var y = xD
        var stripping = false
        var feed = 0
        var count = 0.0
        while (stages.size < 300) {
            val x = xEq(y)
            stages += McCabeStage(x, y)
            if (x <= xB) {
                val prev = if (stages.size >= 2) stages[stages.size - 2].x else xD
                count = stages.size - 1 + fraction(prev, x, xB)
                break
            }
            if (!stripping && x < xi) { stripping = true; feed = stages.size }
            y = op(x, stripping)
        }
        if (stages.size >= 300) warnings += "More than 300 stages: the reflux is too close to the minimum."
        if (feed == 0) feed = stages.size
        return McCabeResult(curve, stages, count, feed, minimumStages(curve, xD, xB), minimumReflux(curve, xD, zF, q), warnings, xi to yi)
    }

    /**
     * The last, partial stage: how far from [from] to [to] the bottoms [xB] lies, in ln(x/(1 − x)), the measure in
     * which separation per stage is even for a constant volatility (so Fenske's count comes out exactly).
     */
    private fun fraction(from: Double, to: Double, xB: Double): Double {
        fun r(x: Double) = kotlin.math.ln(x.coerceIn(1e-12, 1 - 1e-12) / (1 - x.coerceIn(1e-12, 1 - 1e-12)))
        val span = r(from) - r(to)
        return if (span > 0) ((r(from) - r(xB)) / span).coerceIn(0.0, 1.0) else 1.0
    }

    /** Stages at total reflux (operating line y = x), including the reboiler, the last as a fraction. */
    fun minimumStages(curve: List<Pair<Double, Double>>, xD: Double, xB: Double): Double {
        var x = xD
        var n = 0
        while (n < 500) {
            val next = interp(curve, x, byX = false)
            n++
            if (next <= xB) return n - 1 + fraction(x, next, xB)
            if (abs(next - x) < 1e-12) return Double.POSITIVE_INFINITY
            x = next
        }
        return Double.POSITIVE_INFINITY
    }

    /** The reflux at which the q-line meets the equilibrium curve on the rectifying line (no tangent pinch check). */
    fun minimumReflux(curve: List<Pair<Double, Double>>, xD: Double, zF: Double, q: Double): Double? {
        // Bisection on x along the q-line for y_q(x) = y_eq(x).
        fun qLine(x: Double) = if (abs(q - 1) < 1e-9) Double.NaN else q / (q - 1) * x - zF / (q - 1)
        val (xp, yp) = if (abs(q - 1) < 1e-9) zF to interp(curve, zF, byX = true) else {
            var lo = 0.0; var hi = 1.0
            fun g(x: Double) = interp(curve, x, byX = true) - qLine(x)
            if (g(lo) * g(hi) > 0) return null
            repeat(80) { val mid = (lo + hi) / 2; if (g(lo) * g(mid) <= 0) hi = mid else lo = mid }
            val x = (lo + hi) / 2
            x to interp(curve, x, byX = true)
        }
        if (yp <= xp || xD <= yp) return null
        return (xD - yp) / (yp - xp)
    }

    /** The diagram: equilibrium curve, the diagonal, both operating lines (meeting on the q-line) and the stage steps. */
    fun plot(r: McCabeResult, xD: Double, xB: Double, reflux: Double, lightKey: String): Plot {
        // The staircase: across to each stage on the curve, then down to the next stage's vapour (the last to y = x).
        val steps = buildList {
            add(xD to xD)
            r.stages.forEachIndexed { i, st -> add(st.x to st.y); add(st.x to (r.stages.getOrNull(i + 1)?.y ?: st.x)) }
        }
        val icpt = xD / (reflux + 1)
        // Both operating lines meet where the q-line crosses them.
        val (xi, yi) = r.intersection ?: (xB to reflux / (reflux + 1) * xB + icpt)
        val series = listOfNotNull(
            PlotSeries("Equilibrium", r.equilibrium.map { it.first }, r.equilibrium.map { it.second }),
            PlotSeries("y = x", listOf(0.0, 1.0), listOf(0.0, 1.0), dashed = true),
            PlotSeries("Rectifying line", listOf(xi, xD), listOf(yi, xD), dashed = true),
            r.intersection?.let { PlotSeries("Stripping line", listOf(xB, xi), listOf(xB, yi), dashed = true) },
            PlotSeries("Stages", steps.map { it.first }, steps.map { it.second }),
        )
        return Plot("McCabe–Thiele, $lightKey", "x, liquid mole fraction of $lightKey", app.flowstead.core.units.Quantity.Fraction,
            "y, vapour mole fraction", app.flowstead.core.units.Quantity.Fraction, series,
            markers = listOf(PlotMarker("Distillate", xD, xD), PlotMarker("Bottoms", xB, xB)))
    }
}