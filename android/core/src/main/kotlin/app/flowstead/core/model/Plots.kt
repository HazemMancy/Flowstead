/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.api.PhaseEnvelope
import app.flowstead.core.api.ProfileKeys
import app.flowstead.core.units.Quantity
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** One line on a plot, SI. [parameter]: the value (SI) of the plot's parameter this line is drawn at, if any. */
data class PlotSeries(val label: String, val x: List<Double>, val y: List<Double>, val dashed: Boolean = false, val parameter: Double? = null)

/** A labelled point on a plot (critical point, operating point…), SI. */
data class PlotMarker(val label: String, val x: Double, val y: Double)

/** A line plot of engine numbers (D50): every series and marker is SI, converted to the user's units when drawn. */
data class Plot(
    val title: String,
    val xLabel: String,
    val xQuantity: Quantity,
    val yLabel: String,
    val yQuantity: Quantity,
    val series: List<PlotSeries>,
    val markers: List<PlotMarker> = emptyList(),
    /** A family of curves: what each line's [PlotSeries.parameter] is (a case study's second variable). */
    val parameterLabel: String? = null,
    val parameterQuantity: Quantity? = null,
)

object Plots {
    /** The plots a unit's profiles give, in the order they are offered; empty when the engine returned none. */
    fun forObject(type: ObjectType, profiles: Map<String, List<Double>>?): List<Plot> {
        val p = profiles ?: return emptyList()
        fun series(label: String, x: String, y: String, dashed: Boolean = false): PlotSeries? {
            val xs = p[x] ?: return null
            val ys = p[y] ?: return null
            return if (xs.size == ys.size && xs.size >= 2) PlotSeries(label, xs, ys, dashed) else null
        }
        fun plot(title: String, xLabel: String, xq: Quantity, yLabel: String, yq: Quantity, vararg s: PlotSeries?) =
            s.filterNotNull().takeIf { it.isNotEmpty() }?.let { Plot(title, xLabel, xq, yLabel, yq, it) }

        val L = ProfileKeys
        return when (type) {
            ObjectType.HeatExchanger -> listOfNotNull(
                plot(
                    "Temperature – heat flow", "Heat transferred from the hot inlet end", Quantity.Power, "Temperature", Quantity.Temperature,
                    series("Hot side", L.HEAT_FLOW, L.HOT_TEMPERATURE), series("Cold side", L.HEAT_FLOW, L.COLD_TEMPERATURE),
                ),
            )
            ObjectType.PipeSegment -> listOfNotNull(
                plot("Pressure", "Distance from the inlet", Quantity.Length, "Pressure", Quantity.Pressure, series("Pressure", L.LENGTH, L.PRESSURE)),
                plot("Temperature", "Distance from the inlet", Quantity.Length, "Temperature", Quantity.Temperature, series("Temperature", L.LENGTH, L.TEMPERATURE)),
                plot("Liquid holdup", "Distance from the inlet", Quantity.Length, "Holdup", Quantity.Fraction, series("Holdup", L.LENGTH, L.LIQUID_HOLDUP)),
                plot("Velocity", "Distance from the inlet", Quantity.Length, "Mixture velocity", Quantity.Velocity, series("Velocity", L.LENGTH, L.VELOCITY)),
            )
            ObjectType.DistillationColumn, ObjectType.Absorber, ObjectType.RefluxedAbsorber, ObjectType.ReboiledAbsorber -> listOfNotNull(
                plot("Temperature", "Stage (1 = top)", Quantity.Count, "Temperature", Quantity.Temperature, series("Temperature", L.STAGE, L.TEMPERATURE)),
                plot(
                    "Flows", "Stage (1 = top)", Quantity.Count, "Molar flow leaving the stage", Quantity.MolarFlow,
                    series("Vapour", L.STAGE, L.VAPOUR_FLOW), series("Liquid", L.STAGE, L.LIQUID_FLOW, dashed = true),
                ),
                plot("Pressure", "Stage (1 = top)", Quantity.Count, "Pressure", Quantity.Pressure, series("Pressure", L.STAGE, L.PRESSURE)),
            )
            else -> emptyList()
        }
    }

    /** P–T phase envelope with its special points and, when given, the stream's own state. */
    fun envelope(env: PhaseEnvelope, streamTemperature: Double? = null, streamPressure: Double? = null): Plot? {
        fun ok(s: PlotSeries) = s.takeIf { it.x.size >= 2 && it.x.size == it.y.size }
        val envelope = listOfNotNull(
            ok(PlotSeries("Bubble point", env.bubbleTemperature, env.bubblePressure)),
            ok(PlotSeries("Dew point", env.dewTemperature, env.dewPressure, dashed = true)),
        )
        if (envelope.isEmpty()) return null
        // Quality lines (D52) dotted, then the hydrate curve (D52), each only where the engine traced it.
        val series = envelope +
            env.qualityLines.mapNotNull { ok(PlotSeries("VF ${"%.2f".format(java.util.Locale.ROOT, it.quality).trimEnd('0').trimEnd('.')}", it.temperature, it.pressure, dashed = true)) } +
            listOfNotNull(
                ok(PlotSeries("Hydrate formation", env.hydrateTemperature, env.hydratePressure)),
                ok(PlotSeries("Hydrate, inhibited", env.inhibitedHydrateTemperature, env.hydratePressure, dashed = true)),
            )
        val markers = listOfNotNull(
            point("Critical point", env.criticalTemperature, env.criticalPressure),
            point("Cricondenbar", env.cricondenbarTemperature, env.cricondenbarPressure),
            point("Cricondentherm", env.cricondenthermTemperature, env.cricondenthermPressure),
            point("This stream", streamTemperature, streamPressure),
        )
        return Plot("Phase envelope", "Temperature", Quantity.Temperature, "Pressure", Quantity.Pressure, series, markers)
    }

    private fun point(label: String, x: Double?, y: Double?) =
        if (x != null && y != null && x.isFinite() && y.isFinite()) PlotMarker(label, x, y) else null

    /**
     * Round tick values (1, 2 or 5 × 10ⁿ apart) covering [min]..[max], about [target] of them. A flat range is
     * widened so a constant series still gets an axis.
     */
    fun ticks(min: Double, max: Double, target: Int = 5): List<Double> {
        var lo = min
        var hi = max
        if (!(lo.isFinite() && hi.isFinite())) return emptyList()
        if (hi - lo < 1e-12 * maxOf(1.0, abs(hi))) {
            val pad = if (hi == 0.0) 1.0 else abs(hi) * 0.05
            lo -= pad
            hi += pad
        }
        val raw = (hi - lo) / target.coerceAtLeast(1)
        val magnitude = 10.0.pow(floor(log10(raw)))
        val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * magnitude }.first { it >= raw }
        val first = floor(lo / step) * step
        val last = ceil(hi / step) * step
        val n = ((last - first) / step).toInt()
        // Rounding keeps 0.30000000000000004 from showing.
        return (0..n).map { i -> (first + i * step).let { v -> if (abs(v) < step * 1e-9) 0.0 else v } }
    }
}
