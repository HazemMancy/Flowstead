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
import app.flowstead.core.api.PressurizationSupply
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
    val pressurization: List<PressurizationCase> = emptyList(),
)

/** One length of pipe of a pipeline network typed by hand (m). */
@Serializable
data class PipeSection(val insideDiameter: Double, val length: Double, val wallThickness: Double? = null)

/**
 * Pipeline pressurization (HYSYS's template): the pipes it fills (pipe segments of the flowsheet and typed sections, as
 * one network at one pressure), the supply stream (its solved state and composition), the starting pressure and
 * temperature, and how the gas gets in: [valve] "orifice", "cv" or "size" (an orifice sized to [targetTime]).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PressurizationCase(
    val id: String,
    val name: String,
    val supplyId: String? = null,
    val pipeIds: List<String> = emptyList(),
    val sections: List<PipeSection> = emptyList(),
    val initialPressure: Double = 101325.0,
    /** Null: the supply's temperature. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val initialTemperature: Double? = null,
    val valve: String = PressurizationValves.SIZE,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val cv: Double? = null,
    val opening: Double = 1.0,
    val valveXt: Double = 0.72,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val orificeDiameter: Double? = null,
    /** Null: 95 % of the way to the supply pressure (a header); to where the two sides equalise (a fixed volume). */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val targetPressure: Double? = null,
    val targetTime: Double = 3600.0,
    val duration: Double = 4 * 3600.0,
    /**
     * Where the gas comes from (D149): [PressurizationSources.HEADER], a supply at constant pressure (the stream
     * [supplyId]); or [PressurizationSources.VESSEL], a fixed volume, the vessel, tank or pipe [sourceId] (its size
     * [sourceVessel] for a vessel, a pipe's own), its contents from the solve, emptying until the pressures equalise.
     */
    val source: String = PressurizationSources.HEADER,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val sourceId: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val sourceVessel: VesselGeometry? = null,
    /** The source's starting pressure, Pa abs; null: its pressure in the solve. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val sourcePressure: Double? = null,
    /** No heat from the source's wall: the coldest source (wall heat is the default). */
    val sourceAdiabatic: Boolean = false,
    /** A vessel or tank filled instead of pipes (D149): the object [fillId] (its size [fillVessel]); null: the pipes and sections. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val fillId: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val fillVessel: VesselGeometry? = null,
    /** [PressurizationValves.FLOWSHEET]: the control valve (its rated or required Cv, its xT) or orifice plate (its bore) on the flowsheet. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val valveId: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val result: DepressuringResult? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val resultHash: String? = null,
)

object PressurizationValves {
    const val SIZE = "size"
    const val ORIFICE = "orifice"
    const val CV = "cv"
    /** A control valve or restriction orifice already on the flowsheet (D149). */
    const val FLOWSHEET = "flowsheet"
}

