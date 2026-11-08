/*
 * Flowstead web (D112): the app's analysis tools in the browser. For every tool the app's core builds the engine
 * request and turns the engine's answer into plots and tables, as the app's sheets do (EnvelopeSection, PvtSheet,
 * PetroleumSection, CaseStudySheet, ToolsSheet, SafetySheet, BlackOilSheet); the page only shows them. Every number
 * shown is one the engine returned, in the web's display units (°C, bara, SI).
 *
 * A tool is two calls: request(...) gives {method, params} for the engine worker, or {error}; report(...) turns the
 * engine's result into {sections: [...]}: note, kv (label/value/unit rows), table (columns, rows), plot (axes in
 * display units, series, markers).
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:OptIn(ExperimentalJsExport::class)

package app.flowstead.web

import app.flowstead.core.api.DistillationCurveKinds
import app.flowstead.core.api.HydrateInhibitors
import app.flowstead.core.api.PetroleumKeys
import app.flowstead.core.api.PetroleumPropertiesRequest
import app.flowstead.core.api.PetroleumPropertiesResult
import app.flowstead.core.api.PhaseEnvelope
import app.flowstead.core.api.PhaseEnvelopeRequest
import app.flowstead.core.api.Phases
import app.flowstead.core.api.PvtExperiments
import app.flowstead.core.api.PvtRequest
import app.flowstead.core.api.PvtResult
import app.flowstead.core.api.SeparatorStage
import app.flowstead.core.api.SolveResult
import app.flowstead.core.format.ValueFormat
import app.flowstead.core.api.OptimiseRequest
import app.flowstead.core.api.OptimiseResult
import app.flowstead.core.api.OptimiserTarget
import app.flowstead.core.api.OptimiserVariable
import app.flowstead.core.model.BlackOil
import app.flowstead.core.model.CaseOutput
import app.flowstead.core.model.EnvelopeNotes
import app.flowstead.core.model.CaseResults
import app.flowstead.core.model.CaseStudies
import app.flowstead.core.model.CaseStudy
import app.flowstead.core.model.FlowsheetEditing
import app.flowstead.core.model.Readiness
import app.flowstead.core.model.ResultKeys
import app.flowstead.core.model.SpecKey
import app.flowstead.core.model.Specification
import app.flowstead.core.model.EditRejected
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.FlowsheetJson
import app.flowstead.core.model.Plot
import app.flowstead.core.model.Plots
import app.flowstead.core.model.PvtTables
import app.flowstead.core.model.isFeed
import app.flowstead.core.model.modelHash
import app.flowstead.core.model.obj
import app.flowstead.core.units.EngUnit
import app.flowstead.core.units.Quantity
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.Units
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Builds a report: the sections the page draws, in order. */
internal class Report(val ctx: UnitContext) {
    val sections = mutableListOf<JsonElement>()
    fun heading(text: String) { sections += buildJsonObject { put("kind", "heading"); put("text", text) } }
    fun note(text: String, level: String = "info") { sections += buildJsonObject { put("kind", "note"); put("text", text); put("level", level) } }
    fun kv(rows: List<Triple<String, String, String>>) {
        if (rows.isEmpty()) return
        sections += buildJsonObject {
            put("kind", "kv")
            putJsonArray("rows") { rows.forEach { (l, v, u) -> addJsonObject { put("label", l); put("value", v); put("unit", u) } } }
        }
    }
    fun table(columns: List<String>, rows: List<List<String>>) {
        if (columns.isEmpty()) return
        sections += buildJsonObject {
            put("kind", "table")
            putJsonArray("columns") { columns.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("rows") { rows.forEach { r -> addJsonArray { r.forEach { add(JsonPrimitive(it)) } } } }
        }
    }
    /** A plot from the core's [Plot], every point converted from SI to the display unit of its axis. */
    fun plot(p: Plot) {
        val xu = unitOf(p.xQuantity); val yu = unitOf(p.yQuantity)
        sections += buildJsonObject {
            put("kind", "plot"); put("title", p.title)
            put("xLabel", p.xLabel + sym(xu)); put("yLabel", p.yLabel + sym(yu))
            putJsonArray("series") {
                p.series.forEach { s ->
                    addJsonObject {
                        put("label", s.label + (s.parameter?.let { v -> p.parameterQuantity?.let { q -> " = " + value(v, q) } ?: "" } ?: ""))
                        put("dashed", s.dashed)
                        putJsonArray("points") { s.x.indices.forEach { i -> addJsonArray { add(JsonPrimitive(xu.fromSi(s.x[i], ctx))); add(JsonPrimitive(yu.fromSi(s.y[i], ctx))) } } }
                    }
                }
            }
            putJsonArray("markers") { p.markers.forEach { m -> addJsonObject { put("label", m.label); put("x", xu.fromSi(m.x, ctx)); put("y", yu.fromSi(m.y, ctx)) } } }
        }
    }
    private fun sym(u: EngUnit) = if (u.symbol.isBlank()) "" else ", ${u.symbol}"
    fun unitOf(q: Quantity): EngUnit = displayUnits.unitFor(q)
    fun value(si: Double, q: Quantity, decimals: Int? = null): String { val u = unitOf(q); return ValueFormat.number(u.fromSi(si, ctx), decimals ?: u.decimals) }
    fun withUnit(si: Double, q: Quantity, decimals: Int? = null): String = (value(si, q, decimals) + " " + unitOf(q).symbol).trimEnd()
    fun json(): String = buildJsonObject { put("sections", JsonArray(sections)) }.toString()
}

/** A value the page sends as typed: {value: "85", unit: "C"}; null when blank. */
internal fun siOf(e: JsonElement?, ctx: UnitContext): Double? {
    val o = e as? JsonObject ?: return null
    val text = o["value"]?.jsonPrimitive?.content?.trim()?.replace(',', '.').orEmpty()
    if (text.isEmpty()) return null
    val v = text.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw EditRejected("Not a number: $text")
    val u = Units.byIdOrNull(o["unit"]?.jsonPrimitive?.content ?: "") ?: throw EditRejected("Unknown unit")
    return u.toSi(v, ctx)
}

@JsExport
object Tools {
    private fun fs(json: String): Flowsheet = FlowsheetJson.decodeFromString(Flowsheet.serializer(), json)
    private fun solve(json: String?): SolveResult? = json?.takeIf { it.isNotBlank() && it != "null" }?.let { FlowsheetJson.decodeFromString(SolveResult.serializer(), it) }
    private fun params(json: String): JsonObject = FlowsheetJson.parseToJsonElement(json.ifBlank { "{}" }).jsonObject
    private fun err(message: String) = buildJsonObject { put("error", message) }.toString()
    private fun call(method: String, params: JsonElement) = buildJsonObject { put("method", method); put("params", params) }.toString()
    private inline fun <reified T> enc(serializer: kotlinx.serialization.KSerializer<T>, v: T): JsonElement = FlowsheetJson.encodeToJsonElement(serializer, v)

