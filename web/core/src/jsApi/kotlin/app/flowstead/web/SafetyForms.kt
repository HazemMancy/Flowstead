/*
 * Flowstead web (D112): safety analysis as the app's SafetySheet: relief valves (API 521 loads, API 520 sizing and line
 * checks), depressuring (blowdown through a restriction orifice) and pipeline pressurization. Kotlin describes each
 * case's form (the app's fields, hints and defaults) and edits the case as the app does, by field path; the engine
 * request, the stored result and the plots are the app's core (Safety, FlowsheetEditing).
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:OptIn(ExperimentalJsExport::class)

package app.flowstead.web

import app.flowstead.core.api.DepressuringRequest
import app.flowstead.core.api.DepressuringResult
import app.flowstead.core.api.ReliefLine
import app.flowstead.core.api.ReliefRequest
import app.flowstead.core.api.ReliefResult
import app.flowstead.core.api.ReliefScenario
import app.flowstead.core.api.ReliefScenarioKinds
import app.flowstead.core.api.SolveResult
import app.flowstead.core.api.ValveDesigns
import app.flowstead.core.format.ValueFormat
import app.flowstead.core.model.DepressuringCase
import app.flowstead.core.model.EditRejected
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.FlowsheetEditing
import app.flowstead.core.model.FlowsheetJson
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.PipeSection
import app.flowstead.core.model.PipeSizes
import app.flowstead.core.model.PressurizationCase
import app.flowstead.core.model.PressurizationValves
import app.flowstead.core.model.PressurizationSources
import app.flowstead.core.model.ReliefDeviceDef
import app.flowstead.core.model.ResultView
import app.flowstead.core.model.Safety
import app.flowstead.core.model.SimObject
import app.flowstead.core.model.StoredSolve
import app.flowstead.core.model.displayName
import app.flowstead.core.model.modelHash
import app.flowstead.core.units.Quantity
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.Units
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

private const val RELIEF = "relief"
private const val BLOWDOWN = "depressuring"
private const val FILL = "pressurization"

@JsExport
object SafetyForms {
    private fun fs(json: String): Flowsheet = FlowsheetJson.decodeFromString(Flowsheet.serializer(), json)
    private fun out(f: Flowsheet) = buildJsonObject { put("flowsheet", FlowsheetJson.encodeToJsonElement(Flowsheet.serializer(), f)) }.toString()
    private fun err(m: String) = buildJsonObject { put("error", m) }.toString()
    private fun view(f: Flowsheet, solveJson: String?, solvedHash: String): ResultView =
        solveJson?.takeIf { it.isNotBlank() && it != "null" }?.let { ResultView(StoredSolve(FlowsheetJson.decodeFromString(SolveResult.serializer(), it), solvedHash, 0L), f.modelHash()) } ?: ResultView.NONE
    private fun vessels(f: Flowsheet) = f.objects.filter { it.type in Safety.PROTECTABLE }
    private fun newId() = FlowsheetEditing.newId()

    /** The three lists, each case with its one-line summary: {relief: [...], depressuring: [...], pressurization: [...], vessels}. */
    fun overview(flowsheetJson: String): String {
        val f = fs(flowsheetJson)
        val s = f.safety
        val hash = f.modelHash()
        fun item(id: String, title: String, summary: String?, stale: Boolean) = buildJsonObject { put("id", id); put("title", title); summary?.let { put("summary", it) }; put("stale", stale) }
        return buildJsonObject {
            putJsonArray(RELIEF) { s?.devices.orEmpty().forEach { d -> add(item(d.id, d.tag, d.result?.let { r -> r.orifice?.let { o -> (if (r.valves > 1) "${r.valves} × " else "") + (r.valveSize ?: o) } }, d.result != null && d.resultHash != hash)) } }
            putJsonArray(BLOWDOWN) { s?.depressuring.orEmpty().forEach { c -> add(item(c.id, c.name, c.result?.timeToTarget?.let { "${ValueFormat.number(it / 60, 1)} min" }, c.result != null && c.resultHash != hash)) } }
            putJsonArray(FILL) { s?.pressurization.orEmpty().forEach { c -> add(item(c.id, c.name, c.result?.timeToTarget?.let { "${ValueFormat.number(it / 60, 1)} min" }, c.result != null && c.resultHash != hash)) } }
            put("vessels", vessels(f).size)
        }.toString()
    }

    /** A new case of [kind], set up as the app's "Add" buttons do. Returns {flowsheet, id} or {error}. */
    fun create(flowsheetJson: String, solveJson: String?, solvedHash: String, kind: String): String = try {
        val f = fs(flowsheetJson)
        val results = view(f, solveJson, solvedHash)
        val study = f.safety
        val id = newId()
        val next = when (kind) {
            RELIEF -> {
                val v = vessels(f).firstOrNull() ?: throw EditRejected("Add a separator, tank or pipe segment to protect first")
                val n = (study?.devices?.size ?: 0) + 100
                // HYSYS-like: start from the scenarios the flowsheet suggests for the vessel.
                FlowsheetEditing.upsertReliefDevice(f, ReliefDeviceDef(id, "PSV-$n", protectedId = v.id,
                    scenarios = Safety.suggest(f, results, v.id).map { it.scenario }
                        .ifEmpty { listOf(ReliefScenario(ReliefScenarioKinds.FIRE, "Fire"), ReliefScenario(ReliefScenarioKinds.BLOCKED_OUTLET, "Blocked outlet")) }))
            }
            BLOWDOWN -> {
                val v = vessels(f).firstOrNull() ?: throw EditRejected("Add a separator, tank or pipe segment to depressure first")
                val geometry = study?.devices?.firstOrNull { it.protectedId == v.id }?.vessel
                FlowsheetEditing.upsertDepressuring(f, DepressuringCase(id, "Depressuring ${(study?.depressuring?.size ?: 0) + 1}", v.id, geometry))
            }
            FILL -> {
                val pipes = f.objects.filter { it.type == ObjectType.PipeSegment }.map { it.id }
                val supply = f.objects.firstOrNull { it.type == ObjectType.MaterialStream }?.id
                FlowsheetEditing.upsertPressurization(f, PressurizationCase(id, "Pressurization ${(study?.pressurization?.size ?: 0) + 1}", supply, pipes))
            }
            else -> throw EditRejected("Unknown safety case")
        }
        buildJsonObject { put("flowsheet", FlowsheetJson.encodeToJsonElement(Flowsheet.serializer(), next)); put("id", id) }.toString()
    } catch (e: IllegalArgumentException) { err(e.message ?: "Not possible") }

    private fun relief(f: Flowsheet, id: String) = f.safety?.devices?.firstOrNull { it.id == id } ?: throw EditRejected("That relief valve no longer exists")
    private fun blowdown(f: Flowsheet, id: String) = f.safety?.depressuring?.firstOrNull { it.id == id } ?: throw EditRejected("That case no longer exists")
    private fun fill(f: Flowsheet, id: String) = f.safety?.pressurization?.firstOrNull { it.id == id } ?: throw EditRejected("That case no longer exists")

    // ---------- the form ----------

    fun form(flowsheetJson: String, solveJson: String?, solvedHash: String, kind: String, id: String): String {
        val f = fs(flowsheetJson)
        val results = view(f, solveJson, solvedHash)
        val b = SafetyForm(f, UnitContext(f.atmosphericPressurePa))
        when (kind) {
            RELIEF -> b.relief(relief(f, id), results, vessels(f))
            BLOWDOWN -> b.blowdown(blowdown(f, id), results, vessels(f))
            FILL -> b.fill(fill(f, id), results)
        }
        return buildJsonObject { put("items", JsonArray(b.items)) }.toString()
    }

    // ---------- edits, by field path, as the app's SafetySheet saves the case ----------

    /**
     * [actionJson]: {op: "set", path, value, unit?} | {op: "addScenario", value} | {op: "removeScenario", index} |
     * {op: "addSuggestion", index} | {op: "lineSize", path, value} | {op: "lineSchedule", path, value} | {op: "pipe", value, on} |
     * {op: "addSection"} | {op: "removeSection", index} | {op: "delete"}.
     */
    fun apply(flowsheetJson: String, solveJson: String?, solvedHash: String, kind: String, id: String, actionJson: String): String = try {
        val f = fs(flowsheetJson)
        val results = view(f, solveJson, solvedHash)
        val a = FlowsheetJson.parseToJsonElement(actionJson).jsonObject
        fun s(k: String) = a[k]?.jsonPrimitive?.content
        val ctx = UnitContext(f.atmosphericPressurePa)
        val op = s("op")
        out(when (kind) {
            RELIEF -> {
                val d = relief(f, id)
                when (op) {
                    "delete" -> FlowsheetEditing.deleteReliefDevice(f, id)
                    "addScenario" -> FlowsheetEditing.upsertReliefDevice(f, d.copy(scenarios = d.scenarios + ReliefScenario(s("value")!!, ReliefScenarioKinds.label(s("value")!!))))
                    "removeScenario" -> FlowsheetEditing.upsertReliefDevice(f, d.copy(scenarios = d.scenarios.filterIndexed { j, _ -> j != s("index")!!.toInt() }))
                    "addSuggestion" -> FlowsheetEditing.upsertReliefDevice(f, d.copy(scenarios = d.scenarios + Safety.newSuggestions(f, results, d)[s("index")!!.toInt()].scenario))
                    "lineSize", "lineSchedule" -> FlowsheetEditing.upsertReliefDevice(f, line(d, s("path")!!, op, s("value").orEmpty()))
                    else -> FlowsheetEditing.upsertReliefDevice(f, setPath(d, ReliefDeviceDef.serializer(), a, ctx))
                }
            }
            BLOWDOWN -> {
                val c = blowdown(f, id)
                if (op == "delete") FlowsheetEditing.deleteDepressuring(f, id) else FlowsheetEditing.upsertDepressuring(f, setPath(c, DepressuringCase.serializer(), a, ctx))
            }
            FILL -> {
                val c = fill(f, id)
                when (op) {
                    "delete" -> FlowsheetEditing.deletePressurization(f, id)
                    "pipe" -> FlowsheetEditing.upsertPressurization(f, c.copy(pipeIds = if (a["on"]?.jsonPrimitive?.booleanOrNull == true) (c.pipeIds + s("value")!!).distinct() else c.pipeIds - s("value")!!))
                    "addSection" -> FlowsheetEditing.upsertPressurization(f, c.copy(sections = c.sections + PipeSection(0.2, 1000.0)))
                    "removeSection" -> FlowsheetEditing.upsertPressurization(f, c.copy(sections = c.sections.filterIndexed { j, _ -> j != s("index")!!.toInt() }))
                    "fill" -> FlowsheetEditing.upsertPressurization(f, c.copy(fillId = s("value")?.takeIf { it.isNotEmpty() }))
                    else -> {
                        val next = setPath(c, PressurizationCase.serializer(), a, ctx)
                        // As the app: the run lasts at least twice the target time.
                        FlowsheetEditing.upsertPressurization(f, if (a["path"]?.jsonPrimitive?.content == "targetTime") next.copy(duration = maxOf(next.duration, 2 * next.targetTime)) else next)
                    }
                }
            }
            else -> throw EditRejected("Unknown safety case")
        })
    } catch (e: IllegalArgumentException) { err(e.message ?: "Not allowed") } catch (e: NullPointerException) { err("Incomplete change") } catch (e: IndexOutOfBoundsException) { err("That item no longer exists") }

    /** A relief line by nominal size and schedule (inside diameter from ASME B36.10M/B36.19M), as the app's LineFields. */
    private fun line(d: ReliefDeviceDef, path: String, op: String, value: String): ReliefDeviceDef {
        val current = if (path == "inletLine") d.inletLine else d.outletLine
        val next: ReliefLine? = if (op == "lineSize") {
            val size = PipeSizes.size(value)
            if (size == null) null else {
                val sch = size.schedules.firstOrNull { "40" in it.label.split(" / ") } ?: size.schedules.first()
                (current ?: ReliefLine(0.0, 3.0)).copy(insideDiameter = size.insideDiameterM(sch))
            }
        } else {
            val l = current ?: return d
            val size = PipeSizes.ALL.firstOrNull { s -> s.schedules.any { abs(s.insideDiameterM(it) - l.insideDiameter) < 1e-6 } } ?: return d
            size.schedule(value)?.let { l.copy(insideDiameter = size.insideDiameterM(it)) } ?: l
        }
        return if (path == "inletLine") d.copy(inletLine = next) else d.copy(outletLine = next)
    }

    /**
     * Sets one field at [a]'s path ("setPressure", "vessel.diameter", "scenarios.0.massFlow"): a typed number in its unit
     * (stored SI), a plain number, text, a boolean or a choice; blank clears it, or puts back the field's default.
     */
    private fun <T> setPath(item: T, serializer: kotlinx.serialization.KSerializer<T>, a: JsonObject, ctx: UnitContext): T {
        val path = a["path"]!!.jsonPrimitive.content.split('.')
        val raw = a["value"]
        val unit = a["unit"]?.jsonPrimitive?.content
        val blank = a["blank"]
        val value: JsonElement = when {
            raw is JsonPrimitive && raw.isString && a["bool"]?.jsonPrimitive?.booleanOrNull == true -> JsonPrimitive(raw.content == "true")
            raw is JsonPrimitive && raw.isString && unit != null -> {
                val t = raw.content.trim().replace(',', '.')
                if (t.isEmpty()) blank ?: JsonNull
                else JsonPrimitive((Units.byIdOrNull(unit) ?: throw EditRejected("Unknown unit")).toSi(t.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw EditRejected("Not a number: $t"), ctx))
            }
            raw is JsonPrimitive && raw.isString && a["number"]?.jsonPrimitive?.booleanOrNull == true -> {
                val t = raw.content.trim().replace(',', '.')
                if (t.isEmpty()) blank ?: JsonNull else JsonPrimitive(t.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw EditRejected("Not a number: $t"))
            }
            raw is JsonPrimitive && raw.isString && raw.content.isEmpty() -> blank ?: JsonNull
            else -> raw ?: JsonNull
        }
        val tree = FlowsheetJson.encodeToJsonElement(serializer, item)
        return FlowsheetJson.decodeFromJsonElement(serializer, put(tree, path, value, a["create"]))
    }

    private fun put(node: JsonElement?, path: List<String>, value: JsonElement, create: JsonElement?): JsonElement {
        if (path.isEmpty()) return value
        val key = path.first()
        return when (node) {
            is JsonArray -> { val i = key.toInt(); JsonArray(node.mapIndexed { j, e -> if (j == i) put(e, path.drop(1), value, create) else e }) }
            is JsonObject -> {
                val child = node[key]?.takeIf { it !is JsonNull } ?: (if (path.size > 1) create ?: JsonObject(emptyMap()) else null)
                val m = node.toMutableMap()
                val v = put(child, path.drop(1), value, create)
                if (v is JsonNull) m.remove(key) else m[key] = v
                JsonObject(m)
            }
            else -> if (path.size == 1) JsonObject(mapOf(key to value)) else JsonObject(mapOf(key to put(create, path.drop(1), value, null)))
        }
    }

    // ---------- run: the engine request, then the result kept on the case (as the app) ----------

    fun request(flowsheetJson: String, solveJson: String?, solvedHash: String, kind: String, id: String): String = try {
        val f = fs(flowsheetJson)
        val results = view(f, solveJson, solvedHash)
        when (kind) {
            RELIEF -> {
                val d = relief(f, id)
                Safety.problem(f, results, d)?.let { throw EditRejected(it) }
                call("relief", FlowsheetJson.encodeToJsonElement(ReliefRequest.serializer(), ReliefRequest(f, Safety.request(f, results, d))))
            }
            BLOWDOWN -> {
                val c = blowdown(f, id)
                Safety.depressuringProblem(f, results, c)?.let { throw EditRejected(it) }
                call("depressuring", FlowsheetJson.encodeToJsonElement(DepressuringRequest.serializer(), Safety.depressuringRequest(f, results, c)))
            }
            FILL -> {
                val c = fill(f, id)
                Safety.pressurizationProblem(f, results, c)?.let { throw EditRejected(it) }
                call("depressuring", FlowsheetJson.encodeToJsonElement(DepressuringRequest.serializer(), Safety.pressurizationRequest(f, results, c)))
            }
            else -> err("Unknown safety case")
        }
    } catch (e: IllegalArgumentException) { err(e.message ?: "Not possible") }

    private fun call(method: String, params: JsonElement) = buildJsonObject { put("method", method); put("params", params) }.toString()

    /** Keeps the engine's [resultJson] on the case with the flowsheet's hash, as the app does. Returns {flowsheet}. */
    fun store(flowsheetJson: String, kind: String, id: String, resultJson: String): String = try {
        val f = fs(flowsheetJson)
        val hash = f.modelHash()
        out(when (kind) {
            RELIEF -> FlowsheetEditing.setReliefResult(f, id, FlowsheetJson.decodeFromString(ReliefResult.serializer(), resultJson), hash)
            BLOWDOWN -> FlowsheetEditing.setDepressuringResult(f, id, FlowsheetJson.decodeFromString(DepressuringResult.serializer(), resultJson), hash)
            FILL -> FlowsheetEditing.setPressurizationResult(f, id, FlowsheetJson.decodeFromString(DepressuringResult.serializer(), resultJson), hash)
            else -> throw EditRejected("Unknown safety case")
        })
    } catch (e: IllegalArgumentException) { err(e.message ?: "Not stored") }

    /** The stored result as the app's SafetySheet shows it: the answer first, then each scenario, the checks and the plots. */
    fun report(flowsheetJson: String, kind: String, id: String): String {
        val f = fs(flowsheetJson)
        val rep = Report(UnitContext(f.atmosphericPressurePa))
        val hash = f.modelHash()
        fun stale(h: String?) { if (h != null && h != hash) rep.note("⚠ out of date: the flowsheet changed since this ran", "warn") }
        fun t(v: Double) = rep.withUnit(v, Quantity.Temperature)
        when (kind) {
            RELIEF -> {
                val d = relief(f, id); val r = d.result ?: return rep.json()
                stale(d.resultHash)
                fun area(v: Double) = "${ValueFormat.number(v * 1e6, 1)} mm² (${ValueFormat.number(v / 6.4516e-4, 3)} in²)"
                fun dia(v: Double) = "Ø ${ValueFormat.number(v * 1000, 1)} mm (${ValueFormat.number(v / 0.0254, 2)} in)"
                val orifice = r.orifice; val orificeArea = r.orificeArea
                if (orifice != null && orificeArea != null) {
                    rep.heading((if (r.valves > 1) "${r.valves} × " else "") + "API 526 orifice $orifice" + (r.valveSize?.let { " · valve $it" } ?: ""))
                    r.valveSize?.let { rep.note("$it: inlet × orifice × outlet in inches, the smallest standard API 526 size; the maker confirms the flange rating.") }
                    rep.kv(listOfNotNull(
                        Triple("Selected", area(orificeArea) + (r.orificeDiameter?.let { ", ${dia(it)}" } ?: "") + if (r.valves > 1) " each" else "", ""),
                        r.requiredArea?.let { Triple("Required", area(it) + (r.requiredDiameter?.let { dd -> ", ${dia(dd)}" + if (r.valves > 1) " per valve" else "" } ?: "") + " · governed by ${r.governing}", "") },
                        r.ratedFlow?.let { Triple("Rated flow (for the line checks)", rep.withUnit(it, Quantity.MassFlow), "") },
                    ))
                }
                for (s in r.scenarios) {
                    rep.heading(s.name)
                    val e = s.error
                    if (e != null) rep.note(e, "error") else {
                        val phase = when (s.phase) { "vapour" -> "vapour"; "liquid" -> "liquid"; "twoPhase" -> "two-phase"; else -> s.phase ?: "" }
                        rep.note(listOfNotNull(s.massFlow?.let { rep.withUnit(it, Quantity.MassFlow) }, phase, "relieving at ${rep.withUnit(s.relievingPressure, Quantity.Pressure)}",
                            s.relievingTemperature?.let { t(it) }, s.requiredArea?.let { a -> "needs ${area(a)}, ${dia(sqrt(4 * a / PI))}" }).joinToString(" · "))
                        s.method?.let { rep.note(it) }
                        s.details["heatInput"]?.let { q -> rep.note("Heat input ${ValueFormat.number(q / 1000, 0)} kW" + (s.details["wettedArea"]?.let { " on ${ValueFormat.number(it, 2)} m² wetted" } ?: "") +
                            (s.details["latentHeat"]?.let { ", latent heat ${ValueFormat.number(it / 1000, 1)} kJ/kg" } ?: "")) }
                    }
                    s.warnings.forEach { rep.note(it, "warn") }
                }
                fun check(label: String, c: app.flowstead.core.api.LineCheck) {
                    val value = c.pressureDrop?.let { "${rep.withUnit(it, Quantity.PressureDifference)} (${ValueFormat.number((c.fractionOfSet ?: 0.0) * 100, 1)} % of set)" } ?: "—"
                    rep.note("${if (c.ok) "✓" else "✗"} $label: $value", if (c.ok) "ok" else "error"); rep.note(c.note)
                }
                r.inlet?.let { check("Inlet loss", it) }
                r.outlet?.let { check("Built-up back pressure", it) }
                r.warnings.forEach { rep.note(it, "warn") }
            }
            BLOWDOWN -> {
                val c = blowdown(f, id); val r = c.result ?: return rep.json()
                stale(c.resultHash)
                rep.heading("Orifice ${ValueFormat.number(r.orificeDiameter * 1000, 1)} mm" + if (r.orificeSized) " (sized)" else "")
                rep.kv(listOf(
                    Triple("Target ${rep.withUnit(r.targetPressure, Quantity.Pressure)}", r.timeToTarget?.let { "reached after ${ValueFormat.number(it / 60, 1)} min" } ?: "not reached", ""),
                ) + listOfNotNull(Safety.targetBasisText(r.targetBasis)?.let { Triple("Target basis", it, "") }) + listOf(
                    Triple("Lowest vapour temperature", t(r.minimumTemperature), ""),
                ) + listOfNotNull(r.minimumLiquidTemperature?.let { Triple("Lowest liquid temperature", t(it), "") }) + listOf(
                    Triple("Lowest inner wall temperature", t(r.minimumWallTemperature), ""),
                    Triple("Wall thickness", "${ValueFormat.number(r.wallThickness * 1000, 1)} mm", ""),
                    Triple("Peak flow", rep.withUnit(r.peakFlow, Quantity.MassFlow), ""),
                ))
                r.warnings.forEach { rep.note(it, "warn") }
                Safety.depressuringPlots(r).forEach { rep.plot(it) }
            }
            FILL -> {
                val c = fill(f, id); val r = c.result ?: return rep.json()
                stale(c.resultHash)
                val te = r.timeToEqualise
                val pe = r.equalisingPressure
                rep.heading(when {
                    c.source == PressurizationSources.VESSEL && c.targetPressure == null ->
                        if (te != null && pe != null) "Equalised at ${rep.withUnit(pe, Quantity.Pressure)} after ${ValueFormat.number(te / 60, 1)} min" else "Not equalised in ${ValueFormat.number(c.duration / 3600, 1)} h"
                    else -> "Target ${rep.withUnit(r.targetPressure, Quantity.Pressure)} " + (r.timeToTarget?.let { "reached after ${ValueFormat.number(it / 60, 1)} min" } ?: "not reached")
                })
                if (c.source == PressurizationSources.VESSEL) rep.kv(listOfNotNull(
                    pe?.let { Triple("Equalising pressure", rep.withUnit(it, Quantity.Pressure) + (te?.let { t -> " after ${ValueFormat.number(t / 60, 1)} min" } ?: ""), "") },
                    r.massTransferred?.let { Triple("Mass moved from the source", ValueFormat.number(it, 1), "kg") },
                    r.sourceMinimumTemperature?.let { Triple("Source's coldest gas", t(it), "") },
                    r.sourceMinimumWallTemperature?.let { Triple("Source's lowest inner wall temperature", t(it), "") },
                ))
                rep.kv(listOf(
                    Triple("Inlet", r.cv?.let { "Cv ${ValueFormat.number(it, 1)}" } ?: ("${ValueFormat.number(r.orificeDiameter * 1000, 1)} mm orifice" + if (r.orificeSized) " (sized)" else ""), ""),
                    Triple("Gas temperature", "${t(r.minimumTemperature)} to ${r.maximumTemperature?.let(::t) ?: "—"}", ""),
                ) + listOfNotNull(r.minimumInletTemperature?.let { Triple("Coldest gas entering (let down from the supply)", t(it), "") }) + listOf(
                    Triple("Lowest inner wall temperature", t(r.minimumWallTemperature), ""),
                    Triple("Peak flow", rep.withUnit(r.peakFlow, Quantity.MassFlow), ""),
                ))
                r.warnings.forEach { rep.note(it, "warn") }
                Safety.depressuringPlots(r, filling = true).forEach { rep.plot(it) }
            }
        }
        return rep.json()
    }
}

