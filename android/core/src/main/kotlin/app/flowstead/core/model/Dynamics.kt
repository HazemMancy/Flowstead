/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.api.DynamicsRequest
import app.flowstead.core.api.DynamicsResult
import app.flowstead.core.units.Quantity
import kotlinx.serialization.Serializable

/** At [time] s, [key] of [objectId] becomes [value] (SI): a feed's pressure, a valve's opening, a setpoint (D86). */
@Serializable
data class DynamicEvent(val time: Double, val objectId: String, val key: String, val value: Double)

/**
 * A flowsheet's dynamic run (D86): how long, the step, events, and what only dynamics needs, by object id (vessel
 * size, valve Cv and opening, controller tuning). Kept out of the model hash: none of it changes a steady result.
 */
@Serializable
data class DynamicsSetup(
    val duration: Double = 600.0,
    val step: Double = 1.0,
    val parameters: Map<String, Map<String, Spec>> = emptyMap(),
    val events: List<DynamicEvent> = emptyList(),
)

/** Keys the dynamic run reads (the engine's DynamicKeys). */
object DynamicKey {
    const val VOLUME = "volume"
    const val HEIGHT = "vesselHeight"
    const val INITIAL_LEVEL = "initialLevel"
    const val OPENING = "opening"
    const val RATED_CV = "ratedCv"
    const val GAIN = "gain"
    const val INTEGRAL_TIME = "integralTime"
    const val DERIVATIVE_TIME = "derivativeTime"
    const val PV_MINIMUM = "pvMinimum"
    const val PV_MAXIMUM = "pvMaximum"
    const val ACTION = "action"
    const val MODE = "mode"

    fun label(key: String): String = when (key) {
        VOLUME -> "Volume"
        HEIGHT -> "Height (liquid span)"
        INITIAL_LEVEL -> "Starting level"
        OPENING -> "Opening at the start"
        RATED_CV -> "Rated Cv (fully open)"
        GAIN -> "Gain Kc"
        INTEGRAL_TIME -> "Integral time Ti"
        DERIVATIVE_TIME -> "Derivative time Td"
        PV_MINIMUM -> "Measurement range from"
        PV_MAXIMUM -> "Measurement range to"
        else -> key
    }

    fun quantity(key: String): Quantity = when (key) {
        VOLUME -> Quantity.Volume
        HEIGHT -> Quantity.Length
        INITIAL_LEVEL, OPENING -> Quantity.Fraction
        RATED_CV, GAIN -> Quantity.Ratio
        INTEGRAL_TIME, DERIVATIVE_TIME -> Quantity.Time
        else -> Quantity.Ratio
    }
}

object Dynamics {
    /** Units a dynamic run models in 2.0.0 (the engine refuses others with a reason). */
    val SUPPORTED = setOf(
        ObjectType.MaterialStream, ObjectType.EnergyStream, ObjectType.Separator, ObjectType.Valve, ObjectType.Heater,
        ObjectType.Cooler, ObjectType.AirCooler, ObjectType.Mixer, ObjectType.Pump, ObjectType.Compressor, ObjectType.Expander,
        ObjectType.Controller, ObjectType.Set, ObjectType.Adjust, ObjectType.ReliefValve,
    )

    /** Measurements a controller can take only in a dynamic run (D86). */
    val DYNAMIC_ONLY = setOf("liquidLevel", "levelFraction")

    /** What a controller can measure on [o] in a dynamic run. */
    fun measurable(o: SimObject): List<String> = when (o.type) {
        ObjectType.Separator -> listOf("pressure", "temperature", "liquidLevel", "levelFraction")
        ObjectType.MaterialStream -> listOf("pressure", "temperature", "massFlow", "molarFlow")
        else -> emptyList()
    }

    fun setup(fs: Flowsheet) = fs.dynamics ?: DynamicsSetup()

    fun parameter(fs: Flowsheet, id: String, key: String): Spec? = setup(fs).parameters[id]?.get(key)

    /** Why the flowsheet can't run dynamically yet, or null. */
    fun problem(fs: Flowsheet): String? {
        val vessels = fs.objects.filter { it.type == ObjectType.Separator }
        fs.objects.firstOrNull { it.type !in SUPPORTED }?.let {
            return "${it.tag}: ${it.type.displayName().lowercase()} is not in dynamic runs yet (2.0 runs separators, valves, heaters, coolers, mixers, pumps, compressors and controllers)"
        }
        if (vessels.isEmpty()) return "A dynamic run needs a separator to hold inventory"
        vessels.firstOrNull { parameter(fs, it.id, DynamicKey.VOLUME) == null }?.let { return "${it.tag}: give its volume" }
        val s = setup(fs)
        if (!(s.duration > 0)) return "Give the run's duration"
        if (!(s.step > 0) || s.step > s.duration) return "The step must be positive and shorter than the run"
        if (s.duration / s.step > 20_000) return "That is more than 20 000 steps; lengthen the step or shorten the run"
        return null
    }