    /**
     * The stream's composition, as the app's FlowsheetViewModel.streamComposition: the solved overall composition when the
     * results are current, else a feed's own; with the reason when there is none.
     */
    private fun composition(f: Flowsheet, result: SolveResult?, solvedHash: String, streamId: String): Map<String, Double> {
        val stream = f.obj(streamId) ?: throw EditRejected("That stream no longer exists")
        if (f.propertyPackage == null) throw EditRejected("Choose a fluid package first")
        val solved = result?.takeIf { solvedHash == f.modelHash() }?.objects?.get(streamId)?.phases?.get(Phases.OVERALL)?.composition
        val c = solved ?: stream.composition.takeIf { f.isFeed(stream) && it.values.sum() > 0 }
        if (c.isNullOrEmpty()) throw EditRejected("Solve first: the stream's composition comes from the solve")
        return c
    }

    private fun property(f: Flowsheet, result: SolveResult?, solvedHash: String, id: String, key: String): Double? =
        result?.takeIf { solvedHash == f.modelHash() }?.objects?.get(id)?.properties?.get(key)

    /** Inputs a tool offers before it runs (temperatures prefilled from the stream, as the app's sheets do). */
    fun defaults(tool: String, flowsheetJson: String, solveJson: String?, solvedHash: String, objectId: String): String {
        val f = fs(flowsheetJson)
        val r = solve(solveJson)
        val ctx = UnitContext(f.atmosphericPressurePa)
        val t = displayUnits.unitFor(Quantity.Temperature)
        return buildJsonObject {
            when (tool) {
                "pvt" -> {
                    val st = property(f, r, solvedHash, objectId, "temperature") ?: f.obj(objectId)?.specs?.get(app.flowstead.core.model.SpecKey.TEMPERATURE)?.si
                    put("temperature", st?.let { ValueFormat.number(t.fromSi(it, ctx), t.decimals).replace(",", "") } ?: "")
                    put("temperatureUnit", t.id)
                }
            }
            putJsonArray("inhibitors") { HydrateInhibitors.ALL.forEach { addJsonObject { put("id", it); put("label", HydrateInhibitors.label(it)) } } }
            putJsonArray("experiments") {
                listOf(PvtExperiments.SATURATION, PvtExperiments.CCE, PvtExperiments.CVD, PvtExperiments.DL, PvtExperiments.SEPARATOR)
                    .forEach { addJsonObject { put("id", it); put("label", PvtTables.experimentLabel(it)) } }
            }
        }.toString()
    }