/** The app's SafetySheet fields, as JSON form items with the path each one edits. */
private class SafetyForm(val fs: Flowsheet, val ctx: UnitContext) {
    val items = mutableListOf<JsonElement>()
    fun note(text: String, level: String = "info") { items += buildJsonObject { put("kind", "note"); put("text", text); put("level", level) } }
    fun label(text: String) { items += buildJsonObject { put("kind", "label"); put("text", text) } }

    /** A quantity in its unit; [blank] is what an empty field stores (null: the field is cleared). */
    fun q(path: String, label: String, si: Double?, quantity: Quantity, hint: String? = null, blank: Double? = null, small: Boolean = false, create: JsonObject? = null) {
        val def = if (small && quantity == Quantity.Length) Units.mm else displayUnits.unitFor(quantity)
        items += buildJsonObject {
            put("kind", "q"); put("path", path); put("label", label); put("unit", def.id)
            putJsonArray("units") {
                Units.forQuantity(quantity).forEach { u ->
                    addJsonObject { put("id", u.id); put("symbol", u.symbol.ifEmpty { u.id }); put("text", si?.let { ValueFormat.number(u.fromSi(it, ctx), u.decimals).replace(",", "") } ?: "") }
                }
            }
            hint?.let { put("hint", it) }; blank?.let { put("blank", it) }; create?.let { put("create", it) }
        }
    }
    fun num(path: String, label: String, v: Double?, hint: String? = null, blank: Double? = null, decimals: Int = 2) {
        items += buildJsonObject { put("kind", "num"); put("path", path); put("label", label); put("value", v?.let { ValueFormat.number(it, decimals).replace(",", "") } ?: ""); hint?.let { put("hint", it) }; blank?.let { put("blank", it) } }
    }
    fun text(path: String, label: String, v: String) { items += buildJsonObject { put("kind", "text"); put("path", path); put("label", label); put("value", v) } }
    fun bool(path: String, label: String, v: Boolean) { items += buildJsonObject { put("kind", "bool"); put("path", path); put("label", label); put("checked", v) } }
    fun choice(label: String, value: String?, options: List<Pair<String, String>>, op: JsonObject, placeholder: String = "Choose…") {
        items += buildJsonObject {
            put("kind", "choice"); put("label", label); put("value", value ?: ""); put("placeholder", placeholder)
            putJsonArray("options") { options.forEach { (i, l) -> addJsonObject { put("id", i); put("label", l) } } }
            put("op", op)
        }
    }
    fun setOp(path: String) = buildJsonObject { put("op", "set"); put("path", path) }
    fun button(label: String, op: JsonObject, danger: Boolean = false) { items += buildJsonObject { put("kind", "button"); put("label", label); put("op", op); put("danger", danger) } }
    fun op(name: String, vararg kv: Pair<String, Any>) = buildJsonObject { put("op", name); kv.forEach { (k, v) -> when (v) { is Int -> put(k, v); is Boolean -> put(k, v); else -> put(k, v.toString()) } } }