    fun request(fs: Flowsheet): DynamicsRequest {
        val s = setup(fs)
        return DynamicsRequest(fs.copy(dynamics = null), s.duration, s.step, s.events, s.parameters)
    }

    /** Trends as plots: each controller's measurement against its setpoint and its output, then vessels, valves and duties. */
    fun plots(fs: Flowsheet, r: DynamicsResult): List<Plot> {
        val t = r.times
        if (t.size < 2) return emptyList()
        fun trend(id: String, key: String) = r.trends.firstOrNull { it.objectId == id && it.key == key }?.values?.takeIf { it.size == t.size }
        fun tag(id: String) = fs.obj(id)?.tag ?: id
        val plots = mutableListOf<Plot>()
        for (c in fs.objects.filter { it.type == ObjectType.Controller }) {
            val pv = trend(c.id, "controlledValue") ?: continue
            val key = c.refs[AdjustKeys.CONTROLLED_PROPERTY] ?: continue
            val q = ResultKeys.quantity(key)
            plots += Plot("${c.tag} PV and SP", "Time", Quantity.Time, ResultKeys.label(key), q, listOfNotNull(
                PlotSeries("Measured (PV)", t, pv),
                trend(c.id, "target")?.let { PlotSeries("Setpoint (SP)", t, it, dashed = true) },
            ))
            trend(c.id, "output")?.let { op ->
                plots += Plot("${c.tag} output", "Time", Quantity.Time, "Output (OP)", Quantity.Fraction, listOf(PlotSeries("OP", t, op.map { it / 100 })))
            }
        }
        for (v in fs.objects.filter { it.type == ObjectType.Separator }) {
            trend(v.id, "pressure")?.let { plots += Plot("${v.tag} pressure", "Time", Quantity.Time, "Pressure", Quantity.Pressure, listOf(PlotSeries(v.tag, t, it))) }
            trend(v.id, "liquidLevel")?.let { plots += Plot("${v.tag} level", "Time", Quantity.Time, "Liquid level", Quantity.Length, listOf(PlotSeries(v.tag, t, it))) }
            trend(v.id, "temperature")?.let { plots += Plot("${v.tag} temperature", "Time", Quantity.Time, "Temperature", Quantity.Temperature, listOf(PlotSeries(v.tag, t, it))) }
        }
        val valves = fs.objects.filter { it.type == ObjectType.Valve }
        valves.mapNotNull { v -> trend(v.id, "valveOpening")?.let { PlotSeries(tag(v.id), t, it) } }.takeIf { it.isNotEmpty() }
            ?.let { plots += Plot("Valve openings", "Time", Quantity.Time, "Opening", Quantity.Fraction, it) }
        valves.mapNotNull { v -> trend(v.id, "massFlow")?.let { PlotSeries(tag(v.id), t, it) } }.takeIf { it.isNotEmpty() }
            ?.let { plots += Plot("Flows", "Time", Quantity.Time, "Mass flow", Quantity.MassFlow, it) }
        fs.objects.filter { it.type in setOf(ObjectType.Heater, ObjectType.Cooler, ObjectType.AirCooler) }
            .mapNotNull { h -> trend(h.id, "duty")?.let { PlotSeries(tag(h.id), t, it) } }.takeIf { it.isNotEmpty() }
            ?.let { plots += Plot("Duties", "Time", Quantity.Time, "Duty", Quantity.Power, it) }
        return plots
    }

    /** What an event can change on [o], with the quantity of its value. */
    fun eventKeys(fs: Flowsheet, o: SimObject): List<Pair<String, Quantity>> = when (o.type) {
        ObjectType.MaterialStream -> if (fs.isFeed(o)) listOf(SpecKey.PRESSURE to Quantity.Pressure, SpecKey.TEMPERATURE to Quantity.Temperature)
            else if (fs.connections.none { it.fromObject == o.id }) listOf(SpecKey.PRESSURE to Quantity.Pressure) else emptyList()
        ObjectType.Valve -> listOf(DynamicKey.OPENING to Quantity.Fraction)
        ObjectType.Controller -> listOf(AdjustKeys.TARGET to (o.refs[AdjustKeys.CONTROLLED_PROPERTY]?.let { ResultKeys.quantity(it) } ?: Quantity.Ratio), DynamicKey.MODE to Quantity.Ratio)
        ObjectType.Heater, ObjectType.Cooler, ObjectType.AirCooler -> listOf(SpecKey.DUTY to Quantity.Power, SpecKey.OUTLET_TEMPERATURE to Quantity.Temperature)
        else -> emptyList()
    }

    fun eventLabel(key: String): String = when (key) {
        AdjustKeys.TARGET -> "Setpoint"
        DynamicKey.MODE -> "Mode (1 auto, 0 manual)"
        DynamicKey.OPENING -> "Opening"
        else -> SpecKey.label(key)
    }
}