    /** The engine call for [tool] on object [objectId] with the page's inputs [paramsJson]. */
    fun request(tool: String, flowsheetJson: String, solveJson: String?, solvedHash: String, objectId: String, paramsJson: String): String = try {
        val f = fs(flowsheetJson)
        val r = solve(solveJson)
        val p = params(paramsJson)
        val ctx = UnitContext(f.atmosphericPressurePa)
        when (tool) {
            // As FlowsheetViewModel.phaseEnvelope (D50, D52): quality lines, the hydrate curve, an inhibitor in the free water.
            "envelope" -> {
                val qualities = p["qualities"]?.jsonArray?.mapNotNull { it.jsonPrimitive.doubleOrNull }.orEmpty()
                val hydrates = p["hydrates"]?.jsonPrimitive?.booleanOrNull ?: true
                val inhibitor = p["inhibitor"]?.jsonPrimitive?.content?.takeIf { it in HydrateInhibitors.ALL }
                val pct = p["inhibitorPercent"]?.jsonPrimitive?.content?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 && it < 100 }
                val fraction = if (inhibitor == null) 0.0 else (pct ?: 0.0) / 100
                val req = PhaseEnvelopeRequest(
                    f, composition(f, r, solvedHash, objectId), qualities, hydrates, property(f, r, solvedHash, objectId, "pressure"),
                    property(f, r, solvedHash, objectId, "temperature"), inhibitor.takeIf { fraction > 0 }, fraction,
                )
                call("phaseEnvelope", enc(PhaseEnvelopeRequest.serializer(), req))
            }
            // As the app's PvtSheet (D53): the experiment at reservoir temperature; separator stages for the separator test.
            "pvt" -> {
                val experiment = p["experiment"]?.jsonPrimitive?.content ?: PvtExperiments.CCE
                val t = siOf(p["temperature"], ctx) ?: throw EditRejected("Give the reservoir temperature")
                val stages = p["stages"]?.jsonArray?.map { s ->
                    val o = s.jsonObject
                    SeparatorStage(siOf(o["temperature"], ctx) ?: throw EditRejected("Give every stage's temperature"), siOf(o["pressure"], ctx) ?: throw EditRejected("Give every stage's pressure"))
                }.orEmpty()
                if (experiment == PvtExperiments.SEPARATOR && stages.isEmpty()) throw EditRejected("Add a separator stage")
                call("pvt", enc(PvtRequest.serializer(), PvtRequest(f, composition(f, r, solvedHash, objectId), experiment, t, stages = stages)))
            }
            // As FlowsheetViewModel.petroleumProperties (D99).
            "petroleum" -> call("petroleumProperties", enc(PetroleumPropertiesRequest.serializer(), PetroleumPropertiesRequest(f, composition(f, r, solvedHash, objectId))))
            else -> err("Unknown tool")
        }
    } catch (e: IllegalArgumentException) { err(e.message ?: "Not possible") }

    /** The report of the engine's [resultJson] for [tool], as the app's sheet shows it. */
    fun report(tool: String, flowsheetJson: String, solveJson: String?, solvedHash: String, objectId: String, paramsJson: String, resultJson: String): String {
        val f = fs(flowsheetJson)
        val r = solve(solveJson)
        val rep = Report(UnitContext(f.atmosphericPressurePa))
        when (tool) {
            "envelope" -> envelope(rep, FlowsheetJson.decodeFromString(PhaseEnvelope.serializer(), resultJson), f, r, solvedHash, objectId, params(paramsJson))
            "pvt" -> pvt(rep, FlowsheetJson.decodeFromString(PvtResult.serializer(), resultJson))
            "petroleum" -> petroleum(rep, FlowsheetJson.decodeFromString(PetroleumPropertiesResult.serializer(), resultJson))
        }
        return rep.json()
    }