    private val vesselCreate = buildJsonObject { put("diameter", 0.0); put("length", 0.0) }

    fun relief(d: ReliefDeviceDef, results: ResultView, vessels: List<SimObject>) {
        note("API 521 relief loads and API 520 sizing, with the inlet-loss and back-pressure checks of API 520 Part II.")
        text("tag", "Tag", d.tag)
        choice("Protects", d.protectedId, vessels.map { it.id to "${it.tag} · ${it.type.displayName()}" }, setOp("protectedId"))
        q("setPressure", "Set pressure", d.setPressure, Quantity.Pressure)
        q("backPressure", "Back pressure (superimposed)", d.backPressure, Quantity.Pressure, "atmospheric", blank = 101325.0)
        choice("Valve", d.design, ValveDesigns.ALL, setOp("design"))
        if (d.design == ValveDesigns.BALANCED_BELLOWS) num("kb", "Kb / Kw (from the valve maker)", d.kb, "1", blank = 1.0)
        bool("ruptureDisk", "Rupture disk upstream (Kc 0.9)", d.ruptureDisk)
        label("Vessel")
        geometry(d.protectedId, d.vessel)
        label("Relief scenarios")
        d.scenarios.forEachIndexed { i, s -> scenario(i, s) }
        val suggested = Safety.newSuggestions(fs, results, d)
        if (suggested.isNotEmpty()) {
            label("Suggested from the flowsheet")
            suggested.forEachIndexed { i, s ->
                note("${s.scenario.name ?: ReliefScenarioKinds.label(s.scenario.kind)}: ${s.reason}")
                button("Add ${s.scenario.name ?: ReliefScenarioKinds.label(s.scenario.kind)}", op("addSuggestion", "index" to i))
            }
        }
        choice("Add a scenario", null, ReliefScenarioKinds.ALL.map { it to ReliefScenarioKinds.label(it) }, op("addScenario"))
        label("Inlet line (vessel to valve)")
        line("inletLine", d.inletLine)
        label("Outlet line (valve to discharge)")
        line("outletLine", d.outletLine)
        Safety.problem(fs, results, d)?.let { note(it, "warn") }
        items += buildJsonObject { put("kind", "run"); put("label", if (d.result == null) "Size" else "Size again"); put("ready", Safety.problem(fs, results, d) == null); put("working", "Sizing…") }
    }

