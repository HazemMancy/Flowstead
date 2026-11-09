/*
 * Flowstead web (D130): the Fluid package's thermodynamics tools, as the app's ThermoToolsSheet (D100): DWSIM's binary
 * T–x–y / P–x–y diagram with its liquid–liquid region, a ternary flashed on a grid, the true critical point, one
 * composition with several packages, and a property along T or P. The requests and plots are the app's core
 * (ThermoToolsDtos, ThermoPlots); every number and curve is the engine's, in the user's display units.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:OptIn(ExperimentalJsExport::class)

package app.flowstead.web

import app.flowstead.core.api.BinaryDiagramKinds
import app.flowstead.core.api.BinaryDiagramRequest
import app.flowstead.core.api.BinaryDiagramResult
import app.flowstead.core.api.CriticalPointRequest
import app.flowstead.core.api.CriticalPointResult
import app.flowstead.core.api.ObjectResult
import app.flowstead.core.api.PackageComparisonRequest
import app.flowstead.core.api.PackageComparisonResult
import app.flowstead.core.api.Phases
import app.flowstead.core.api.PropertySweepRequest
import app.flowstead.core.api.PropertySweepResult
import app.flowstead.core.api.SolveResult
import app.flowstead.core.api.SweepVariables
import app.flowstead.core.api.TernaryRequest
import app.flowstead.core.api.TernaryResult
import app.flowstead.core.format.ValueFormat
import app.flowstead.core.model.EditRejected
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.FlowsheetJson
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.ThermoPlots
import app.flowstead.core.model.isFeed
import app.flowstead.core.model.modelHash
import app.flowstead.core.model.obj
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

@JsExport
object ThermoTools {
    private fun fs(json: String): Flowsheet = FlowsheetJson.decodeFromString(Flowsheet.serializer(), json)
    private fun solve(json: String?): SolveResult? = json?.takeIf { it.isNotBlank() && it != "null" }?.let { FlowsheetJson.decodeFromString(SolveResult.serializer(), it) }
    private fun params(json: String): JsonObject = FlowsheetJson.parseToJsonElement(json.ifBlank { "{}" }).jsonObject
    private fun err(message: String) = buildJsonObject { put("error", message) }.toString()
    private fun call(method: String, params: JsonElement) = buildJsonObject { put("method", method); put("params", params) }.toString()

    /** Streams with a composition to take: the solved overall one when current, else a feed's own (the app's streams list). */
    private fun streams(f: Flowsheet, result: SolveResult?, solvedHash: String): List<Triple<String, String, Map<String, Double>>> =
        f.objects.filter { it.type == ObjectType.MaterialStream }.mapNotNull { s ->
            val solved = result?.takeIf { solvedHash == f.modelHash() }?.objects?.get(s.id)?.phases?.get(Phases.OVERALL)?.composition
            val c = solved ?: s.composition.takeIf { f.isFeed(s) && it.values.sum() > 0 }
            c?.filterKeys { it in f.compounds }?.takeIf { it.isNotEmpty() }?.let { Triple(s.id, s.tag, it) }
        }

    /** What the tools offer for this fluid: its package and components, the streams to take a composition from, the units, the chart's properties. */
    fun setup(flowsheetJson: String, solveJson: String?, solvedHash: String): String {
        val f = fs(flowsheetJson)
        return buildJsonObject {
            put("package", f.propertyPackage.orEmpty())
            putJsonArray("compounds") { f.compounds.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("streams") { streams(f, solve(solveJson), solvedHash).forEach { (id, tag, _) -> addJsonObject { put("id", id); put("label", tag) } } }
            for ((name, q) in listOf("t" to Quantity.Temperature, "p" to Quantity.Pressure)) {
                put("${name}Unit", displayUnits.unitFor(q).id)
                putJsonArray("${name}Units") { Units.forQuantity(q).filter { q != Quantity.Pressure || !it.id.endsWith("g") }.forEach { u -> addJsonObject { put("id", u.id); put("symbol", u.symbol) } } }
            }
            putJsonArray("properties") { ThermoPlots.SWEEP_PROPERTIES.forEach { add(JsonPrimitive(it.label)) } }
            // Starting values in the user's units: 25 °C, 1 atm; a chart from −20 to 180 °C or 1 to 100 bar.
            val ctx = UnitContext(f.atmosphericPressurePa)
            fun txt(si: Double, q: Quantity) = displayUnits.unitFor(q).let { u -> ValueFormat.number(u.fromSi(si, ctx), u.decimals + 3).replace(",", "").let { t -> if ('.' in t) t.trimEnd('0').trimEnd('.') else t } }
            put("tDefault", txt(298.15, Quantity.Temperature)); put("pDefault", txt(101325.0, Quantity.Pressure))
            put("tFrom", txt(253.15, Quantity.Temperature)); put("tTo", txt(453.15, Quantity.Temperature))
            put("pFrom", txt(1e5, Quantity.Pressure)); put("pTo", txt(1e7, Quantity.Pressure))
        }.toString()
    }

    private fun composition(f: Flowsheet, result: SolveResult?, solvedHash: String, source: String?): Map<String, Double> {
        if (source.isNullOrEmpty()) return f.compounds.associateWith { 1.0 / f.compounds.size }
        return streams(f, result, solvedHash).firstOrNull { it.first == source }?.third
            ?: throw EditRejected("${f.obj(source)?.tag ?: "That stream"} has no composition yet: solve first")
    }

    /**
     * The engine call for [tool] ("binary", "ternary", "critical", "compare", "chart") from the page's inputs
     * ({a, b, c, kind, lle, temperature: {value, unit}, pressure: {…}, step, source, packages, variable, from, to}), or {error}.
     */
    fun request(tool: String, flowsheetJson: String, solveJson: String?, solvedHash: String, paramsJson: String): String = try {
        val f = fs(flowsheetJson)
        if (f.propertyPackage == null) throw EditRejected("Choose a property package first")
        val p = params(paramsJson)
        val ctx = UnitContext(f.atmosphericPressurePa)
        fun s(k: String) = p[k]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
        fun compound(k: String) = s(k)?.takeIf { it in f.compounds } ?: throw EditRejected("Choose the components")
        fun t() = siOf(p["temperature"], ctx) ?: throw EditRejected("Give the temperature")
        fun pr() = siOf(p["pressure"], ctx)?.also { if (!(it > 0)) throw EditRejected("The pressure must be above zero") } ?: throw EditRejected("Give the pressure")
        val r = solve(solveJson)
        when (tool) {
            "binary" -> {
                val a = compound("a"); val b = compound("b")
                if (a == b) throw EditRejected("Choose two different components")
                val txy = s("kind") != BinaryDiagramKinds.PXY
                call("binaryDiagram", FlowsheetJson.encodeToJsonElement(BinaryDiagramRequest.serializer(), BinaryDiagramRequest(
                    f, a, b, if (txy) BinaryDiagramKinds.TXY else BinaryDiagramKinds.PXY,
                    if (txy) pr() else 101325.0, if (txy) 298.15 else t(), 40, p["lle"]?.jsonPrimitive?.booleanOrNull ?: true,
                )))
            }
            "ternary" -> {
                val three = listOf(compound("a"), compound("b"), compound("c"))
                if (three.distinct().size < 3) throw EditRejected("Choose three different components")
                val step = s("step")?.toDoubleOrNull()?.takeIf { it in 0.025..0.25 } ?: 0.1
                call("ternary", FlowsheetJson.encodeToJsonElement(TernaryRequest.serializer(), TernaryRequest(f, three, t(), pr(), step)))
            }
            "critical" -> call("criticalPoints", FlowsheetJson.encodeToJsonElement(CriticalPointRequest.serializer(), CriticalPointRequest(f, composition(f, r, solvedHash, s("source")))))
            "compare" -> {
                val pk = p["packages"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty().distinct()
                if (pk.isEmpty()) throw EditRejected("Choose at least one package")
                if (pk.size > 8) throw EditRejected("Up to eight packages at a time")
                call("comparePackages", FlowsheetJson.encodeToJsonElement(PackageComparisonRequest.serializer(), PackageComparisonRequest(f, composition(f, r, solvedHash, s("source")), t(), pr(), pk)))
            }
            "chart" -> {
                val temp = s("variable") != SweepVariables.PRESSURE
                val q = if (temp) Quantity.Temperature else Quantity.Pressure
                val from = siOf(p["from"], ctx) ?: throw EditRejected("Give where the chart starts")
                val to = siOf(p["to"], ctx) ?: throw EditRejected("Give where the chart ends")
                if (from == to) throw EditRejected("The chart needs a range")
                if (!temp && (from <= 0 || to <= 0)) throw EditRejected("The pressures must be above zero")
                if (q == Quantity.Temperature && (from <= 0 || to <= 0)) throw EditRejected("Temperatures must be above absolute zero")
                call("propertySweep", FlowsheetJson.encodeToJsonElement(PropertySweepRequest.serializer(), PropertySweepRequest(
                    f, composition(f, r, solvedHash, s("source")), if (temp) SweepVariables.TEMPERATURE else SweepVariables.PRESSURE, from, to, 25, if (temp) pr() else t(),
                )))
            }
            else -> throw EditRejected("Unknown tool")
        }
    } catch (e: IllegalArgumentException) { err(e.message ?: "Not allowed") }

    /** The tool's answer as the app shows it: {sections} (and, for the ternary, {ternary: {compounds, ties, liquid, vapour}} to draw). */
    fun report(tool: String, flowsheetJson: String, paramsJson: String, resultJson: String): String {
        val f = fs(flowsheetJson)
        val p = params(paramsJson)
        val ctx = UnitContext(f.atmosphericPressurePa)
        val rep = Report(ctx)
        var extra: JsonElement? = null
        when (tool) {
            "binary" -> {
                val r = FlowsheetJson.decodeFromString(BinaryDiagramResult.serializer(), resultJson)
                ThermoPlots.binary(r, p["a"]?.jsonPrimitive?.content ?: "")?.let { rep.plot(it) } ?: rep.note("DWSIM traced no curve for this pair.", "warn")
                r.warnings.forEach { rep.note(it, "warn") }
            }
            "ternary" -> {
                val r = FlowsheetJson.decodeFromString(TernaryResult.serializer(), resultJson)
                val ties = ThermoPlots.tieLines(r)
                fun xy(z: List<Double>) = ThermoPlots.triangle(z).let { (x, y) -> JsonArray(listOf(JsonPrimitive(x), JsonPrimitive(y))) }
                extra = buildJsonObject {
                    putJsonArray("compounds") { r.compounds.forEach { add(JsonPrimitive(it)) } }
                    putJsonArray("ties") { ties.forEach { (l1, l2) -> addJsonArray { add(xy(l1)); add(xy(l2)) } } }
                    putJsonArray("liquid") { r.points.filter { it.liquid2 == null && it.vapourFraction <= 1e-9 }.forEach { add(xy(it.feed)) } }
                    putJsonArray("vapour") { r.points.filter { it.liquid2 == null && it.vapourFraction > 1e-9 }.forEach { add(xy(it.feed)) } }
                }
                rep.note("${ties.size} tie lines (two liquids), ${r.points.count { it.vapourFraction > 1e-9 }} points with vapour (hollow), the rest one liquid. Each point is a DWSIM flash with the three-phase search.")
                if (r.failed > 0) rep.note("${r.failed} grid points did not flash; they are left out.", "warn")
                r.warnings.forEach { rep.note(it, "warn") }
            }
            "critical" -> {
                val r = FlowsheetJson.decodeFromString(CriticalPointResult.serializer(), resultJson)
                if (r.points.isEmpty()) rep.note("DWSIM found no critical point for this composition.", "warn")
                r.points.forEachIndexed { i, c ->
                    rep.kv(listOf(
                        Triple(if (r.points.size > 1) "Critical temperature (${i + 1})" else "Critical temperature", rep.value(c.temperature, Quantity.Temperature), rep.unitOf(Quantity.Temperature).symbol),
                        Triple("Critical pressure", rep.value(c.pressure, Quantity.Pressure), rep.unitOf(Quantity.Pressure).symbol),
                        Triple("Critical molar volume", ValueFormat.number(c.molarVolume * 1000, 4), "m³/kmol"),
                    ))
                }
                r.warnings.forEach { rep.note(it, "warn") }
                rep.note("Heidemann–Khalil criticality conditions on the package's equation of state (DWSIM).")
            }
            "compare" -> {
                val r = FlowsheetJson.decodeFromString(PackageComparisonResult.serializer(), resultJson)
                data class Line(val label: String, val quantity: Quantity, val pick: (ObjectResult) -> Double?)
                fun ph(phase: String, key: String): (ObjectResult) -> Double? = { it.phases?.get(phase)?.properties?.get(key) }
                val lines = listOf(
                    Line("Vapour fraction", Quantity.Fraction) { it.properties["vapourFraction"] },
                    Line("Mass enthalpy (each package's own reference)", Quantity.MassEnthalpy) { it.properties["massEnthalpy"] },
                    Line("Mixture density", Quantity.Density, ph(Phases.OVERALL, "density")),
                    Line("Vapour density", Quantity.Density, ph(Phases.VAPOUR, "density")),
                    Line("Liquid density", Quantity.Density, ph(Phases.LIQUID1, "density")),
                    Line("Aqueous density", Quantity.Density, ph(Phases.LIQUID2, "density")),
                    Line("Mixture Cp", Quantity.MassHeatCapacity, ph(Phases.OVERALL, "massHeatCapacity")),
                    Line("Vapour viscosity", Quantity.Viscosity, ph(Phases.VAPOUR, "viscosity")),
                    Line("Liquid viscosity", Quantity.Viscosity, ph(Phases.LIQUID1, "viscosity")),
                    Line("Vapour Z factor", Quantity.Ratio, ph(Phases.VAPOUR, "compressibilityFactor")),
                ).filter { l -> r.rows.any { row -> row.result?.let(l.pick) != null } }
                rep.table(listOf("") + r.rows.map { it.`package` }, lines.map { l ->
                    val u = rep.unitOf(l.quantity)
                    listOf(l.label + if (u.symbol.isBlank()) "" else ", ${u.symbol}") + r.rows.map { row -> row.result?.let(l.pick)?.let { ValueFormat.number(u.fromSi(it, ctx), u.decimals) } ?: "—" }
                })
                r.rows.filter { it.error != null }.forEach { rep.note("${it.`package`}: ${it.error}", "error") }
            }
            "chart" -> {
                val r = FlowsheetJson.decodeFromString(PropertySweepResult.serializer(), resultJson)
                val variable = if (p["variable"]?.jsonPrimitive?.content == SweepVariables.PRESSURE) SweepVariables.PRESSURE else SweepVariables.TEMPERATURE
                val prop = ThermoPlots.SWEEP_PROPERTIES.firstOrNull { it.label == p["property"]?.jsonPrimitive?.content } ?: ThermoPlots.SWEEP_PROPERTIES.first()
                ThermoPlots.sweep(r, variable, prop)?.let { rep.plot(it) } ?: rep.note("The engine returned no ${prop.label.lowercase()} in this range.", "warn")
                val missing = r.results.count { it == null }
                if (missing > 0) rep.note("$missing of ${r.results.size} points did not flash; they are left out.", "warn")
            }
        }
        val sections = FlowsheetJson.parseToJsonElement(rep.json()).jsonObject
        return if (extra == null) sections.toString() else JsonObject(sections + ("ternary" to extra!!)).toString()
    }
}