    private fun envelope(rep: Report, env: PhaseEnvelope, f: Flowsheet, r: SolveResult?, solvedHash: String, id: String, p: JsonObject) {
        val plot = Plots.envelope(env, property(f, r, solvedHash, id, "temperature"), property(f, r, solvedHash, id, "pressure"))
        if (plot == null) rep.note("DWSIM traced no bubble or dew curve for this composition.") else rep.plot(plot)
        // A narrow envelope squeezed by the hydrate curve and the water dew line, close up (D119).
        Plots.envelopeCloseUp(env, property(f, r, solvedHash, id, "temperature"), property(f, r, solvedHash, id, "pressure"))?.let { rep.plot(it) }
        val rows = mutableListOf<Triple<String, String, String>>()
        fun row(label: String, t: Double?, pr: Double?) {
            if (t == null || pr == null) return
            rows += Triple(label, "${rep.withUnit(pr, Quantity.Pressure)} at ${rep.withUnit(t, Quantity.Temperature)}", "")
        }
        row("Cricondenbar", env.cricondenbarTemperature, env.cricondenbarPressure)
        row("Cricondentherm", env.cricondenthermTemperature, env.cricondenthermPressure)
        row("Critical point", env.criticalTemperature, env.criticalPressure)
        rep.kv(rows)
        env.hydrateTemperatureAtPressure?.let { th ->
            rep.note("Hydrates (${env.hydrateStructureAtPressure ?: "?"}) form below ${rep.withUnit(th, Quantity.Temperature)} at this stream's pressure", "warn")
        }
        // A wet gas (D118): where its free water drops out; what the inhibitor does, and where its water freezes (D119).
        val tFmt = { k: Double -> rep.withUnit(k, Quantity.Temperature) }
        EnvelopeNotes.waterDew(env, property(f, r, solvedHash, id, "temperature"), tFmt)?.let { rep.note(it) }
        EnvelopeNotes.inhibitor(env, tFmt)?.let { rep.note(it) }
        env.requiredInhibitorFraction?.let { w ->
            val what = HydrateInhibitors.label(p["inhibitor"]?.jsonPrimitive?.content?.takeIf { it in HydrateInhibitors.ALL } ?: HydrateInhibitors.METHANOL)
            rep.note(if (w <= 0) "This stream is warmer than its hydrate temperature: no inhibitor needed."
                else "To stay out of hydrates at this stream's temperature, its free water needs ${ValueFormat.number(w * 100, 1)} wt % $what.")
        }
        env.warnings.forEach { rep.note(it, "warn") }
    }

    private fun pvt(rep: Report, r: PvtResult) {
        rep.heading(PvtTables.experimentLabel(r.experiment))
        fun show(key: String, v: Double): String {
            val q = PvtTables.quantity(key)
            return rep.withUnit(v, q, if (q == Quantity.Ratio) 4 else null)
        }
        r.saturationPressure?.let {
            rep.kv(listOf(Triple(if (r.saturationKind == "dew") "Dew point (gas condensate)" else "Bubble point (oil)", rep.value(it, Quantity.Pressure), rep.unitOf(Quantity.Pressure).symbol)))
        }
        rep.kv(r.summary.filterKeys { it != "saturationPressure" }.map { (k, v) -> Triple(PvtTables.label(k), show(k, v), "") })
        r.warnings.forEach { rep.note(it, "warn") }
        PvtTables.plots(r).forEach { rep.plot(it) }
        if (r.columns.isEmpty()) return
        val n = r.table.values.firstOrNull()?.size ?: 0
        rep.table(
            r.columns.map { k -> val q = PvtTables.quantity(k); val u = rep.unitOf(q); PvtTables.label(k) + if (u.symbol.isNotBlank() && q != Quantity.Ratio) ", ${u.symbol}" else "" },
            (0 until n).map { i -> r.columns.map { k -> r.table[k]?.getOrNull(i)?.let { v -> val q = PvtTables.quantity(k); rep.value(v, q, if (q == Quantity.Ratio) 4 else null) } ?: "—" } },
        )
    }