    private fun scenario(i: Int, s: ReliefScenario) {
        val p = "scenarios.$i"
        items += buildJsonObject { put("kind", "subhead"); put("text", s.name ?: ReliefScenarioKinds.label(s.kind)); put("op", op("removeScenario", "index" to i)) }
        when (s.kind) {
            ReliefScenarioKinds.FIRE -> {
                num("$p.environmentFactor", "Environment factor F", s.environmentFactor, "1 (bare vessel)", blank = 1.0)
                bool("$p.adequateDrainage", "Adequate drainage and fire-fighting", s.adequateDrainage)
                num("$p.area", "Wetted area, m²", s.area, "from the vessel")
            }
            ReliefScenarioKinds.BLOCKED_OUTLET, ReliefScenarioKinds.CUSTOM -> {
                q("$p.massFlow", "Relief flow", s.massFlow, Quantity.MassFlow, if (s.kind == ReliefScenarioKinds.BLOCKED_OUTLET) "the vessel's feed" else null)
                q("$p.temperature", "Fluid temperature", s.temperature, Quantity.Temperature, if (s.kind == ReliefScenarioKinds.BLOCKED_OUTLET) "the feed's" else null)
            }
            ReliefScenarioKinds.CONTROL_VALVE_FAILURE -> {
                q("$p.upstreamPressure", "Upstream pressure", s.upstreamPressure, Quantity.Pressure)
                q("$p.temperature", "Upstream temperature", s.temperature, Quantity.Temperature)
                num("$p.ratedCv", "Rated Cv of the valve (fully open)", s.ratedCv)
                note("The vessel's own fluid unless the upstream one differs; no credit is taken for normal outflow.")
            }
            ReliefScenarioKinds.THERMAL_EXPANSION -> {
                q("$p.heatInput", "Heat into the blocked-in liquid", s.heatInput, Quantity.Power)
                q("$p.temperature", "Liquid temperature", s.temperature, Quantity.Temperature, "operating")
            }
        }
    }