object PressurizationSources {
    const val HEADER = "header"
    const val VESSEL = "vessel"
}

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
    /**
     * The vessel's design pressure, Pa abs (D142). API 521 §5.20.1 aims a blowdown at the lower of 6.9 barg and half of
     * it; null: half the starting gauge pressure, with a warning. It also sets the ASME wall thickness, unless given.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val designPressure: Double? = null,
    val withFire: Boolean = false,
    /** Null: Flowstead's correlations (natural convection, nucleate boiling); 0: adiabatic. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val heatTransferCoefficient: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val ambientTemperature: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val result: DepressuringResult? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val resultHash: String? = null,
)

object Safety {
    /** What a relief valve or depressuring case can protect. */
    val PROTECTABLE = setOf(ObjectType.Separator, ObjectType.ThreePhaseSeparator, ObjectType.Tank, ObjectType.PipeSegment)

    /** A pipe segment's geometry: its inside diameter and length (no heads), and its wall thickness when its outside diameter is set. */
    fun pipeGeometry(o: SimObject): Pair<VesselGeometry, Double?>? {
        if (o.type != ObjectType.PipeSegment) return null
        val id = o.specs[SpecKey.INNER_DIAMETER]?.si ?: return null
        val od = o.specs[SpecKey.OUTER_DIAMETER]?.si
        // Sections (D98): the straight runs, each at its own size, as one pipe of the same volume and surface.
        o.pipeSections?.filter { !it.isFitting }?.takeIf { it.isNotEmpty() }?.let { runs ->
            return network(runs.map { r ->
                val d = r.innerDiameter ?: id
                val wall = (r.outerDiameter ?: od.takeIf { r.innerDiameter == null })?.let { (it - d) / 2 }?.takeIf { it > 0 }
                PipeSection(d, (r.length ?: 0.0) * r.count, wall)
            })
        }
        val length = o.specs[SpecKey.LENGTH]?.si ?: return null
        val wall = od?.let { (it - id) / 2 }?.takeIf { it > 0 }
        return VesselGeometry(id, length, pipe = true) to wall
    }

    /** What is protected or blown down: a pipe segment's own geometry, else the vessel dimensions the user gave. */
    fun geometry(fs: Flowsheet, protectedId: String?, given: VesselGeometry?): VesselGeometry? =
        fs.objects.firstOrNull { it.id == protectedId }?.let { pipeGeometry(it)?.first } ?: given

    /**
     * A pipeline network as one equivalent pipe with the same volume and inside surface (D = 4V/S, L = S/(πD)), and the
     * surface-weighted wall thickness (null when a section has none).
     */
    fun network(sections: List<PipeSection>): Pair<VesselGeometry, Double?>? {
        val ok = sections.filter { it.insideDiameter > 0 && it.length > 0 }
        if (ok.isEmpty()) return null
        val volume = ok.sumOf { Math.PI * it.insideDiameter * it.insideDiameter / 4 * it.length }
        val surface = ok.sumOf { Math.PI * it.insideDiameter * it.length }
        val d = 4 * volume / surface
        val wall = if (ok.all { it.wallThickness != null }) ok.sumOf { it.wallThickness!! * Math.PI * it.insideDiameter * it.length } / surface else null
        return VesselGeometry(d, surface / (Math.PI * d), pipe = true) to wall
    }

    /** The sections a pressurization case fills: its pipe segments (by their size) and its typed sections. */
    fun sections(fs: Flowsheet, c: PressurizationCase): List<PipeSection> =
        c.pipeIds.mapNotNull { id -> fs.objects.firstOrNull { it.id == id }?.let(::pipeGeometry) }
            .map { (g, wall) -> PipeSection(g.diameter, g.length, wall) } + c.sections

    /** A stream's own solved state and composition (the supply of a pressurization). */
    fun streamState(fs: Flowsheet, results: ResultView, id: String?): Contents? {
        val s = id?.let { i -> fs.objects.firstOrNull { it.id == i && it.type == ObjectType.MaterialStream } } ?: return null
        val t = (results.value(s.id, "temperature") as? DisplayValue.Calculated)?.si ?: return null
        val p = (results.value(s.id, "pressure") as? DisplayValue.Calculated)?.si ?: return null
        val x = fs.compounds.associateWith { c -> (results.composition(s.id, Phases.OVERALL, c) as? DisplayValue.Calculated)?.si ?: 0.0 }.filterValues { it > 0 }
        return if (x.isEmpty()) null else Contents(x, t, p, null)
    }

    /** A vessel's or pipe's volume, m³: the shell and, for a vessel, two 2:1 heads (the engine's ReliefMath.Volume). */
    fun volume(g: VesselGeometry): Double = kotlin.math.PI * g.diameter * g.diameter / 4 * g.length + (if (g.pipe) 0.0 else 2 * kotlin.math.PI * g.diameter * g.diameter * g.diameter / 24)

    /** A vessel, tank or pipe's volume and wall: a pipe its own (its size), a vessel the dimensions given. */
    private fun volumeOf(fs: Flowsheet, id: String?, given: VesselGeometry?): Pair<VesselGeometry, Double?>? {
        val o = fs.objects.firstOrNull { it.id == id } ?: return null
        return pipeGeometry(o) ?: given?.let { it to null }
    }

    /** What is filled: a vessel or tank when [PressurizationCase.fillId] is set, else the pipes and sections as one network. */
    fun filled(fs: Flowsheet, c: PressurizationCase): Pair<VesselGeometry, Double?>? =
        if (c.fillId != null) volumeOf(fs, c.fillId, c.fillVessel) else network(sections(fs, c))

    /** The source's state: a header's stream, or a fixed volume's contents from the solve (its pressure the one given, if any). */
    fun sourceState(fs: Flowsheet, results: ResultView, c: PressurizationCase): Contents? =
        if (c.source == PressurizationSources.VESSEL) contents(fs, results, c.sourceId)?.let { it.copy(pressure = c.sourcePressure ?: it.pressure) }
        else streamState(fs, results, c.supplyId)

    /** A control valve's Cv (its rated one, else the one the solve required) and xT, or an orifice plate's bore, on the flowsheet (D149). */
    data class FlowsheetValve(val tag: String, val cv: Double? = null, val xt: Double = 0.72, val orificeDiameter: Double? = null, val ratedCv: Boolean = false)

    fun flowsheetValve(fs: Flowsheet, results: ResultView, id: String?): FlowsheetValve? {
        val o = fs.objects.firstOrNull { it.id == id } ?: return null
        return when (o.type) {
            ObjectType.Valve -> {
                val rated = o.specs[SpecKey.RATED_CV]?.si?.takeIf { it > 0 }
                val required = (results.value(o.id, "requiredCv") as? DisplayValue.Calculated)?.si?.takeIf { it > 0 && !results.stale }
                FlowsheetValve(o.tag, cv = rated ?: required ?: return null, xt = o.specs[SpecKey.VALVE_XT]?.si ?: 0.72, ratedCv = rated != null)
            }
            ObjectType.OrificePlate -> FlowsheetValve(o.tag, orificeDiameter = o.specs[SpecKey.ORIFICE_DIAMETER]?.si?.takeIf { it > 0 } ?: return null)
            else -> null
        }
    }

    /** What may be a pressurization's valve on the flowsheet: its control valves and orifice plates. */
    fun valveChoices(fs: Flowsheet): List<SimObject> = fs.objects.filter { it.type == ObjectType.Valve || it.type == ObjectType.OrificePlate }

    fun pressurizationProblem(fs: Flowsheet, results: ResultView, c: PressurizationCase): String? {
        val vessel = c.source == PressurizationSources.VESSEL
        val supply = sourceState(fs, results, c)
        val fv = flowsheetValve(fs, results, c.valveId)
        return when {
            !vessel && c.supplyId == null -> "Choose the supply stream"
            vessel && (c.sourceId == null || fs.objects.none { it.id == c.sourceId && it.type in PROTECTABLE }) -> "Choose the source vessel, tank or pipe"
            vessel && volumeOf(fs, c.sourceId, c.sourceVessel) == null -> "Give the source vessel's dimensions (a pipe needs its inside diameter and length)"
            vessel && (c.sourceId in c.pipeIds || c.sourceId == c.fillId) -> "The source cannot be what it fills"
            c.fillId != null && filled(fs, c) == null -> "Give the dimensions of the vessel filled"
            c.fillId == null && sections(fs, c).isEmpty() -> "Choose the pipe segments to fill or add pipe sections (inside diameter and length), or a vessel"
            results.stale || supply == null -> "Solve the flowsheet first: the ${if (vessel) "source's contents come" else "supply's state comes"} from the solve"
            supply.pressure <= c.initialPressure -> "The ${if (vessel) "source" else "supply"} must be above the starting pressure of what it fills"
            c.valve == PressurizationValves.CV && !((c.cv ?: 0.0) > 0) -> "Give the control valve's Cv"
            c.valve == PressurizationValves.ORIFICE && !((c.orificeDiameter ?: 0.0) > 0) -> "Give the orifice diameter"
            c.valve == PressurizationValves.FLOWSHEET && c.valveId == null -> "Choose the control valve or restriction orifice on the flowsheet"
            c.valve == PressurizationValves.FLOWSHEET && fv == null ->
                "${fs.objects.firstOrNull { it.id == c.valveId }?.tag ?: "That valve"} has no ${if (fs.objects.firstOrNull { it.id == c.valveId }?.type == ObjectType.OrificePlate) "orifice diameter" else "Cv: give its rated Cv, or solve the flowsheet for the Cv it needs"}"
            vessel && c.valve == PressurizationValves.SIZE && c.targetPressure == null -> "Give the target pressure to size the orifice (or choose a valve or orifice)"
            c.targetPressure != null && !(c.targetPressure > c.initialPressure && c.targetPressure < supply.pressure) ->
                "The target must lie between the starting pressure and the ${if (vessel) "source's" else "supply's"}"
            else -> null
        }
    }

    fun pressurizationRequest(fs: Flowsheet, results: ResultView, c: PressurizationCase): DepressuringRequest {
        val vessel = c.source == PressurizationSources.VESSEL
        val supply = sourceState(fs, results, c) ?: error("No supply")
        val (geometry, wall) = filled(fs, c) ?: error("Nothing to fill")
        val fv = if (c.valve == PressurizationValves.FLOWSHEET) flowsheetValve(fs, results, c.valveId) else null
        val source = if (vessel) volumeOf(fs, c.sourceId, c.sourceVessel) else null
        return DepressuringRequest(
            flowsheet = SubflowsheetSolve.forObject(fs, if (vessel) c.sourceId else c.supplyId), composition = supply.composition, initialPressure = c.initialPressure,
            initialTemperature = c.initialTemperature ?: supply.temperature, vessel = geometry, wallThickness = wall,
            targetPressure = c.targetPressure, targetTime = c.targetTime, duration = c.duration,
            supply = PressurizationSupply(
                supply.composition, supply.pressure, supply.temperature,
                cv = fv?.cv ?: c.cv.takeIf { c.valve == PressurizationValves.CV },
                // A required Cv from the solve is the valve as it runs: fully its Cv. A rated one takes the opening.
                opening = if (fv != null && fv.cv != null && !fv.ratedCv) 1.0 else c.opening,
                valveXt = fv?.xt ?: c.valveXt,
                orificeDiameter = fv?.orificeDiameter ?: c.orificeDiameter.takeIf { c.valve == PressurizationValves.ORIFICE },
                vessel = source?.first, wallThickness = source?.second, heatTransferCoefficient = if (vessel && c.sourceAdiabatic) 0.0 else null,
            ),
        )
    }

    /** The vessel's contents in normal operation: its first inlet stream's solved state and composition. */
    data class Contents(val composition: Map<String, Double>, val temperature: Double, val pressure: Double, val massFlow: Double?)

    /**
     * With one feed, its state and composition. With several, the vessel holds their mixture: the molar-flow-weighted
     * composition, every feed's mass flow (a blocked outlet relieves them all, API 521 §4.4.2), and the vessel's own
     * temperature and pressure, read from its first outlet stream (the engine's flash of the mixed feed).
     */
    fun contents(fs: Flowsheet, results: ResultView, protectedId: String?): Contents? {
        val id = protectedId ?: return null
        fun calc(obj: String, key: String) = (results.value(obj, key) as? DisplayValue.Calculated)?.si
        fun isStream(s: String) = fs.objects.any { it.id == s && it.type == ObjectType.MaterialStream }
        fun composition(s: String) = fs.compounds.associateWith { c -> (results.composition(s, Phases.OVERALL, c) as? DisplayValue.Calculated)?.si ?: 0.0 }
        val feeds = fs.connections.filter { it.toObject == id }.map { it.fromObject }.filter(::isStream).distinct()
            .ifEmpty { listOfNotNull(id.takeIf(::isStream)) }
        if (feeds.isEmpty()) return null
        if (feeds.size == 1) {
            val feed = feeds.single()
            val t = calc(feed, "temperature") ?: return null
            val p = calc(feed, "pressure") ?: return null
            val x = composition(feed).filterValues { it > 0 }
            if (x.isEmpty()) return null
            return Contents(x, t, p, calc(feed, "massFlow"))
        }
        val flows = feeds.map { calc(it, "molarFlow") ?: return null }
        val total = flows.sum()
        if (!(total > 0)) return null
        val x = fs.compounds.associateWith { c -> feeds.indices.sumOf { i -> flows[i] * (composition(feeds[i])[c] ?: 0.0) } / total }.filterValues { it > 0 }
        if (x.isEmpty()) return null
        val outlet = fs.connections.filter { it.fromObject == id }.map { it.toObject }.firstOrNull(::isStream)
        val state = outlet?.takeIf { calc(it, "temperature") != null && calc(it, "pressure") != null } ?: feeds.first()
        val t = calc(state, "temperature") ?: return null
        val p = calc(state, "pressure") ?: return null
        val w = feeds.map { calc(it, "massFlow") }.takeIf { m -> m.all { it != null } }?.sumOf { it!! }
        return Contents(x, t, p, w)
    }

    /** A relief scenario the flowsheet suggests for a vessel, with why (HYSYS-like scenario screening, API 521 §4.4). */
    data class Suggestion(val scenario: ReliefScenario, val reason: String)

    /**
     * The credible overpressure causes the flowsheet shows for the protected vessel: fire (always, for a vessel in a
     * fire zone), its blocked outlet (the whole feed), a control valve failing wide open for each valve feeding it
     * (its upstream state from the solve, its rated Cv if given), and thermal expansion when it holds only liquid.
     */
    fun suggest(fs: Flowsheet, results: ResultView, protectedId: String?): List<Suggestion> {
        val id = protectedId ?: return emptyList()
        val vessel = fs.objects.firstOrNull { it.id == id } ?: return emptyList()
        fun calc(obj: String, key: String) = (results.value(obj, key) as? DisplayValue.Calculated)?.si
        val out = mutableListOf(
            Suggestion(ReliefScenario(ReliefScenarioKinds.FIRE, "Fire"), "Any vessel in a fire zone; the wetted area comes from the vessel's dimensions"),
        )
        if (fs.connections.any { it.fromObject == id }) {
            out += Suggestion(ReliefScenario(ReliefScenarioKinds.BLOCKED_OUTLET, "Blocked outlet"), "An outlet of ${vessel.tag} closed: its whole feed relieves")
        }
        val feeds = fs.connections.filter { it.toObject == id }.map { it.fromObject }
        for (stream in feeds) {
            val valve = fs.connections.firstOrNull { it.toObject == stream }?.let { c -> fs.objects.firstOrNull { it.id == c.fromObject && it.type == ObjectType.Valve } } ?: continue
            val inlet = fs.connections.firstOrNull { it.toObject == valve.id }?.fromObject ?: continue
            val x = fs.compounds.associateWith { c -> (results.composition(inlet, Phases.OVERALL, c) as? DisplayValue.Calculated)?.si ?: 0.0 }.filterValues { it > 0 }
            val cv = valve.specs[SpecKey.RATED_CV]?.si
            out += Suggestion(
                ReliefScenario(
                    ReliefScenarioKinds.CONTROL_VALVE_FAILURE, "${valve.tag} fails open",
                    composition = x.takeIf { it.isNotEmpty() }, temperature = calc(inlet, "temperature"),
                    upstreamPressure = calc(inlet, "pressure"), ratedCv = cv, valveXt = valve.specs[SpecKey.VALVE_XT]?.si,
                ),
                "${valve.tag} feeds ${vessel.tag} from the higher-pressure side" + if (cv == null) "; give its rated Cv" else "",
            )
        }
        val feedVf = feeds.firstNotNullOfOrNull { calc(it, "vapourFraction") }
        if (feedVf != null && feedVf <= 0.0) {
            out += Suggestion(ReliefScenario(ReliefScenarioKinds.THERMAL_EXPANSION, "Thermal expansion"), "${vessel.tag} holds only liquid: if blocked in, heat expands it; give the heat input")
        }
        return out
    }

    /** The suggestions not already among the valve's scenarios (same kind and name). */
    fun newSuggestions(fs: Flowsheet, results: ResultView, d: ReliefDeviceDef): List<Suggestion> =
        suggest(fs, results, d.protectedId).filter { s -> d.scenarios.none { it.kind == s.scenario.kind && it.name == s.scenario.name } }

    /** What stops the valve from being sized, or null. */
    fun problem(fs: Flowsheet, results: ResultView, d: ReliefDeviceDef): String? = when {
        d.protectedId == null || fs.objects.none { it.id == d.protectedId } -> "Choose the equipment it protects"
        d.setPressure == null -> "Give the set pressure"
        d.scenarios.isEmpty() -> "Add at least one relief scenario"
        results.stale || contents(fs, results, d.protectedId) == null -> "Solve the flowsheet first: the fluid and its state come from the solve"
        d.scenarios.any { it.kind == ReliefScenarioKinds.FIRE } && geometry(fs, d.protectedId, d.vessel) == null && d.scenarios.none { it.area != null } ->
            "Give the vessel's dimensions for the fire case"
        else -> null
    }

    /** The engine request for one valve: the protected equipment's contents with the user's valve and scenarios. */
    fun request(fs: Flowsheet, results: ResultView, d: ReliefDeviceDef): ReliefDevice {
        val c = contents(fs, results, d.protectedId) ?: error("No contents")
        return ReliefDevice(
            id = d.id, tag = d.tag, setPressure = d.setPressure!!, backPressure = d.backPressure, design = d.design,
            kb = d.kb, ruptureDisk = d.ruptureDisk, composition = c.composition, operatingTemperature = c.temperature,
            operatingPressure = c.pressure, vessel = geometry(fs, d.protectedId, d.vessel), inletLine = d.inletLine, outletLine = d.outletLine,
            // A blocked outlet relieves the whole feed at its own temperature unless the user gave the flow.
            scenarios = d.scenarios.map { s ->
                if (s.kind == ReliefScenarioKinds.BLOCKED_OUTLET) s.copy(massFlow = s.massFlow ?: c.massFlow, temperature = s.temperature ?: c.temperature) else s
            },
        )
    }

    fun depressuringProblem(fs: Flowsheet, results: ResultView, c: DepressuringCase): String? = when {
        c.protectedId == null || fs.objects.none { it.id == c.protectedId } -> "Choose the vessel or pipe"
        geometry(fs, c.protectedId, c.vessel) == null -> "Give the vessel's dimensions (a pipe needs its inside diameter and length)"
        results.stale || contents(fs, results, c.protectedId) == null -> "Solve the flowsheet first: the vessel's contents come from the solve"
        c.designPressure != null && c.designPressure <= c.backPressure -> "The design pressure must be above the back pressure"
        else -> null
    }

    /** Why the target is what it is, for the result (the engine's basis in words; API 521 §5.20.1). */
    fun targetBasisText(basis: String?): String? = when (basis) {
        "given" -> "as given"
        "6.9 barg" -> "6.9 barg, the lower of the two (API 521)"
        "half the design pressure" -> "half the design gauge pressure, the lower of the two (API 521)"
        "half the starting pressure" -> "half the starting gauge pressure: give the design pressure for API 521's basis"
        null -> null
        else -> basis
    }

    fun depressuringRequest(fs: Flowsheet, results: ResultView, c: DepressuringCase): DepressuringRequest {
        val contents = contents(fs, results, c.protectedId) ?: error("No contents")
        return DepressuringRequest(
            flowsheet = SubflowsheetSolve.forObject(fs, c.protectedId), composition = contents.composition, initialPressure = contents.pressure, initialTemperature = contents.temperature,
            vessel = geometry(fs, c.protectedId, c.vessel)!!, backPressure = c.backPressure, orificeDiameter = c.orificeDiameter, designPressure = c.designPressure,
            wallThickness = fs.objects.firstOrNull { it.id == c.protectedId }?.let { pipeGeometry(it)?.second },
            fire = if (c.withFire) ReliefScenario(ReliefScenarioKinds.FIRE) else null, heatTransferCoefficient = c.heatTransferCoefficient,
            ambientTemperature = c.ambientTemperature,
        )
    }

    /** Pressure, temperatures (vapour, liquid and both wall surfaces) and flow against time. */
    fun depressuringPlots(r: DepressuringResult, filling: Boolean = false): List<Plot> {
        val t = r.time
        if (t.size < 2) return emptyList()
        fun ok(y: List<Double>) = y.size == t.size
        return listOfNotNull(
            Plot("Pressure", "Time", Quantity.Time, "Pressure", Quantity.Pressure, listOfNotNull(
                PlotSeries(if (filling) "Filled" else "Vessel", t, r.pressure),
                r.sourcePressure?.takeIf { ok(it) }?.let { PlotSeries("Source", t, it) },
            ), markers = listOfNotNull(
                r.timeToTarget?.let { PlotMarker("Target", it, r.targetPressure) }.takeIf { r.equalisingPressure == null || r.timeToTarget != r.timeToEqualise },
                r.timeToEqualise?.let { te -> r.equalisingPressure?.let { PlotMarker("Equalised", te, it) } },
            )).takeIf { ok(r.pressure) },
            Plot("Temperature", "Time", Quantity.Time, "Temperature", Quantity.Temperature, listOfNotNull(
                PlotSeries(if (r.sourceTemperature != null) "Filled, gas" else "Vapour", t, r.temperature).takeIf { ok(r.temperature) },
                r.sourceTemperature?.takeIf { ok(it) }?.let { PlotSeries("Source, gas", t, it) },
                partial("Liquid", t, r.liquidTemperature, dashed = false),
                PlotSeries("Dry wall", t, r.wallTemperature, dashed = true).takeIf { ok(r.wallTemperature) },
                partial("Wetted wall", t, r.wettedWallTemperature, dashed = true),
            )).takeIf { ok(r.temperature) },
            Plot("Liquid", "Time", Quantity.Time, "Liquid volume fraction", Quantity.Fraction,
                listOf(PlotSeries("Liquid holdup", t, r.liquidVolumeFraction))).takeIf { ok(r.liquidVolumeFraction) && r.liquidVolumeFraction.any { it > 0 } },
            Plot("Flow", "Time", Quantity.Time, if (filling) "Mass flow in" else "Mass flow out", Quantity.MassFlow,
                listOf(PlotSeries(if (filling) "Through the valve" else "Through the orifice", t, r.massFlow))).takeIf { ok(r.massFlow) },
        )
    }

    /** A series over the times where it has values (the liquid zone can empty), or null if it never does. */
    private fun partial(label: String, t: List<Double>, y: List<Double?>, dashed: Boolean): PlotSeries? {
        if (y.size != t.size) return null
        val keep = t.indices.filter { y[it] != null }
        if (keep.size < 2) return null
        return PlotSeries(label, keep.map { t[it] }, keep.map { y[it]!! }, dashed = dashed)
    }
}