    private fun petroleum(rep: Report, r: PetroleumPropertiesResult) {
        val t = rep.unitOf(Quantity.Temperature)
        val curves = listOf(DistillationCurveKinds.TBP, DistillationCurveKinds.D86, DistillationCurveKinds.D2887, DistillationCurveKinds.D1160, DistillationCurveKinds.EFV)
            .mapNotNull { k -> r.curves.firstOrNull { it.kind == k } }
        val percents = curves.flatMap { c -> c.points.map { it.percent } }.distinct().sorted()
        rep.heading("Distillation curves (${t.symbol})")
        rep.table(listOf("%") + curves.map { DistillationCurveKinds.label(it.kind).removeSuffix(" (simulated)") },
            percents.map { pc -> listOf(ValueFormat.number(pc, 0)) + curves.map { c -> c.points.firstOrNull { it.percent == pc }?.value?.let { v -> rep.value(v, Quantity.Temperature) } ?: "—" } })
        if (curves.isNotEmpty()) rep.plot(Plot("Distillation curves", "Distilled", Quantity.Ratio, "Temperature", Quantity.Temperature,
            curves.map { c -> app.flowstead.core.model.PlotSeries(DistillationCurveKinds.label(c.kind).removeSuffix(" (simulated)"), c.points.map { it.percent }, c.points.map { it.value }) }))
        rep.note("TBP on liquid volume; D86 and D1160 liquid volume, D1160 at ${curves.firstOrNull { it.kind == DistillationCurveKinds.D1160 }?.let { ValueFormat.number(it.pressure / 133.322, 0) } ?: "10"} mmHg; D2887 weight %; EFV at 1 atm. Conversions by the API Technical Data Book (3A1.1, 3A2.1, 3A3.1).")
        val rows = PetroleumKeys.ROWS.mapNotNull { (key, label, kind) ->
            val v = r.properties[key] ?: return@mapNotNull null
            when (kind) {
                PetroleumKeys.Kind.Temperature -> Triple(label, rep.value(v, Quantity.Temperature), t.symbol)
                PetroleumKeys.Kind.Pressure -> Triple(label, rep.value(v, Quantity.Pressure), rep.unitOf(Quantity.Pressure).symbol)
                PetroleumKeys.Kind.Density -> Triple(label, rep.value(v, Quantity.Density), rep.unitOf(Quantity.Density).symbol)
                PetroleumKeys.Kind.KinematicViscosity -> Triple(label, rep.value(v, Quantity.KinematicViscosity), rep.unitOf(Quantity.KinematicViscosity).symbol)
                PetroleumKeys.Kind.Length -> Triple(label, ValueFormat.number(Units.mm.fromSi(v), Units.mm.decimals), "mm")
                PetroleumKeys.Kind.Number -> Triple(label, ValueFormat.number(v, when (key) {
                    PetroleumKeys.SPECIFIC_GRAVITY, PetroleumKeys.REFRACTIVE -> 4
                    PetroleumKeys.API, PetroleumKeys.CETANE, PetroleumKeys.CETANE_D4737, PetroleumKeys.MOLECULAR_WEIGHT -> 1
                    else -> 2 }), "")
            }
        }
        rep.heading("Product properties"); rep.kv(rows)
        r.warnings.forEach { rep.note(it, "warn") }
        rep.note("Product properties are API correlations of the curves, SG and Watson K (pour point 2B8.1, flash point 2B7.1, cetane index 2B13.1); they are estimates, best for petroleum fractions characterised from an assay.")
    }

    // ---------- what a flowsheet tool can vary and record ----------