    /** A vessel's dimensions to type, or a pipe segment's own (the app's GeometryFields). */
    private fun geometry(protectedId: String?, v: app.flowstead.core.api.VesselGeometry?, path: String = "vessel") {
        val pipe = fs.objects.firstOrNull { it.id == protectedId && it.type == ObjectType.PipeSegment }
        if (pipe == null) {
            q("$path.diameter", "Diameter", v?.diameter?.takeIf { it > 0 }, Quantity.Length, blank = 0.0, create = vesselCreate)
            q("$path.length", "Length T/T", v?.length?.takeIf { it > 0 }, Quantity.Length, blank = 0.0, create = vesselCreate)
            choice("Orientation", if (v?.horizontal == true) "true" else "false", listOf("false" to "Vertical", "true" to "Horizontal"), buildJsonObject { put("op", "set"); put("path", "$path.horizontal"); put("bool", true); put("create", vesselCreate) })
            q("$path.liquidLevel", "Liquid level", v?.liquidLevel ?: 0.0, Quantity.Length, "0 (gas-filled)", blank = 0.0, create = vesselCreate)
            if (path == "vessel") q("$path.elevation", "Bottom above grade", v?.elevation ?: 0.0, Quantity.Length, "0", blank = 0.0, create = vesselCreate)
            return
        }
        val g = Safety.pipeGeometry(pipe)
        if (g == null) { note("Give ${pipe.tag} its inside diameter and length (in its worksheet): the pipe's volume and surface come from them.", "warn"); return }
        val (geo, wall) = g
        note("Pipe ${pipe.tag}: inside ${ValueFormat.number(geo.diameter * 1000, 1)} mm × ${ValueFormat.number(geo.length, 1)} m, volume ${ValueFormat.number(PI * geo.diameter * geo.diameter / 4 * geo.length, 3)} m³" +
            (wall?.let { ", wall ${ValueFormat.number(it * 1000, 2)} mm" } ?: " (wall from ASME pressure design: set the pipe size to use its schedule)") + ".")
    }

