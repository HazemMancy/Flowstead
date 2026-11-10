/*
 * Flowstead web (D136): a pump's or compressor's performance curve and a pipe's sections and fittings, as the app's
 * CurveSection and PipeSectionsSection. The model, its checks and the fittings are the app's core (PerformanceCurve,
 * Specification.curveProblem, PipeFittings, FlowsheetEditing); values are shown and typed in the user's display units.
 * The operating point on the curve is the engine's result.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:OptIn(ExperimentalJsExport::class)

package app.flowstead.web

import app.flowstead.core.api.SolveResult
import app.flowstead.core.format.ValueFormat
import app.flowstead.core.model.CurveFits
import app.flowstead.core.model.CurvePoint
import app.flowstead.core.model.DisplayValue
import app.flowstead.core.model.EditRejected
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.FlowsheetEditing
import app.flowstead.core.model.FlowsheetJson
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.PerformanceCurve
import app.flowstead.core.model.PipeFittings
import app.flowstead.core.model.PipeSegmentSection
import app.flowstead.core.model.ResultView
import app.flowstead.core.model.SpecKey
import app.flowstead.core.model.CatalyticUnits
import app.flowstead.core.model.Reaction
import app.flowstead.core.model.ReactionPhase
import app.flowstead.core.model.ReactionType
import app.flowstead.core.model.ReactorKeys
import app.flowstead.core.model.Reactors
import app.flowstead.core.model.Specification
import app.flowstead.core.model.SpeedLine
import app.flowstead.core.model.StoredSolve
import app.flowstead.core.model.modelHash
import app.flowstead.core.model.obj
import app.flowstead.core.units.EngUnit
import app.flowstead.core.units.Quantity
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.Units
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

@JsExport
object EquipmentTools {
    private fun fs(json: String): Flowsheet = FlowsheetJson.decodeFromString(Flowsheet.serializer(), json)
    private fun out(f: Flowsheet) = buildJsonObject { put("flowsheet", FlowsheetJson.encodeToJsonElement(Flowsheet.serializer(), f)) }.toString()
    private fun err(message: String) = buildJsonObject { put("error", message) }.toString()
    private fun view(f: Flowsheet, solveJson: String?, solvedHash: String): ResultView {
        val s = solveJson?.takeIf { it.isNotBlank() && it != "null" }?.let { StoredSolve(FlowsheetJson.decodeFromString(SolveResult.serializer(), it), solvedHash, 0L) }
        return ResultView(s, f.modelHash())
    }
    private fun num(v: Double?, u: EngUnit?, ctx: UnitContext, decimals: Int = 1): String =
        v?.let { x -> u?.let { ValueFormat.number(it.fromSi(x, ctx), it.decimals + 1) } ?: ValueFormat.number(x, decimals) }?.replace(",", "") ?: ""
    /** A typed number in [u] (null: as typed) to SI; blank is null; anything else is refused. */
    private fun parse(text: String?, u: EngUnit?, ctx: UnitContext): Double? {
        val t = text?.trim()?.replace(',', '.').orEmpty()
        if (t.isEmpty()) return null
        val v = t.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw EditRejected("“$text” isn't a number")
        return u?.toSi(v, ctx) ?: v
    }
    private fun ctx(f: Flowsheet) = UnitContext(f.atmosphericPressurePa)
    private inline fun edit(block: () -> Flowsheet): String = try { out(block()) } catch (e: Exception) { err(e.message ?: "Not allowed") }

    // ---------- performance curves (D48) ----------

    /**
     * A pump's or compressor's curve for the worksheet: {kind: pump|compressor|null, on, problem?, units: {flow, head},
     * points: [{flow, head, eff, npshr}], fit, order, speed, operatingSpeed, solvesSpeed, solvedSpeed?, lines: [{speed,
     * points}], plot?}; numbers as text in the user's units. kind null: the object has no curve.
     */
    fun curve(flowsheetJson: String, id: String, solveJson: String?, solvedHash: String): String {
        val f = fs(flowsheetJson)
        val o = f.obj(id)
        if (o == null || (o.type != ObjectType.Pump && o.type != ObjectType.Compressor)) return buildJsonObject { put("kind", null as String?) }.toString()
        val c = ctx(f)
        val qu = displayUnits.unitFor(Quantity.VolumeFlow); val hu = displayUnits.unitFor(Quantity.Length)
        val pump = o.type == ObjectType.Pump
        val curve = o.curve
        val results = view(f, solveJson, solvedHash)
        fun points(list: List<CurvePoint>) = buildJsonArray {
            list.forEach { p -> addJsonObject { put("flow", num(p.flow, qu, c)); put("head", num(p.head, hu, c)); put("eff", num(p.efficiency?.times(100), null, c)); put("npshr", num(p.npshr, hu, c)) } }
        }
        return buildJsonObject {
            put("kind", if (pump) "pump" else "compressor"); put("on", curve != null)
            putJsonObject("units") { put("flow", qu.symbol); put("head", hu.symbol) }
            if (curve == null) return@buildJsonObject
            Specification.curveProblem(o)?.let { put("problem", it) }
            put("points", points(curve.points))
            put("fit", curve.fit ?: ""); put("order", curve.order?.toString() ?: "")
            put("speed", num(curve.speed, null, c, 0)); put("operatingSpeed", num(curve.operatingSpeed, null, c, 0))
            put("solvesSpeed", curve.solvesSpeed)
            (results.value(o.id, "operatingSpeed") as? DisplayValue.Calculated)?.takeIf { !results.stale }?.let { put("solvedSpeed", ValueFormat.number(it.si, 0)) }
            putJsonArray("lines") { curve.speedLines.forEach { l -> addJsonObject { put("speed", num(l.speed, null, c, 0)); put("points", points(l.points)) } } }
            // The curve with the engine's operating point on it, in the user's units.
            if (curve.points.size >= 2 && Specification.curveProblem(o) == null) {
                val q = (results.value(o.id, "inletVolumeFlow") as? DisplayValue.Calculated)?.takeIf { !results.stale }?.si
                val h = (results.value(o.id, if (pump) "head" else "adiabaticHead") as? DisplayValue.Calculated)?.takeIf { !results.stale }?.si
                putJsonObject("plot") {
                    put("kind", "plot"); put("title", if (pump) "Pump curve" else "Compressor curve")
                    put("xLabel", "Actual inlet flow, ${qu.symbol}"); put("yLabel", "${if (pump) "Head" else "Adiabatic head"}, ${hu.symbol}")
                    putJsonArray("series") {
                        fun line(label: String, pts: List<CurvePoint>) = addJsonObject {
                            put("label", label)
                            putJsonArray("points") { pts.sortedBy { it.flow }.forEach { p -> add(buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(qu.fromSi(p.flow, c))); add(kotlinx.serialization.json.JsonPrimitive(hu.fromSi(p.head, c))) }) } }
                        }
                        line(curve.speed?.let { "Head at ${ValueFormat.number(it, 0)} rpm" } ?: "Head", curve.points)
                        curve.speedLines.forEach { line("Head at ${ValueFormat.number(it.speed, 0)} rpm", it.points) }
                    }
                    putJsonArray("markers") {
                        if (q != null && h != null) addJsonObject { put("x", qu.fromSi(q, c)); put("y", hu.fromSi(h, c)); put("label", "Operating point (engine)") }
                    }
                }
            }
        }.toString()
    }

    /**
     * One change to the curve, as the app's editor makes it: [opJson] {op: on|off|point|addPoint|removePoint|field|
     * addLine|removeLine|lineSpeed, line (-1: the rated curve), index, field, value}. Returns {flowsheet} or {error}.
     */
    fun curveEdit(flowsheetJson: String, id: String, opJson: String): String = edit {
        val f = fs(flowsheetJson)
        val o = f.obj(id) ?: throw EditRejected("That machine no longer exists")
        val c = ctx(f)
        val op = FlowsheetJson.parseToJsonElement(opJson).jsonObject
        fun s(k: String) = op[k]?.jsonPrimitive?.contentOrNull
        val line = op["line"]?.jsonPrimitive?.intOrNull ?: -1
        val index = op["index"]?.jsonPrimitive?.intOrNull ?: 0
        val qu = displayUnits.unitFor(Quantity.VolumeFlow); val hu = displayUnits.unitFor(Quantity.Length)
        val curve = o.curve ?: PerformanceCurve()
        fun pointsOf(cv: PerformanceCurve) = if (line < 0) cv.points else cv.speedLines.getOrNull(line)?.points ?: throw EditRejected("No such curve")
        fun withPoints(cv: PerformanceCurve, pts: List<CurvePoint>) =
            if (line < 0) cv.copy(points = pts) else cv.copy(speedLines = cv.speedLines.toMutableList().also { it[line] = it[line].copy(points = pts) })
        val next: PerformanceCurve? = when (s("op")) {
            "on" -> PerformanceCurve()
            "off" -> null
            "point" -> {
                val pts = pointsOf(curve).toMutableList()
                val p = pts.getOrNull(index) ?: throw EditRejected("No such point")
                pts[index] = when (s("field")) {
                    "flow" -> p.copy(flow = parse(s("value"), qu, c) ?: 0.0)
                    "head" -> p.copy(head = parse(s("value"), hu, c) ?: 0.0)
                    "eff" -> p.copy(efficiency = parse(s("value"), null, c)?.div(100))
                    "npshr" -> p.copy(npshr = parse(s("value"), hu, c))
                    else -> p
                }
                withPoints(curve, pts)
            }
            // A new point after the last, so the list stays in flow order (the app's rule); the user types over it.
            "addPoint" -> {
                val pts = pointsOf(curve)
                val last = pts.maxByOrNull { it.flow }
                withPoints(curve, pts + (last?.let { CurvePoint(it.flow * 1.2, it.head * 0.9, it.efficiency, it.npshr?.times(1.2)) } ?: CurvePoint(0.01, 50.0)))
            }
            "removePoint" -> withPoints(curve, pointsOf(curve).filterIndexed { j, _ -> j != index })
            "field" -> when (s("field")) {
                "fit" -> if (s("value") == CurveFits.POLYNOMIAL) curve.copy(fit = CurveFits.POLYNOMIAL) else curve.copy(fit = null, order = null)
                "order" -> curve.copy(order = parse(s("value"), null, c)?.toInt())
                "speed" -> curve.copy(speed = parse(s("value"), null, c))
                "operatingSpeed" -> curve.copy(operatingSpeed = parse(s("value"), null, c))
                else -> curve
            }
            // As the app: the affinity-law copy of the rated curve at 80 % of the lowest speed; the user types the vendor's figures over it.
            "addLine" -> {
                if (curve.points.size < 2) throw EditRejected("Give the rated curve two points first")
                val rated = curve.speed ?: 3000.0
                val n = (curve.speedLines.map { it.speed } + rated).min() * 0.8
                val r = n / rated
                curve.copy(speed = rated, speedLines = curve.speedLines + SpeedLine(n, curve.points.map { CurvePoint(it.flow * r, it.head * r * r, it.efficiency, it.npshr?.times(r * r)) }))
            }
            "removeLine" -> curve.copy(speedLines = curve.speedLines.filterIndexed { j, _ -> j != line })
            "lineSpeed" -> curve.copy(speedLines = curve.speedLines.toMutableList().also { it[line] = it[line].copy(speed = parse(s("value"), null, c) ?: 0.0) })
            else -> curve
        }
        FlowsheetEditing.setCurve(f, id, next)
    }

    // ---------- pipe sections and fittings (D98) ----------

    private fun small() = if (displayUnits.unitFor(Quantity.Length) == Units.ft) Units.inch else Units.mm

    /**
     * A pipe's sections for the worksheet: {pipe, on, units: {length, small}, items: [{fitting?, name, length, rise, id,
     * increments, count}], fittings: [{id, label}], totals}.
     */
    fun sections(flowsheetJson: String, id: String): String {
        val f = fs(flowsheetJson)
        val o = f.obj(id)
        if (o == null || o.type != ObjectType.PipeSegment) return buildJsonObject { put("pipe", false) }.toString()
        val c = ctx(f)
        val lu = displayUnits.unitFor(Quantity.Length); val su = small()
        val list = o.pipeSections.orEmpty()
        return buildJsonObject {
            put("pipe", true); put("on", list.isNotEmpty())
            putJsonObject("units") { put("length", lu.symbol); put("small", su.symbol) }
            putJsonArray("items") {
                list.forEach { s ->
                    val k = PipeFittings.get(s.fitting)
                    addJsonObject {
                        k?.let { put("fitting", it.id) }
                        put("name", k?.let { "${it.name} · ${PipeFittings.describe(it)}" } ?: "Straight run")
                        put("length", num(s.length, lu, c)); put("rise", num(s.elevationChange, lu, c)); put("id", num(s.innerDiameter, su, c))
                        put("increments", s.increments?.toString() ?: ""); put("count", s.count.toString())
                    }
                }
            }
            putJsonArray("fittings") { PipeFittings.ALL.forEach { k -> addJsonObject { put("id", k.id); put("label", "${k.name} · ${PipeFittings.describe(k)}") } } }
            // Totals from the sections as typed, as the app shows them: straight length, rise, fittings as pipe, K.
            val d0 = o.specs[SpecKey.INNER_DIAMETER]?.si
            val run = list.filter { !it.isFitting }.sumOf { (it.length ?: 0.0) * it.count }
            val rise = list.filter { !it.isFitting }.sumOf { (it.elevationChange ?: 0.0) * it.count }
            val eq = list.mapNotNull { s -> PipeFittings.get(s.fitting)?.takeIf { it.lOverD }?.let { k -> (s.innerDiameter ?: d0)?.let { k.value * it * s.count } } }.sum()
            val kSum = list.mapNotNull { s -> PipeFittings.get(s.fitting)?.takeIf { !it.lOverD }?.let { it.value * s.count } }.sum()
            if (list.isNotEmpty()) put("totals",
                "Straight length ${num(run, lu, c)} ${lu.symbol}, rise ${num(rise, lu, c)} ${lu.symbol}" +
                    (if (eq > 0) "; fittings as ${num(eq, lu, c)} ${lu.symbol} more pipe" else "") +
                    (if (kSum > 0) "; K ${ValueFormat.number(kSum, 2)} velocity heads" else "") +
                    ". Each fitting's loss is DWSIM's (Crane L/D or K).")
        }.toString()
    }

    /** One change to the sections: {op: on|off|set|addRun|addFitting|remove, index, field, value}. {flowsheet} or {error}. */
    fun sectionsEdit(flowsheetJson: String, id: String, opJson: String): String = edit {
        val f = fs(flowsheetJson)
        val o = f.obj(id) ?: throw EditRejected("That pipe no longer exists")
        val c = ctx(f)
        val op = FlowsheetJson.parseToJsonElement(opJson).jsonObject
        fun s(k: String) = op[k]?.jsonPrimitive?.contentOrNull
        val index = op["index"]?.jsonPrimitive?.intOrNull ?: 0
        val lu = displayUnits.unitFor(Quantity.Length); val su = small()
        val list = o.pipeSections.orEmpty()
        val next: List<PipeSegmentSection>? = when (s("op")) {
            "on" -> listOf(PipeSegmentSection(length = o.specs[SpecKey.LENGTH]?.si, elevationChange = o.specs[SpecKey.ELEVATION_CHANGE]?.si))
            "off" -> null
            "set" -> list.toMutableList().also { l ->
                val x = l.getOrNull(index) ?: throw EditRejected("No such section")
                l[index] = when (s("field")) {
                    "length" -> x.copy(length = parse(s("value"), lu, c))
                    "rise" -> x.copy(elevationChange = parse(s("value"), lu, c))
                    "id" -> x.copy(innerDiameter = parse(s("value"), su, c))
                    "increments" -> x.copy(increments = parse(s("value"), null, c)?.toInt())
                    "count" -> x.copy(count = (parse(s("value"), null, c) ?: 1.0).toInt().coerceAtLeast(1))
                    else -> x
                }
            }
            "addRun" -> list + PipeSegmentSection(length = list.lastOrNull { !it.isFitting }?.length)
            "addFitting" -> list + PipeSegmentSection(fitting = PipeFittings.get(s("value"))?.id ?: throw EditRejected("Choose a fitting"))
            "remove" -> list.filterIndexed { j, _ -> j != index }
            else -> list
        }
        FlowsheetEditing.setPipeSections(f, id, next?.ifEmpty { null })
    }

    // ---------- reactors and their reactions (D136, the app's ReactorEditor and ReactionFields) ----------

    private fun plain(v: Double?): String = v?.let { x -> if (x == kotlin.math.floor(x) && kotlin.math.abs(x) < 1e15) x.toLong().toString() else x.toString() } ?: ""

    /**
     * The reactions a reactor can run: {reactor, gibbs, kinds: [{type, label}], compounds, items: [{id, name, type, equation,
     * used, problem?, stoich: {compound: text}, base, reactants, phase, phases: [{id, label}], conversion, approach,
     * forwardA, forwardE, orders: {compound: text}, reverseA, reverseE, num, den, conc, rate, concUnits, rateUnits, tmin,
     * tmax}]}. Numbers as typed (no unit conversion), as in the app.
     */
    fun reactions(flowsheetJson: String, id: String): String {
        val f = fs(flowsheetJson)
        val o = f.obj(id)
        if (o == null || !Reactors.isReactor(o.type)) return buildJsonObject { put("reactor", false) }.toString()
        val type = Reactors.reactionType(o.type)
        val kinds = when (type) { null -> emptyList(); ReactionType.Kinetic -> listOf(ReactionType.Kinetic, ReactionType.Catalytic); else -> listOf(type) }
        val used = Reactors.reactionIds(o)
        return buildJsonObject {
            put("reactor", true); put("gibbs", type == null)
            putJsonArray("kinds") { kinds.forEach { k -> addJsonObject { put("type", k.name); put("label", k.label) } } }
            putJsonArray("compounds") { f.compounds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            putJsonArray("items") {
                f.reactions.filter { it.type in kinds }.forEach { r ->
                    addJsonObject {
                        put("id", r.id); put("name", r.name); put("type", r.type.name); put("equation", r.equation()); put("used", r.id in used)
                        Reactors.problem(f, r)?.let { put("problem", it.replaceFirstChar(Char::uppercase)) }
                        putJsonObject("stoich") { f.compounds.forEach { c -> put(c, plain(r.stoichiometry[c])) } }
                        val reactants = r.stoichiometry.filterValues { it < 0 }.keys.toList()
                        put("base", r.baseCompound.takeIf { it in reactants } ?: "")
                        putJsonArray("reactants") { reactants.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                        put("phase", r.phase.name)
                        putJsonArray("phases") {
                            (if (r.type == ReactionType.Equilibrium) listOf(ReactionPhase.Vapour, ReactionPhase.Liquid) else ReactionPhase.entries)
                                .forEach { p -> addJsonObject { put("id", p.name); put("label", p.label) } }
                        }
                        put("conversion", plain(r.conversion?.times(100))); put("approach", plain(r.approachTemperature))
                        put("forwardA", plain(r.forwardA)); put("forwardE", plain(r.forwardE))
                        putJsonObject("orders") { reactants.forEach { c -> put(c, plain(r.forwardOrders[c])) } }
                        put("reverseA", plain(r.reverseA)); put("reverseE", plain(r.reverseE))
                        put("num", r.rateNumerator ?: ""); put("den", r.rateDenominator ?: "")
                        put("conc", r.concentrationUnit ?: CatalyticUnits.CONCENTRATIONS.first()); put("rate", r.rateUnit ?: CatalyticUnits.RATES.first())
                        putJsonArray("concUnits") { CatalyticUnits.CONCENTRATIONS.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                        putJsonArray("rateUnits") { CatalyticUnits.RATES.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                        put("tmin", plain(r.minTemperature)); put("tmax", plain(r.maxTemperature))
                        // The symbols a catalytic rate may use, in stoichiometry order (DWSIM's R1…, P1…).
                        val order = r.stoichiometry.entries.toList()
                        put("symbols", (order.filter { it.value < 0 }.mapIndexed { i, e -> "R${i + 1} = ${e.key}" } + order.filter { it.value > 0 }.mapIndexed { i, e -> "P${i + 1} = ${e.key}" }).joinToString(", "))
                    }
                }
            }
        }.toString()
    }

    /**
     * One change to a reactor's reactions: {op: use|new|delete|set, reaction, on, kind, field, compound, value}.
     * Returns {flowsheet, reaction?} or {error}.
     */
    fun reactionEdit(flowsheetJson: String, reactorId: String, opJson: String): String = try {
        val f = fs(flowsheetJson)
        val o = f.obj(reactorId) ?: throw EditRejected("That reactor no longer exists")
        val op = FlowsheetJson.parseToJsonElement(opJson).jsonObject
        fun s(k: String) = op[k]?.jsonPrimitive?.contentOrNull
        val used = Reactors.reactionIds(o)
        fun setUsed(fl: Flowsheet, ids: List<String>) = FlowsheetEditing.setRef(fl, reactorId, ReactorKeys.REACTIONS, ids.joinToString(",").ifEmpty { null })
        fun num(): Double? = parse(s("value"), null, ctx(f))
        var created: String? = null
        val next = when (s("op")) {
            "use" -> setUsed(f, if (s("on") == "true") (used + s("reaction")!!).distinct() else used - s("reaction")!!)
            "new" -> {
                val kind = ReactionType.valueOf(s("kind") ?: throw EditRejected("Choose a kind"))
                val id = FlowsheetEditing.newId()
                val n = generateSequence(1) { it + 1 }.first { k -> f.reactions.none { it.name == "Rxn-$k" } }
                created = id
                setUsed(FlowsheetEditing.upsertReaction(f, Reaction(id, "Rxn-$n", kind, phase = if (kind == ReactionType.Equilibrium) ReactionPhase.Vapour else ReactionPhase.Mixture)), used + id)
            }
            "delete" -> setUsed(FlowsheetEditing.deleteReaction(f, s("reaction")!!), used - s("reaction")!!)
            "set" -> {
                val r = f.reaction(s("reaction") ?: "") ?: throw EditRejected("That reaction no longer exists")
                val c = s("compound")
                val u = when (s("field")) {
                    "name" -> s("value")?.trim()?.takeIf { it.isNotEmpty() }?.let { r.copy(name = it) } ?: throw EditRejected("Give the reaction a name")
                    "stoich" -> num().let { v -> r.copy(stoichiometry = if (v == null || v == 0.0) r.stoichiometry - c!! else r.stoichiometry + (c!! to v)) }
                    "base" -> r.copy(baseCompound = s("value") ?: "")
                    "phase" -> r.copy(phase = ReactionPhase.valueOf(s("value")!!))
                    "conversion" -> r.copy(conversion = num()?.div(100))
                    "approach" -> r.copy(approachTemperature = num())
                    "forwardA" -> r.copy(forwardA = num()); "forwardE" -> r.copy(forwardE = num())
                    "order" -> num().let { v -> r.copy(forwardOrders = if (v == null) r.forwardOrders - c!! else r.forwardOrders + (c!! to v)) }
                    "reverseA" -> r.copy(reverseA = num()); "reverseE" -> r.copy(reverseE = num())
                    "num" -> r.copy(rateNumerator = s("value")?.trim()?.ifBlank { null })
                    "den" -> r.copy(rateDenominator = s("value")?.trim()?.ifBlank { null })
                    "conc" -> r.copy(concentrationUnit = s("value")); "rate" -> r.copy(rateUnit = s("value"))
                    "tmin" -> r.copy(minTemperature = num()); "tmax" -> r.copy(maxTemperature = num())
                    else -> r
                }
                FlowsheetEditing.upsertReaction(f, u)
            }
            else -> f
        }
        buildJsonObject { put("flowsheet", FlowsheetJson.encodeToJsonElement(Flowsheet.serializer(), next)); created?.let { put("reaction", it) } }.toString()
    } catch (e: Exception) { err(e.message ?: "Not allowed") }
}