    /**
     * Every object with the specifications it has (what a case study or the optimiser can vary, in each one's unit) and
     * the results it can give (what they can record), as the app's pickers list them.
     */
    fun catalogue(flowsheetJson: String): String {
        val f = fs(flowsheetJson)
        val ctx = UnitContext(f.atmosphericPressurePa)
        return buildJsonArray {
            f.objects.forEach { o ->
                addJsonObject {
                    put("id", o.id); put("tag", o.tag)
                    putJsonArray("specs") {
                        o.specs.forEach { (k, sp) ->
                            val q = runCatching { SpecKey.quantity(k) }.getOrNull() ?: return@forEach
                            val u = Units.byIdOrNull(sp.unit)?.takeIf { it.quantity == q } ?: displayUnits.unitFor(q)
                            addJsonObject {
                                put("key", k); put("label", SpecKey.label(k)); put("unit", u.id); put("symbol", u.symbol)
                                put("value", ValueFormat.number(u.fromSi(sp.si, ctx), u.decimals).replace(",", ""))
                            }
                        }
                    }
                    putJsonArray("results") {
                        val keys = if (o.type == app.flowstead.core.model.ObjectType.MaterialStream) ResultKeys.STREAM else ResultKeys.forObject(f, o)
                        keys.forEach { k -> addJsonObject { put("key", k); put("label", ResultKeys.label(k)); put("unit", displayUnits.unitFor(ResultKeys.quantityFor(o, k)).id); put("symbol", displayUnits.unitFor(ResultKeys.quantityFor(o, k)).symbol) } }
                    }
                }
            }
        }.toString()
    }

    // ---------- case studies (D51): as FlowsheetViewModel.runCaseStudy, one full solve per point ----------

    /** The study the page describes, in SI: {name, objectId, specKey, from, to, steps, outputs, second?}. */
    private fun study(f: Flowsheet, p: JsonObject, ctx: UnitContext): CaseStudy {
        fun str(k: String, o: JsonObject = p) = o[k]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
        val second = p["second"] as? JsonObject
        return CaseStudy(
            id = str("id") ?: "web", name = str("name") ?: "Case study",
            objectId = str("objectId"), specKey = str("specKey"), from = siOf(p["from"], ctx), to = siOf(p["to"], ctx),
            steps = str("steps")?.toIntOrNull() ?: 10,
            outputs = p["outputs"]?.jsonArray?.map { o -> CaseOutput(o.jsonObject["objectId"]!!.jsonPrimitive.content, o.jsonObject["key"]!!.jsonPrimitive.content) }.orEmpty(),
            objectId2 = second?.let { str("objectId", it) }, specKey2 = second?.let { str("specKey", it) },
            from2 = second?.let { siOf(it["from"], ctx) }, to2 = second?.let { siOf(it["to"], ctx) }, steps2 = second?.let { str("steps", it)?.toIntOrNull() } ?: 4,
        )
    }

    /** The flowsheets to solve, point by point ({points: [flowsheet...]}), or {error} with what stops the study. */
    fun caseStudyPoints(flowsheetJson: String, studyJson: String): String = try {
        val f = fs(flowsheetJson)
        val cs = study(f, params(studyJson), UnitContext(f.atmosphericPressurePa))
        CaseStudies.problem(f, cs)?.let { throw EditRejected(it) }
        Specification.problems(f).firstOrNull()?.let { throw EditRejected("Fix the flowsheet first: ${it.second}") }
        buildJsonObject {
            putJsonArray("points") { CaseStudies.grid(cs).forEach { (x, x2) -> add(FlowsheetJson.encodeToJsonElement(Flowsheet.serializer(), CaseStudies.at(f, cs, x, x2))) } }
        }.toString()
    } catch (e: IllegalArgumentException) { err(e.message ?: "Not possible") }