    private fun line(path: String, l: ReliefLine?) {
        val match = l?.let { x -> PipeSizes.ALL.firstNotNullOfOrNull { s -> s.schedules.firstOrNull { abs(s.insideDiameterM(it) - x.insideDiameter) < 1e-6 }?.let { s to it } } }
        choice("Nominal size", match?.first?.nps ?: if (l == null) "" else "custom", listOf("" to "Not checked") + (if (l != null && match == null) listOf("custom" to "Custom") else emptyList()) + PipeSizes.ALL.map { it.nps to it.label },
            op("lineSize", "path" to path))
        if (l == null) return
        match?.let { (size, sch) -> choice("Schedule", sch.label, size.schedules.map { it.label to it.label }, op("lineSchedule", "path" to path)) }
        q("$path.length", "Length", l.length, Quantity.Length)
        q("$path.fittingsLength", "Fittings (equivalent length)", l.fittingsLength, Quantity.Length, "0", blank = 0.0)
    }

    fun blowdown(c: DepressuringCase, results: ResultView, vessels: List<SimObject>) {
        note("Blowdown through a restriction orifice to the flare or atmosphere (API 521): pressure, fluid and wall temperatures, and flow against time.")
        text("name", "Name", c.name)
        choice("Vessel or pipe", c.protectedId, vessels.map { it.id to "${it.tag} · ${it.type.displayName()}" }, setOp("protectedId"))
        note("Starts from its operating pressure, temperature and contents (its feed, from the last solve).")
        geometry(c.protectedId, c.vessel)
        q("backPressure", "Back pressure", c.backPressure, Quantity.Pressure, "atmospheric", blank = 101325.0)
        q("designPressure", "Design pressure", c.designPressure, Quantity.Pressure, "half the starting pressure if empty")
        note("API 521 blows down to the lower of 6.9 barg and half the design gauge pressure in about 15 minutes; the design pressure also sets the wall (ASME VIII-1) for the wall temperatures.")
        q("orificeDiameter", "Restriction orifice diameter", c.orificeDiameter, Quantity.Length, "sized for 15 min if empty", small = true)
        bool("withFire", "Fire during depressuring (API 521 heat input)", c.withFire)
        note("Two zones (vapour and liquid, not in equilibrium) and a wall conducting through its thickness, wet and dry sections apart.")
        q("heatTransferCoefficient", "Inside heat transfer coefficient", c.heatTransferCoefficient, Quantity.HeatTransferCoefficient, "correlations if empty; 0 adiabatic")
        q("ambientTemperature", "Ambient temperature", c.ambientTemperature, Quantity.Temperature, "the starting temperature")
        val problem = Safety.depressuringProblem(fs, results, c)
        problem?.let { note(it, "warn") }
        items += buildJsonObject { put("kind", "run"); put("label", if (c.result == null) "Run" else "Run again"); put("ready", problem == null); put("working", "Blowing down…") }
    }

