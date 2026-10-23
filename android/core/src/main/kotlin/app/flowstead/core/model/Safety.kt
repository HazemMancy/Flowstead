/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.api.DepressuringRequest
import app.flowstead.core.api.DepressuringResult
import app.flowstead.core.api.ReliefDevice
import app.flowstead.core.api.ReliefLine
import app.flowstead.core.api.ReliefResult
import app.flowstead.core.api.ReliefScenario
import app.flowstead.core.api.ReliefScenarioKinds
import app.flowstead.core.api.Phases
import app.flowstead.core.api.ValveDesigns
import app.flowstead.core.api.VesselGeometry
import app.flowstead.core.units.Quantity
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/** The project's safety analysis (D54–D57): relief valves and depressuring cases, with their last results. */
@Serializable
data class SafetyStudy(
    val devices: List<ReliefDeviceDef> = emptyList(),
    val depressuring: List<DepressuringCase> = emptyList(),
)

/**
 * A relief valve as the user sets it up: the equipment it protects (whose feed gives the fluid and operating state),
 * the valve and its scenarios. [result] is the engine's last answer, for the flowsheet whose hash is [resultHash].
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ReliefDeviceDef(
    val id: String,
    val tag: String,
    val protectedId: String? = null,
    val setPressure: Double? = null,
    val backPressure: Double = 101325.0,
    val design: String = ValveDesigns.CONVENTIONAL,
    val ruptureDisk: Boolean = false,
    val kb: Double = 1.0,
    val vessel: VesselGeometry? = null,
    val scenarios: List<ReliefScenario> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val inletLine: ReliefLine? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val outletLine: ReliefLine? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val result: ReliefResult? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val resultHash: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DepressuringCase(
    val id: String,
    val name: String,
    val protectedId: String? = null,
    val vessel: VesselGeometry? = null,
    val backPressure: Double = 101325.0,
    /** Null: Flowstead sizes the orifice to the 15-minute target. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val orificeDiameter: Double? = null,
    val withFire: Boolean = false,
    val heatTransferCoefficient: Double = 30.0,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val result: DepressuringResult? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val resultHash: String? = null,
)

object Safety {
    /** What a relief valve or depressuring case can protect. */
    val PROTECTABLE = setOf(ObjectType.Separator, ObjectType.ThreePhaseSeparator, ObjectType.Tank)

    /** The vessel's contents in normal operation: its first inlet stream's solved state and composition. */
    data class Contents(val composition: Map<String, Double>, val temperature: Double, val pressure: Double, val massFlow: Double?)

    fun contents(fs: Flowsheet, results: ResultView, protectedId: String?): Contents? {
        val id = protectedId ?: return null
        val feed = fs.connections.filter { it.toObject == id }.map { it.fromObject }
            .firstOrNull { s -> fs.objects.any { it.id == s && it.type == ObjectType.MaterialStream } } ?: id.takeIf { fs.objects.any { o -> o.id == id && o.type == ObjectType.MaterialStream } }
            ?: return null
        val t = (results.value(feed, "temperature") as? DisplayValue.Calculated)?.si ?: return null
        val p = (results.value(feed, "pressure") as? DisplayValue.Calculated)?.si ?: return null
        val x = fs.compounds.associateWith { c -> (results.composition(feed, Phases.OVERALL, c) as? DisplayValue.Calculated)?.si ?: 0.0 }.filterValues { it > 0 }
        if (x.isEmpty()) return null
        val w = (results.value(feed, "massFlow") as? DisplayValue.Calculated)?.si
        return Contents(x, t, p, w)
    }

    /** What stops the valve from being sized, or null. */
    fun problem(fs: Flowsheet, results: ResultView, d: ReliefDeviceDef): String? = when {
        d.protectedId == null || fs.objects.none { it.id == d.protectedId } -> "Choose the equipment it protects"
        d.setPressure == null -> "Give the set pressure"
        d.scenarios.isEmpty() -> "Add at least one relief scenario"
        results.stale || contents(fs, results, d.protectedId) == null -> "Solve the flowsheet first: the fluid and its state come from the solve"
        d.scenarios.any { it.kind == ReliefScenarioKinds.FIRE } && d.vessel == null && d.scenarios.none { it.area != null } ->
            "Give the vessel's dimensions for the fire case"
        else -> null
    }

    /** The engine request for one valve: the protected equipment's contents with the user's valve and scenarios. */
    fun request(fs: Flowsheet, results: ResultView, d: ReliefDeviceDef): ReliefDevice {
        val c = contents(fs, results, d.protectedId) ?: error("No contents")
        return ReliefDevice(
            id = d.id, tag = d.tag, setPressure = d.setPressure!!, backPressure = d.backPressure, design = d.design,
            kb = d.kb, ruptureDisk = d.ruptureDisk, composition = c.composition, operatingTemperature = c.temperature,
            operatingPressure = c.pressure, vessel = d.vessel, inletLine = d.inletLine, outletLine = d.outletLine,
            // A blocked outlet relieves the whole feed at its own temperature unless the user gave the flow.
            scenarios = d.scenarios.map { s ->
                if (s.kind == ReliefScenarioKinds.BLOCKED_OUTLET) s.copy(massFlow = s.massFlow ?: c.massFlow, temperature = s.temperature ?: c.temperature) else s
            },
        )
    }

    fun depressuringProblem(fs: Flowsheet, results: ResultView, c: DepressuringCase): String? = when {
        c.protectedId == null || fs.objects.none { it.id == c.protectedId } -> "Choose the vessel"
        c.vessel == null -> "Give the vessel's dimensions"
        results.stale || contents(fs, results, c.protectedId) == null -> "Solve the flowsheet first: the vessel's contents come from the solve"
        else -> null
    }

    fun depressuringRequest(fs: Flowsheet, results: ResultView, c: DepressuringCase): DepressuringRequest {
        val contents = contents(fs, results, c.protectedId) ?: error("No contents")
        return DepressuringRequest(
            flowsheet = fs, composition = contents.composition, initialPressure = contents.pressure, initialTemperature = contents.temperature,
            vessel = c.vessel!!, backPressure = c.backPressure, orificeDiameter = c.orificeDiameter,
            fire = if (c.withFire) ReliefScenario(ReliefScenarioKinds.FIRE) else null, heatTransferCoefficient = c.heatTransferCoefficient,
        )
    }

    /** Pressure, temperatures (fluid and wall) and flow against time. */
    fun depressuringPlots(r: DepressuringResult): List<Plot> {
        val t = r.time
        if (t.size < 2) return emptyList()
        fun ok(y: List<Double>) = y.size == t.size
        return listOfNotNull(
            Plot("Pressure", "Time", Quantity.Time, "Pressure", Quantity.Pressure, listOf(PlotSeries("Vessel", t, r.pressure)),
                markers = listOfNotNull(r.timeToTarget?.let { PlotMarker("Target", it, r.targetPressure) })).takeIf { ok(r.pressure) },
            Plot("Temperature", "Time", Quantity.Time, "Temperature", Quantity.Temperature, listOfNotNull(
                PlotSeries("Fluid", t, r.temperature).takeIf { ok(r.temperature) },
                PlotSeries("Wall (mean)", t, r.wallTemperature, dashed = true).takeIf { ok(r.wallTemperature) },
            )).takeIf { ok(r.temperature) },
            Plot("Flow", "Time", Quantity.Time, "Mass flow out", Quantity.MassFlow, listOf(PlotSeries("Through the orifice", t, r.massFlow))).takeIf { ok(r.massFlow) },
        )
    }
}
