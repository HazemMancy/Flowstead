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
import app.flowstead.core.model.CurvePaths
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

    // ---------- sizing (D47, D61, D62, D65, D106; on the web from D141): the app's sizing sections ----------

    private fun pct(v: Double?) = v?.let { ValueFormat.number(it * 100, 1).replace(",", "") } ?: ""

    /**
     * The sizing the app shows for [id]: {applies, kind: separator|equipment|line, report: {sections} (or why there is
     * none), fields: [{key, label, kind: number|choice|bool, value, unit, options?, hint?}]} with every number in the
     * user's display units. The numbers are Flowstead's sizing on the engine's results (stale results are refused).
     */
    fun sizing(flowsheetJson: String, id: String, solveJson: String?, solvedHash: String): String {
        val f = fs(flowsheetJson)
        val o = f.obj(id) ?: return "{\"applies\":false}"
        val c = ctx(f)
        val rv = view(f, solveJson, solvedHash)
        val rep = Report(c)
        fun withU(si: Double, q: Quantity) = rep.withUnit(si, q)
        fun field(key: String, label: String, v: Double?, q: Quantity?, hint: String? = null) = buildJsonObject {
            put("key", key); put("label", label); put("kind", "number")
            val u = q?.let { displayUnits.unitFor(it) }
            put("value", num(v, u, c)); put("unit", u?.symbol ?: ""); hint?.let { put("hint", it) }
        }
        fun pctField(key: String, label: String, v: Double?, hint: String? = null) = buildJsonObject {
            put("key", key); put("label", label); put("kind", "number"); put("value", pct(v)); put("unit", "%"); hint?.let { put("hint", it) }
        }
        fun choice(key: String, label: String, value: String?, options: List<Pair<String, String>>) = buildJsonObject {
            put("key", key); put("label", label); put("kind", "choice"); put("value", value ?: "")
            putJsonArray("options") { options.forEach { (i, l) -> addJsonObject { put("id", i); put("label", l) } } }
        }
        val fields = mutableListOf<kotlinx.serialization.json.JsonObject>()
        val kind: String
        var why: String? = null
        when (o.type) {
            ObjectType.Separator, ObjectType.ThreePhaseSeparator -> {
                kind = "separator"
                val i = o.sizing ?: app.flowstead.core.model.SeparatorSizingInput()
                fields += buildJsonObject { put("key", "horizontal"); put("label", "Horizontal vessel"); put("kind", "bool"); put("value", i.horizontal) }
                fields += choice("mistEliminator", "Mist eliminator", i.mistEliminator.name, app.flowstead.core.model.MistEliminator.entries.map { it.name to it.label })
                fields += choice("inletDevice", "Inlet device", i.inletDevice.name, app.flowstead.core.model.InletDevice.entries.map { it.name to it.label })
                val (loads, w) = app.flowstead.core.model.SeparatorSizing.loads(f, rv, o)
                fields += field("k", "Souders–Brown K", i.k, Quantity.Velocity, loads?.let { "library: " + withU(app.flowstead.core.model.SeparatorSizing.k(i.copy(k = null), it.pressure), Quantity.Velocity) } ?: "library")
                fields += field("holdupTime", "Hold-up time (NLL to LLL)", i.holdupTime, Quantity.Time)
                fields += field("surgeTime", "Surge time (HLL to NLL)", i.surgeTime, Quantity.Time)
                if (o.type == ObjectType.ThreePhaseSeparator) {
                    fields += field("oilRetention", "Oil retention", i.oilRetention, Quantity.Time)
                    fields += field("waterRetention", "Water retention", i.waterRetention, Quantity.Time)
                }
                if (i.horizontal) fields += field("lengthToDiameter", "L/D", i.lengthToDiameter, null, "from the pressure (Svrcek–Monnery)")
                if (rv.stale) why = "Solve the flowsheet first: the sizing uses its results"
                else if (loads == null) why = w ?: "Solve the flowsheet first"
                else {
                    val r = app.flowstead.core.model.SeparatorSizing.size(loads, i, o.type == ObjectType.ThreePhaseSeparator)
                    rep.heading((if (r.horizontal) "Horizontal" else "Vertical") + (if (r.threePhase) " three-phase" else "") + " separator")
                    rep.kv(listOfNotNull(
                        Triple("Diameter", rep.value(r.diameter, Quantity.Length), rep.unitOf(Quantity.Length).symbol),
                        Triple(if (r.horizontal) "Length (tangent to tangent)" else "Height (tangent to tangent)", rep.value(r.length, Quantity.Length), rep.unitOf(Quantity.Length).symbol),
                        Triple(if (r.horizontal) "L/D" else "H/D", ValueFormat.number(r.slenderness, 2), ""),
                        Triple("K used", rep.value(r.k, Quantity.Velocity), rep.unitOf(Quantity.Velocity).symbol),
                        Triple("Allowable gas velocity", rep.value(r.maxGasVelocity, Quantity.Velocity), rep.unitOf(Quantity.Velocity).symbol),
                        Triple("Gas velocity", rep.value(r.gasVelocity, Quantity.Velocity), rep.unitOf(Quantity.Velocity).symbol),
                        Triple("Liquid volume", rep.value(r.liquidVolume, Quantity.Volume), rep.unitOf(Quantity.Volume).symbol),
                        r.mistEliminatorArea?.let { Triple("Mist eliminator face area", ValueFormat.number(it, 2), "m²") },
                    ))
                    if (r.levels.isNotEmpty()) rep.table(listOf("Level", "Height, " + rep.unitOf(Quantity.Length).symbol), r.levels.map { listOf(it.name, rep.value(it.height, Quantity.Length)) })
                    rep.table(listOf("Nozzle", "Bore needed, mm", "Pipe", "Limit"), r.nozzles.map { n -> listOf(n.name, ValueFormat.number(n.requiredDiameter * 1000, 0), n.nps?.let { "NPS $it" } ?: "above 24 in", n.limit) })
                    r.notes.forEach { rep.note(it) }
                    r.warnings.forEach { rep.note(it, "warn") }
                    rep.note("GPSA section 7 (Souders–Brown, pressure factor), Svrcek–Monnery (heights, L/D), API 12J and Arnold & Stewart (three-phase), on the engine's outlet streams.")
                }
            }
            ObjectType.PipeSegment -> {
                kind = "line"
                val svc = app.flowstead.core.model.LineSizing.service(f, o, rv)
                fields += choice("service", "Service", o.refs[app.flowstead.core.model.LineSizing.SERVICE] ?: "", app.flowstead.core.model.LineSizing.SERVICE_OPTIONS.map { (it.first ?: "") to it.second })
                if (svc != null) {
                    val cr = (f.sizingCriteria ?: app.flowstead.core.model.SizingCriteria()).forService(svc)
                    fields += field("maxVelocity", "Max velocity (${svc.label.lowercase()} lines)", cr.maxVelocity?.si, Quantity.Velocity)
                    fields += field("minVelocity", "Min velocity", cr.minVelocity?.si, Quantity.Velocity)
                    fields += field("maxMomentumFlux", "Max ρv²", cr.maxMomentumFlux?.si, Quantity.MomentumFlux)
                    fields += field("maxFrictionGradient", "Max ΔP per length (friction)", cr.maxFrictionGradient?.si, Quantity.PressureGradient)
                    fields += field("maxMach", "Max Mach number", cr.maxMach?.si, null)
                    fields += field("erosionalC", "Erosional C (API RP 14E)", cr.erosionalC?.si, null, "100 continuous, 125 intermittent (solids-free)")
                    if (rv.stale) why = "Solve the flowsheet first: the checks use its results"
                    else {
                        val checks = app.flowstead.core.model.LineSizing.checks(cr, o.id, rv)
                        rep.heading("Line sizing: ${svc.label.lowercase()} service")
                        if (cr.isEmpty) rep.note("No criteria set for ${svc.label.lowercase()} lines: give the project's limits below (they apply to every ${svc.label.lowercase()} line in the case).")
                        else if (checks.isEmpty()) rep.note("The engine returned none of the values these criteria need.")
                        else rep.table(listOf("Check", "Value", "Limit", ""), checks.map { ch ->
                            val u = displayUnits.unitFor(ch.quantity)
                            listOf(ch.label + (ch.note?.let { " ($it)" } ?: ""), ValueFormat.number(u.fromSi(ch.value, c), u.decimals) + " " + u.symbol,
                                (if (ch.isMaximum) "≤ " else "≥ ") + ValueFormat.number(u.fromSi(ch.limit, c), u.decimals) + " " + u.symbol, if (ch.passes) "pass" else "FAIL")
                        })
                    }
                } else why = "Solve the flowsheet first: the service comes from the solved streams"
            }
            in app.flowstead.core.model.EquipmentSizing.TYPES -> {
                kind = "equipment"
                val i = o.equipmentSizing ?: app.flowstead.core.model.EquipmentSizingInput()
                val heat = o.type in setOf(ObjectType.HeatExchanger, ObjectType.Heater, ObjectType.Cooler, ObjectType.AirCooler)
                if (heat) {
                    val groups = app.flowstead.core.model.DataLibrary.U_SERVICES.filter { if (o.type == ObjectType.AirCooler) it.group.startsWith("Air") else !it.group.startsWith("Air") }
                    fields += choice("service", "Service (typical U)", i.service ?: app.flowstead.core.model.EquipmentSizing.defaultService(o.type), groups.map { it.id to "${it.group}: ${it.label} (${it.low.toInt()}–${it.high.toInt()} W/m²·K)" })
                    fields += field("u", "U", i.u, Quantity.HeatTransferCoefficient, "the service's typical value")
                    if (o.type != ObjectType.HeatExchanger) {
                        fields += field("utilityIn", if (o.type == ObjectType.AirCooler) "Air in" else "Utility in", i.utilityIn, Quantity.Temperature)
                        fields += field("utilityOut", if (o.type == ObjectType.AirCooler) "Air out" else "Utility out", i.utilityOut, Quantity.Temperature)
                    }
                    if (o.type == ObjectType.AirCooler) fields += buildJsonObject {
                        put("key", "tubePasses"); put("label", "Tube passes"); put("kind", "number"); put("value", i.tubePasses?.toString() ?: ""); put("unit", ""); put("hint", "1")
                    }
                }
                if (o.type in setOf(ObjectType.Pump, ObjectType.Compressor, ObjectType.Expander)) fields += pctField("margin", "Driver margin over shaft power", i.margin?.let { it }, if (o.type == ObjectType.Pump) "API 610: 125 / 115 / 110 %" else "API 617: 110 %")
                if (o.type == ObjectType.Tank) { fields += field("residenceTime", "Residence time", i.residenceTime, Quantity.Time, "10 min"); fields += pctField("fillFraction", "Filled to", i.fillFraction, "85 %") }
                val packing = if (o.type in app.flowstead.core.model.EquipmentSizing.COLUMNS) app.flowstead.core.model.Packings.of(o) else null
                if (o.type in app.flowstead.core.model.EquipmentSizing.COLUMNS) {
                    // Trays or a packed bed (D152): a packing is rated by the engine, so choosing one asks for a solve.
                    val P = app.flowstead.core.model.Packings
                    fields += choice(P.PACKING, "Internals", packing ?: "", listOf("" to "Sieve trays") +
                        P.RANDOM.map { it to "Random: $it" } + P.STRUCTURED.map { it to "Structured: $it" } +
                        listOfNotNull(packing?.takeIf { it !in P.ALL }?.let { it to it }))
                }
                if (packing != null) {
                    val P = app.flowstead.core.model.Packings
                    fields += pctField(P.FLOOD, "Design flood", P.number(o, P.FLOOD), "70 %")
                    fields += field(P.DIAMETER, "Diameter", P.number(o, P.DIAMETER), Quantity.Length, "sized for the flood")
                    fields += field(P.HEIGHT, "Bed height", P.number(o, P.HEIGHT), Quantity.Length, "stages × HETP")
                } else if (o.type in app.flowstead.core.model.EquipmentSizing.COLUMNS) {
                    fields += field("traySpacing", "Tray spacing", i.traySpacing, Quantity.Length, "610 mm")
                    fields += pctField("floodFraction", "Design flood", i.floodFraction, "80 %")
                    fields += pctField("downcomerFraction", "Downcomer area", i.downcomerFraction, "12 %")
                    fields += pctField("trayEfficiency", "Tray efficiency", i.trayEfficiency, "70 %")
                    fields += field("weirHeight", "Weir height", i.weirHeight, Quantity.Length, "50 mm")
                    fields += field("holeDiameter", "Hole diameter", i.holeDiameter, Quantity.Length, "5 mm")
                    fields += pctField("holeFraction", "Hole area (of active)", i.holeFraction, "10 %")
                }
                if (rv.stale) why = "Solve the flowsheet first: the sizing uses its results"
                else {
                    val r = app.flowstead.core.model.EquipmentSizing.size(f, rv, o, i)
                    if (r == null) why = "Solve the flowsheet (and connect the streams) to size it"
                    else {
                        rep.heading("Sizing")
                        rep.kv(r.lines.map { l ->
                            val q = l.quantity
                            when {
                                l.si != null && q != null && q != Quantity.Count -> Triple(l.label, rep.value(l.si, q), rep.unitOf(q).symbol)
                                l.si != null && q == Quantity.Count -> Triple(l.label, ValueFormat.number(l.si, 0), "")
                                else -> Triple(l.label, l.text ?: "", "")
                            }
                        })
                        r.notes.forEach { rep.note(it) }
                        r.warnings.forEach { rep.note(it, "warn") }
                    }
                }
            }
            else -> return "{\"applies\":false}"
        }
        if (why != null) rep.note(why, "warn")
        return buildJsonObject {
            put("applies", true); put("kind", kind)
            put("report", FlowsheetJson.parseToJsonElement(rep.json()))
            putJsonArray("fields") { fields.forEach { add(it) } }
        }.toString()
    }

    /** Changes one sizing basis field of [id] ({key, value} as typed in the field's display unit; blank: the library's). */
    fun sizingEdit(flowsheetJson: String, id: String, opJson: String, solveJson: String?, solvedHash: String): String = edit {
        val f = fs(flowsheetJson)
        val o = f.obj(id) ?: throw EditRejected("That object no longer exists")
        val op = FlowsheetJson.parseToJsonElement(opJson).jsonObject
        val key = op["key"]?.jsonPrimitive?.content ?: throw EditRejected("No field")
        val text = op["value"]?.jsonPrimitive?.contentOrNull
        val c = ctx(f)
        fun q(qq: Quantity?) = parse(text, qq?.let { displayUnits.unitFor(it) }, c)
        fun frac() = parse(text, null, c)?.let { it / 100 }
        when (o.type) {
            ObjectType.Separator, ObjectType.ThreePhaseSeparator -> {
                val i = o.sizing ?: app.flowstead.core.model.SeparatorSizingInput()
                val n = when (key) {
                    "horizontal" -> i.copy(horizontal = text == "true")
                    "mistEliminator" -> i.copy(mistEliminator = app.flowstead.core.model.MistEliminator.valueOf(text!!))
                    "inletDevice" -> i.copy(inletDevice = app.flowstead.core.model.InletDevice.valueOf(text!!))
                    "k" -> i.copy(k = q(Quantity.Velocity)?.also { if (it <= 0) throw EditRejected("K must be positive") })
                    "holdupTime" -> i.copy(holdupTime = q(Quantity.Time) ?: app.flowstead.core.model.SeparatorSizingInput().holdupTime)
                    "surgeTime" -> i.copy(surgeTime = q(Quantity.Time) ?: app.flowstead.core.model.SeparatorSizingInput().surgeTime)
                    "oilRetention" -> i.copy(oilRetention = q(Quantity.Time) ?: app.flowstead.core.model.SeparatorSizingInput().oilRetention)
                    "waterRetention" -> i.copy(waterRetention = q(Quantity.Time) ?: app.flowstead.core.model.SeparatorSizingInput().waterRetention)
                    "lengthToDiameter" -> i.copy(lengthToDiameter = q(null)?.also { if (it < 1) throw EditRejected("L/D below 1 is not a horizontal vessel") })
                    else -> throw EditRejected("Unknown field")
                }
                FlowsheetEditing.setSizing(f, o.id, n.takeIf { it != app.flowstead.core.model.SeparatorSizingInput() })
            }
            ObjectType.PipeSegment -> {
                if (key == "service") return@edit FlowsheetEditing.setRef(f, o.id, app.flowstead.core.model.LineSizing.SERVICE, text?.takeIf { it.isNotEmpty() })
                // The service the sizing shows: the one chosen, or the solved streams' (the same call as sizing()).
                val svc = app.flowstead.core.model.LineSizing.service(f, o, view(f, solveJson, solvedHash))
                    ?: throw EditRejected("Choose the service (or solve) first: criteria are set per service")
                val all = f.sizingCriteria ?: app.flowstead.core.model.SizingCriteria()
                val cr = all.forService(svc)
                fun sp(qq: Quantity?) = q(qq)?.let { app.flowstead.core.model.Spec(it, qq?.let { x -> displayUnits.unitFor(x).id } ?: "") }
                val n = when (key) {
                    "maxVelocity" -> cr.copy(maxVelocity = sp(Quantity.Velocity))
                    "minVelocity" -> cr.copy(minVelocity = sp(Quantity.Velocity))
                    "maxMomentumFlux" -> cr.copy(maxMomentumFlux = sp(Quantity.MomentumFlux))
                    "maxFrictionGradient" -> cr.copy(maxFrictionGradient = sp(Quantity.PressureGradient))
                    "maxMach" -> cr.copy(maxMach = sp(null))
                    "erosionalC" -> cr.copy(erosionalC = sp(null))
                    else -> throw EditRejected("Unknown field")
                }
                FlowsheetEditing.setSizingCriteria(f, all.with(svc, n))
            }
            in app.flowstead.core.model.EquipmentSizing.TYPES -> {
                // A packed column's basis lives in its refs, sent to the engine that rates the bed (D152).
                val P = app.flowstead.core.model.Packings
                if (o.type in app.flowstead.core.model.EquipmentSizing.COLUMNS && key in setOf(P.PACKING, P.FLOOD, P.DIAMETER, P.HEIGHT)) {
                    val value = when (key) {
                        P.PACKING -> text?.takeIf { it.isNotBlank() }?.also { if (it !in P.ALL) throw EditRejected("'$it' is not in DWSIM's packing catalogue") }
                        P.FLOOD -> frac()?.also { if (it <= 0 || it >= 1) throw EditRejected("Between 0 and 100 %") }?.let(P::text)
                        else -> q(Quantity.Length)?.also { if (it <= 0) throw EditRejected("Must be positive") }?.let(P::text)
                    }
                    // Back to trays keeps the bed's numbers on the column for when a packing is chosen again.
                    return@edit FlowsheetEditing.setRef(f, o.id, key, value)
                }
                val i = o.equipmentSizing ?: app.flowstead.core.model.EquipmentSizingInput()
                val n = when (key) {
                    "service" -> i.copy(service = text?.takeIf { it.isNotEmpty() })
                    "u" -> i.copy(u = q(Quantity.HeatTransferCoefficient))
                    "utilityIn" -> i.copy(utilityIn = q(Quantity.Temperature))
                    "utilityOut" -> i.copy(utilityOut = q(Quantity.Temperature))
                    "tubePasses" -> i.copy(tubePasses = parse(text, null, c)?.let { v -> if (v < 1 || v > 8 || v != kotlin.math.floor(v)) throw EditRejected("Between 1 and 8 tube passes") else v.toInt() })
                    "margin" -> i.copy(margin = frac()?.also { if (it < 1) throw EditRejected("A driver margin is 100 % or more") })
                    "residenceTime" -> i.copy(residenceTime = q(Quantity.Time))
                    "fillFraction" -> i.copy(fillFraction = frac()?.also { if (it <= 0 || it > 1) throw EditRejected("Between 0 and 100 %") })
                    "traySpacing" -> i.copy(traySpacing = q(Quantity.Length))
                    "floodFraction" -> i.copy(floodFraction = frac())
                    "downcomerFraction" -> i.copy(downcomerFraction = frac())
                    "trayEfficiency" -> i.copy(trayEfficiency = frac())
                    "weirHeight" -> i.copy(weirHeight = q(Quantity.Length))
                    "holeDiameter" -> i.copy(holeDiameter = q(Quantity.Length))
                    "holeFraction" -> i.copy(holeFraction = frac())
                    else -> throw EditRejected("Unknown field")
                }
                FlowsheetEditing.setEquipmentSizing(f, o.id, n.takeIf { it != app.flowstead.core.model.EquipmentSizingInput() })
            }
            else -> throw EditRejected("${o.tag} has no sizing")
        }
    }

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
            if (!pump) put("path", if (curve.polytropic) CurvePaths.POLYTROPIC else CurvePaths.ADIABATIC)
            put("speed", num(curve.speed, null, c, 0)); put("operatingSpeed", num(curve.operatingSpeed, null, c, 0))
            put("solvesSpeed", curve.solvesSpeed)
            (results.value(o.id, "operatingSpeed") as? DisplayValue.Calculated)?.takeIf { !results.stale }?.let { put("solvedSpeed", ValueFormat.number(it.si, 0)) }
            putJsonArray("lines") { curve.speedLines.forEach { l -> addJsonObject { put("speed", num(l.speed, null, c, 0)); put("points", points(l.points)) } } }
            // The curve with the engine's operating point on it, in the user's units.
            if (curve.points.size >= 2 && Specification.curveProblem(o) == null) {
                val q = (results.value(o.id, "inletVolumeFlow") as? DisplayValue.Calculated)?.takeIf { !results.stale }?.si
                val h = (results.value(o.id, if (pump) "head" else curve.headKey) as? DisplayValue.Calculated)?.takeIf { !results.stale }?.si
                putJsonObject("plot") {
                    put("kind", "plot"); put("title", if (pump) "Pump curve" else "Compressor curve")
                    put("xLabel", "Actual inlet flow, ${qu.symbol}"); put("yLabel", "${if (pump) "Head" else if (curve.polytropic) "Polytropic head" else "Adiabatic head"}, ${hu.symbol}")
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
            "on" -> PerformanceCurve.newFor(o.type)
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
                "path" -> curve.copy(path = if (s("value") == CurvePaths.POLYTROPIC) CurvePaths.POLYTROPIC else null)
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
                        put("name", k?.let { "${it.name} · ${PipeFittings.describe(s)}" } ?: "Straight run")
                        if (s.fitting == PipeFittings.USER_K) put("k", s.k?.let { ValueFormat.number(it, 4).replace(",", "") } ?: "")
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
            val kSum = list.mapNotNull { s -> PipeFittings.kOf(s)?.let { it * s.count } }.sum()
            if (list.isNotEmpty()) put("totals",
                "Straight length ${num(run, lu, c)} ${lu.symbol}, rise ${num(rise, lu, c)} ${lu.symbol}" +
                    (if (eq > 0) "; fittings as ${num(eq, lu, c)} ${lu.symbol} more pipe" else "") +
                    (if (kSum > 0) "; K ${ValueFormat.number(kSum, 2)} velocity heads" else "") +
                    ". Each fitting's loss is DWSIM's (Crane L/D or K, or the K you give).")
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
                    "k" -> x.copy(k = parse(s("value"), null, c)?.also { if (!(it > 0)) throw EditRejected("K must be positive") })
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