    fun fill(c: PressurizationCase, results: ResultView) {
        note("Time to pressurize a pipeline, a network of pipelines or a vessel through a control valve or orifice, as HYSYS's pipeline pressurization: from a header at constant pressure, or from a vessel or pipe that empties until the two sides equalise. Pressure, gas and wall temperatures and flow against time.")
        text("name", "Name", c.name)
        val vesselSource = c.source == PressurizationSources.VESSEL
        choice("Source", c.source, listOf(PressurizationSources.HEADER to "A header: constant pressure (a stream)", PressurizationSources.VESSEL to "A vessel or pipe: a fixed volume that empties"), setOp("source"))
        val holders = fs.objects.filter { it.type in Safety.PROTECTABLE }
        if (!vesselSource) {
            val streams = fs.objects.filter { it.type == ObjectType.MaterialStream }
            choice("Supply stream", c.supplyId, streams.map { it.id to it.tag }, setOp("supplyId"))
            note("The supply's pressure, temperature and composition come from the last solve; it holds steady while the line fills.")
        } else {
            choice("Source vessel or pipe", c.sourceId, holders.map { it.id to "${it.tag} · ${it.type.displayName()}" }, setOp("sourceId"))
            note("Its contents (composition, temperature, pressure) come from the last solve; it empties into what is filled, falling in pressure, until the two equalise.")
            geometry(c.sourceId, c.sourceVessel, "sourceVessel")
            q("sourcePressure", "Starting pressure", c.sourcePressure, Quantity.Pressure, "as solved")
            bool("sourceAdiabatic", "Adiabatic source (no heat from its wall: the coldest case)", c.sourceAdiabatic)
        }
        choice("Fill", c.fillId ?: "", listOf("" to "Pipes (the network below)") + holders.filter { it.type != ObjectType.PipeSegment && it.id != c.sourceId }.map { it.id to "${it.tag} · ${it.type.displayName()}" },
            op("fill"))
        if (c.fillId != null) geometry(c.fillId, c.fillVessel, "fillVessel")
        else label("Pipeline (one network at one pressure)")
        if (c.fillId == null) fs.objects.filter { it.type == ObjectType.PipeSegment && it.id != c.sourceId }.forEach { p ->
            items += buildJsonObject {
                put("kind", "check"); put("checked", p.id in c.pipeIds)
                put("label", p.tag + (Safety.pipeGeometry(p)?.first?.let { g -> " · ${ValueFormat.number(g.diameter * 1000, 1)} mm × ${ValueFormat.number(g.length, 1)} m" } ?: " · no size yet"))
                put("op", op("pipe", "value" to p.id))
            }
        }
        if (c.fillId == null) {
            c.sections.forEachIndexed { i, s ->
                items += buildJsonObject { put("kind", "subhead"); put("text", "Section ${i + 1}"); put("op", op("removeSection", "index" to i)) }
                q("sections.$i.insideDiameter", "Inside diameter", s.insideDiameter, Quantity.Length, small = true)
                q("sections.$i.length", "Length", s.length, Quantity.Length)
            }
            button("Add a pipe section by hand", op("addSection"))
        }
        Safety.filled(fs, c)?.let { (g, _) -> note("Volume filled ${ValueFormat.number(Safety.volume(g), 3)} m³.") }
        label("Starting state")
        q("initialPressure", "Pressure", c.initialPressure, Quantity.Pressure, "atmospheric", blank = 101325.0)
        q("initialTemperature", "Temperature", c.initialTemperature, Quantity.Temperature, if (vesselSource) "the source's" else "the supply's")
        note("What is filled starts holding the ${if (vesselSource) "source's" else "supply"} gas at that pressure.")
        label("Inlet")
        choice("Through", c.valve, listOf(PressurizationValves.FLOWSHEET to "A valve or orifice on the flowsheet", PressurizationValves.CV to "Control valve (Cv)", PressurizationValves.ORIFICE to "Restriction orifice", PressurizationValves.SIZE to "Orifice sized to the target time"), setOp("valve"))
        when (c.valve) {
            PressurizationValves.FLOWSHEET -> {
                choice("Valve or orifice", c.valveId, Safety.valveChoices(fs).map { it.id to "${it.tag} · ${it.type.displayName()}" }, setOp("valveId"))
                Safety.flowsheetValve(fs, results, c.valveId)?.let { v ->
                    when {
                        v.orificeDiameter != null -> note("${v.tag}: its orifice, ${ValueFormat.number(v.orificeDiameter * 1000, 1)} mm.")
                        v.ratedCv -> { note("${v.tag}: its rated Cv ${ValueFormat.number(v.cv!!, 1)}, xT ${ValueFormat.number(v.xt, 2)}."); q("opening", "Opening", c.opening, Quantity.Fraction, "100 %", blank = 1.0) }
                        else -> note("${v.tag}: Cv ${ValueFormat.number(v.cv!!, 1)}, the Cv it needs in the solve (as it runs), xT ${ValueFormat.number(v.xt, 2)}. Give it a rated Cv to use that instead.")
                    }
                }
            }
            PressurizationValves.CV -> {
                num("cv", "Rated Cv", c.cv)
                q("opening", "Opening", c.opening, Quantity.Fraction, "100 %", blank = 1.0)
                num("valveXt", "xT (IEC 60534)", c.valveXt, "0.72 globe", blank = 0.72)
            }
            PressurizationValves.ORIFICE -> q("orificeDiameter", "Orifice diameter", c.orificeDiameter, Quantity.Length, small = true)
        }
        label("Target")
        q("targetPressure", "Pressure", c.targetPressure, Quantity.Pressure, if (vesselSource) "where the two sides equalise" else "95 % of the supply")
        q("targetTime", "In (time)", c.targetTime, Quantity.Time, "1 h", blank = 3600.0)
        val problem = Safety.pressurizationProblem(fs, results, c)
        problem?.let { note(it, "warn") }
        items += buildJsonObject { put("kind", "run"); put("label", if (c.result == null) "Run" else "Run again"); put("ready", problem == null); put("working", "Filling the line…") }
    }
}