    /**
     * The study's results from each point's engine answer ([resultsJson]: [{result} | {error}], in point order): the
     * flowsheet with the study and its results saved (as the app keeps them), and the report (plots, table).
     */
    fun caseStudyReport(flowsheetJson: String, studyJson: String, resultsJson: String): String {
        val f = fs(flowsheetJson)
        val ctx = UnitContext(f.atmosphericPressurePa)
        val cs0 = study(f, params(studyJson), ctx)
        val grid = CaseStudies.grid(cs0)
        val answers = FlowsheetJson.parseToJsonElement(resultsJson).jsonArray
        val values = cs0.outputs.map { mutableListOf<Double?>() }
        val errors = mutableListOf<String?>()
        grid.forEachIndexed { i, (x, x2) ->
            val a = answers.getOrNull(i)?.jsonObject
            val res = a?.get("result")?.let { FlowsheetJson.decodeFromJsonElement(SolveResult.serializer(), it) }
            if (res == null) { values.forEach { it += null }; errors += a?.get("error")?.jsonPrimitive?.content ?: "No answer"; return@forEachIndexed }
            val (v, e) = CaseStudies.read(CaseStudies.at(f, cs0, x, x2), cs0, res)
            v.forEachIndexed { k, value -> values[k] += value }
            errors += e
        }
        val results = CaseResults(f.modelHash(), grid.map { it.first }, values, errors, grid.map { it.second }.takeIf { CaseStudies.twoWay(cs0) }?.map { it!! })
        val cs = cs0.copy(results = results)
        val saved = FlowsheetEditing.setCaseResults(FlowsheetEditing.upsertCaseStudy(f, cs), cs.id, results)
        val rep = Report(ctx)
        val failed = errors.count { it != null }
        rep.note("${grid.size} points" + if (failed > 0) ", $failed did not converge" else "", if (failed > 0) "warn" else "info")
        CaseStudies.plots(saved, saved.caseStudies.first { it.id == cs.id }).forEach { rep.plot(it) }
        val obj = f.obj(cs.objectId!!)!!
        val q = SpecKey.quantity(cs.specKey!!)
        val obj2 = cs.objectId2?.let { f.obj(it) }
        val head = listOf("${obj.tag} ${SpecKey.label(cs.specKey)}, ${rep.unitOf(q).symbol}") +
            (if (obj2 != null) listOf("${obj2.tag} ${SpecKey.label(cs.specKey2!!)}, ${rep.unitOf(SpecKey.quantity(cs.specKey2)).symbol}") else emptyList()) +
            cs.outputs.map { o -> val oo = f.obj(o.objectId)!!; val u = rep.unitOf(ResultKeys.quantityFor(oo, o.key)); "${oo.tag} ${ResultKeys.label(o.key)}" + if (u.symbol.isBlank()) "" else ", ${u.symbol}" } + listOf("")
        rep.table(head, grid.indices.map { i ->
            listOf(rep.value(grid[i].first, q)) + (if (obj2 != null) listOf(rep.value(grid[i].second!!, SpecKey.quantity(cs.specKey2!!))) else emptyList()) +
                cs.outputs.mapIndexed { k, o -> values[k][i]?.let { rep.value(it, ResultKeys.quantityFor(f.obj(o.objectId)!!, o.key)) } ?: "—" } + listOf(errors[i] ?: "")
        })
        return buildJsonObject {
            put("flowsheet", FlowsheetJson.encodeToJsonElement(Flowsheet.serializer(), saved))
            put("report", FlowsheetJson.parseToJsonElement(rep.json()))
        }.toString()
    }

    // ---------- optimiser (D104): Nelder–Mead on full engine solves, as the app's Optimiser tool ----------

    private fun optimiseInput(f: Flowsheet, p: JsonObject, ctx: UnitContext): OptimiseRequest {
        fun target(o: JsonObject) = OptimiserTarget(o["objectId"]!!.jsonPrimitive.content, o["property"]!!.jsonPrimitive.content, siOf(o["min"], ctx), siOf(o["max"], ctx))
        val variables = p["variables"]?.jsonArray?.map { v ->
            val o = v.jsonObject
            OptimiserVariable(o["objectId"]!!.jsonPrimitive.content, o["key"]!!.jsonPrimitive.content,
                siOf(o["min"], ctx) ?: throw EditRejected("Give every variable's range"), siOf(o["max"], ctx) ?: throw EditRejected("Give every variable's range"))
        }.orEmpty()
        val objective = p["objective"]?.jsonArray?.map { target(it.jsonObject) }.orEmpty()
        if (variables.isEmpty() || objective.isEmpty()) throw EditRejected("Choose at least one variable and one result to optimise")
        if (variables.size > 6) throw EditRejected("Up to six variables")
        return OptimiseRequest(Readiness.of(f).solvable, variables, objective, p["maximise"]?.jsonPrimitive?.booleanOrNull == true,
            p["constraints"]?.jsonArray?.map { target(it.jsonObject) }?.filter { it.min != null || it.max != null }.orEmpty())
    }

    fun optimiseRequest(flowsheetJson: String, paramsJson: String): String = try {
        val f = fs(flowsheetJson)
        Specification.problems(f).firstOrNull()?.let { throw EditRejected("Fix the flowsheet first: ${it.second}") }
        call("optimise", enc(OptimiseRequest.serializer(), optimiseInput(f, params(paramsJson), UnitContext(f.atmosphericPressurePa))))
    } catch (e: IllegalArgumentException) { err(e.message ?: "Not possible") } catch (e: NullPointerException) { err("Incomplete input") }

    fun optimiseReport(flowsheetJson: String, paramsJson: String, resultJson: String): String {
        val f = fs(flowsheetJson)
        val ctx = UnitContext(f.atmosphericPressurePa)
        val req = optimiseInput(f, params(paramsJson), ctx)
        val r = FlowsheetJson.decodeFromString(OptimiseResult.serializer(), resultJson)
        val rep = Report(ctx)
        rep.heading((if (r.feasible) "Optimum" else "Best found (constraints not met)") + " after ${r.solves} solves" + if (r.converged) "" else ", not settled")
        rep.kv(r.values.zip(req.variables).map { (x, v) -> val q = SpecKey.quantity(v.key); Triple("${f.obj(v.objectId)?.tag} ${SpecKey.label(v.key)}", rep.value(x, q, rep.unitOf(q).decimals + 1), rep.unitOf(q).symbol) })
        r.objective?.let { obj ->
            val q = req.objective.first().let { t -> f.obj(t.objectId)?.let { ResultKeys.quantityFor(it, t.property) } } ?: Quantity.Ratio
            rep.kv(listOf(Triple("Objective", rep.value(obj, q, rep.unitOf(q).decimals + 1), rep.unitOf(q).symbol)))
        }
        rep.kv(req.constraints.mapIndexedNotNull { i, c ->
            val v = r.constraintValues.getOrNull(i) ?: return@mapIndexedNotNull null
            val o = f.obj(c.objectId) ?: return@mapIndexedNotNull null
            val q = ResultKeys.quantityFor(o, c.property)
            Triple("${o.tag} ${ResultKeys.label(c.property)} (constraint)", rep.value(v, q), rep.unitOf(q).symbol)
        })
        r.warnings.forEach { rep.note(it, "warn") }
        return rep.json()
    }

    /** Puts the optimum's values into the specifications it moved, in each one's own unit (FlowsheetViewModel.applyOptimum). */
    fun applyOptimum(flowsheetJson: String, paramsJson: String, resultJson: String): String = try {
        val f = fs(flowsheetJson)
        val req = optimiseInput(f, params(paramsJson), UnitContext(f.atmosphericPressurePa))
        val r = FlowsheetJson.decodeFromString(OptimiseResult.serializer(), resultJson)
        if (r.values.size != req.variables.size) throw EditRejected("The optimiser returned no values")
        val next = req.variables.zip(r.values).fold(f) { acc, (v, x) ->
            val old = acc.obj(v.objectId)?.specs?.get(v.key) ?: return@fold acc
            FlowsheetEditing.setSpec(acc, v.objectId, v.key, old.copy(si = x))
        }
        buildJsonObject { put("flowsheet", FlowsheetJson.encodeToJsonElement(Flowsheet.serializer(), next)) }.toString()
    } catch (e: IllegalArgumentException) { err(e.message ?: "Not possible") }

    // ---------- black oil (D101) ----------

    fun blackOils(flowsheetJson: String): String = buildJsonArray {
        fs(flowsheetJson).blackOils.forEach { b -> addJsonObject { put("name", b.name); put("api", b.api); put("gasSpecificGravity", b.gasSpecificGravity); put("gasOilRatio", b.gasOilRatio); put("waterCut", b.waterCut) } }
    }.toString()

    /** Adds or updates a black oil from the well-test data, as the app's Black oil sheet; sets the Black Oil package if none. */
    fun saveBlackOil(flowsheetJson: String, name: String, api: Double, gasSg: Double, gor: Double, waterCutPercent: Double): String = try {
        val f = fs(flowsheetJson)
        val old = f.blackOils.firstOrNull { it.name == name.trim() }
        val b = BlackOil(name.trim(), BlackOil.sgFromApi(api), gasSg, gor, waterCutPercent / 100, old?.viscosity1, old?.viscosityTemperature1, old?.viscosity2, old?.viscosityTemperature2)
        val withOil = FlowsheetEditing.upsertBlackOil(f, b)
        val next = if (withOil.propertyPackage == null) withOil.copy(propertyPackage = BlackOil.PACKAGE) else withOil
        buildJsonObject { put("flowsheet", FlowsheetJson.encodeToJsonElement(Flowsheet.serializer(), next)) }.toString()
    } catch (e: IllegalArgumentException) { err(e.message ?: "Not saved") }
}
