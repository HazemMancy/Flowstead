/*
 * Flowstead web core (D109): what the web app calls. Flowsheets cross as JSON in the app's own format
 * (shared/schema/flowsheet.schema.json), so a project file opens the same in the app and in the browser.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:OptIn(ExperimentalJsExport::class)

package app.flowstead.web

import app.flowstead.core.api.SolveResult
import app.flowstead.core.compat.Resources
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import app.flowstead.core.export.Csv
import app.flowstead.core.help.AutoLayout
import app.flowstead.core.model.Readiness
import app.flowstead.core.model.Subflowsheets
import app.flowstead.core.model.displayName
import app.flowstead.ui.flowsheet.drawNozzles
import app.flowstead.ui.flowsheet.drawUnitIcon
import app.flowstead.core.export.ResultsWorkbook
import app.flowstead.core.export.Xlsx
import app.flowstead.core.format.ValueFormat
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.FlowsheetEditing
import app.flowstead.core.model.FlowsheetJson
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.Port
import app.flowstead.core.model.Ports
import app.flowstead.core.model.Specification
import app.flowstead.core.model.StoredSolve
import app.flowstead.core.model.Symbols
import app.flowstead.core.model.modelHash
import app.flowstead.core.units.Quantity
import app.flowstead.core.units.UnitSet
import app.flowstead.core.units.Units
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

@JsExport
object FlowsteadCore {
    private fun fs(json: String): Flowsheet = FlowsheetJson.decodeFromString(Flowsheet.serializer(), json)
    private fun out(f: Flowsheet): String = FlowsheetJson.encodeToString(Flowsheet.serializer(), f)
    private fun serialName(t: ObjectType): String = ObjectType.serializer().descriptor.getElementName(t.ordinal)
    private fun type(id: String): ObjectType = ObjectType.entries.first { serialName(it) == id }
    private fun portJson(p: Port) = buildJsonObject {
        put("side", p.side.name); put("kind", p.kind.name); put("index", p.index); put("label", p.label)
        put("dx", p.dx.toDouble()); put("dy", p.dy.toDouble()); put("required", p.required)
    }

    /** Every object type the palette offers: id (as in project files), category, symbol size and ports. */
    fun objectTypes(): String = buildJsonArray {
        for (t in ObjectType.entries) add(buildJsonObject {
            put("id", serialName(t)); put("name", t.name); put("label", t.displayName()); put("category", t.category.label)
            put("available", t.available); put("isStream", t.isStream)
            val (w, h) = Symbols.size(t); put("width", w); put("height", h)
            putJsonArray("ports") { Ports.of(t).forEach { add(portJson(it)) } }
        })
    }.toString()

    /**
     * Draws [typeId]'s symbol as the app's palette and canvas do (Icons.kt), centred in a [width] × [height] box on
     * [ctx] (a CanvasRenderingContext2D), scaled to fit with its nozzles.
     */
    fun drawSymbol(ctx: dynamic, typeId: String, width: Double, height: Double, dark: Boolean) {
        val t = type(typeId)
        val (sw, sh) = Symbols.size(t)
        val k = (minOf(width / (sw * 1.15), height / (sh * 1.15))).toFloat()
        val d = CanvasDrawScope(ctx, Size(width.toFloat(), height.toFloat()))
        val p = CanvasColors(dark).palette
        ctx.save()
        ctx.translate(width / 2, height / 2)
        ctx.scale(k, k)
        val s = Size(sw.toFloat(), sh.toFloat())
        if (!t.isStream) d.drawNozzles(t, Offset.Zero, s, p, strokeWidth = 2f)
        d.drawUnitIcon(t, Offset.Zero, s, p, strokeWidth = 2f)
        ctx.restore()
    }

    /** An example's flowsheet laid out left to right as a PFD, as the app opens examples (AutoLayout). */
    fun layout(flowsheetJson: String): String = out(AutoLayout.layout(fs(flowsheetJson)))

    /**
     * What the app checks before it solves: {blocking: [..], notReady: {id: reason}, anyReady, solvable, modelHash}.
     * The engine gets only [solvable], the objects that are ready (D83); [modelHash] is of the whole flowsheet, as the
     * app stores it with the result.
     */
    fun prepareSolve(flowsheetJson: String): String {
        val whole = fs(flowsheetJson)
        // With sub-flowsheets (D132, D133) the checks run on the case flattened (Readiness does); the engine later gets
        // the ready part of the case as it is, which the page's engine client flattens or solves by package.
        val f = Subflowsheets.flatten(whole)
        val r = Readiness.of(whole)
        val solvable = r.solvable
        return buildJsonObject {
            putJsonArray("blocking") { Specification.blocking(f).forEach { add(JsonPrimitive(it.second)) } }
            putJsonArray("problems") { Specification.problems(f).forEach { add(JsonPrimitive(it.second)) } }
            put("notReady", JsonObject(r.notReady.mapValues { JsonPrimitive(it.value) }))
            put("anyReady", r.anyReady)
            put("solvable", FlowsheetJson.parseToJsonElement(out(solvable)))
            put("modelHash", whole.modelHash())
        }.toString()
    }

    /** A new, empty flowsheet in the app's format. */
    fun newFlowsheet(propertyPackage: String): String = out(Flowsheet(propertyPackage = propertyPackage))

    /** Adds an object of [typeId] at ([x], [y]); the core puts it on its grid. Returns {flowsheet, id}. */
    fun add(flowsheetJson: String, typeId: String, x: Double, y: Double): String {
        val (f, o) = FlowsheetEditing.add(fs(flowsheetJson), type(typeId), x, y)
        return buildJsonObject { put("flowsheet", FlowsheetJson.parseToJsonElement(out(f))); put("id", o.id) }.toString()
    }

    /**
     * Moves an object to exactly ([x], [y]), as the app does: the canvas has already placed it on the grid and lined
     * it up with its connections, which snapping again here would undo for nozzles off their symbol's centre.
     */
    fun move(flowsheetJson: String, id: String, x: Double, y: Double): String =
        out(FlowsheetEditing.move(fs(flowsheetJson), id, x, y))

    fun rename(flowsheetJson: String, id: String, tag: String): String = out(FlowsheetEditing.rename(fs(flowsheetJson), id, tag))

    fun delete(flowsheetJson: String, id: String): String = out(FlowsheetEditing.delete(fs(flowsheetJson), id))

    private fun port(f: Flowsheet, unitId: String, side: String, kind: String, index: Int): Port {
        val unit = f.objects.first { it.id == unitId }
        return Ports.of(unit.type).first { it.side.name == side && it.kind.name == kind && it.index == index }
    }

    /** Connects stream [streamId] to a port of unit [unitId], following the app's rules. */
    fun connect(flowsheetJson: String, streamId: String, unitId: String, side: String, kind: String, index: Int): String {
        val f = fs(flowsheetJson)
        return out(FlowsheetEditing.connect(f, streamId, unitId, port(f, unitId, side, kind, index)))
    }

    /** Drags a new stream out of a unit's port to ([x], [y]); returns {flowsheet, id}. */
    fun connectNewStream(flowsheetJson: String, unitId: String, side: String, kind: String, index: Int, x: Double, y: Double): String {
        val f = fs(flowsheetJson)
        val (g, s) = FlowsheetEditing.connectNewStream(f, unitId, port(f, unitId, side, kind, index), x, y)
        return buildJsonObject { put("flowsheet", FlowsheetJson.parseToJsonElement(out(g))); put("id", s.id) }.toString()
    }

    /** Joins two units' ports with a new stream between them. */
    fun connectUnits(flowsheetJson: String, aId: String, aSide: String, aKind: String, aIndex: Int,
                     bId: String, bSide: String, bKind: String, bIndex: Int, x: Double, y: Double): String {
        val f = fs(flowsheetJson)
        return out(FlowsheetEditing.connectUnits(f, aId, port(f, aId, aSide, aKind, aIndex), bId, port(f, bId, bSide, bKind, bIndex), x, y))
    }

    /** The app's checks before a solve: one line per problem, empty when the flowsheet is complete. */
    fun problems(flowsheetJson: String): Array<String> = Specification.problems(fs(flowsheetJson)).map { it.toString() }.toTypedArray()

    /** The specification status of one object, as the app shows it. */
    fun status(flowsheetJson: String, id: String): String {
        val f = fs(flowsheetJson)
        return Specification.status(f, f.objects.first { it.id == id }).toString()
    }

    /** SHA-256 of the solved part of the flowsheet: the same as the app computes (CanonicalJson.kt). */
    fun modelHash(flowsheetJson: String): String = fs(flowsheetJson).modelHash()

    // ---------- the user's unit set (D121) ----------

    private val unitQuantities = listOf(
        Quantity.Temperature to "Temperature", Quantity.TemperatureDifference to "Temperature difference", Quantity.Pressure to "Pressure",
        Quantity.PressureDifference to "Pressure difference", Quantity.MolarFlow to "Molar flow", Quantity.MassFlow to "Mass flow",
        Quantity.VolumeFlow to "Volume flow", Quantity.Power to "Power and duty", Quantity.MassEnthalpy to "Mass enthalpy",
        Quantity.MolarEnthalpy to "Molar enthalpy", Quantity.MassEntropy to "Mass entropy", Quantity.MassHeatCapacity to "Heat capacity",
        Quantity.Density to "Density", Quantity.Viscosity to "Viscosity", Quantity.Length to "Length", Quantity.Volume to "Volume",
        Quantity.Time to "Time", Quantity.Velocity to "Velocity",
    )

    private fun setJson(set: UnitSet) = buildJsonObject {
        put("name", set.name)
        putJsonObject("units") { unitQuantities.forEach { (q, _) -> put(q.name, set.unitFor(q).id) } }
    }

    /** The unit set in use, the presets and every quantity with its units: {current, presets, quantities: [{id, label, units: [{id, symbol}]}]}. */
    fun unitSetOptions(): String = buildJsonObject {
        put("current", setJson(displayUnits))
        putJsonArray("presets") { listOf(WEB_DEFAULT_UNITS, UnitSet.SI.copy(name = "SI (°C, kPa)"), UnitSet.FIELD).forEach { add(setJson(it)) } }
        putJsonArray("quantities") {
            unitQuantities.forEach { (q, label) ->
                add(buildJsonObject {
                    put("id", q.name); put("label", label)
                    putJsonArray("units") { Units.forQuantity(q).forEach { u -> add(buildJsonObject { put("id", u.id); put("symbol", u.symbol.ifEmpty { u.id }) }) } }
                })
            }
        }
    }.toString()

    /** Uses the units of [json] ({name?, units: {Quantity: unitId}}); a quantity not listed keeps the web default. Unknown ones are ignored. */
    fun setUnitSet(json: String) {
        val o = FlowsheetJson.parseToJsonElement(json).jsonObject
        val chosen = (o["units"] as? JsonObject).orEmpty().mapNotNull { (k, v) ->
            val q = unitQuantities.firstOrNull { it.first.name == k }?.first ?: return@mapNotNull null
            val u = Units.byIdOrNull((v as? JsonPrimitive)?.content ?: return@mapNotNull null)?.takeIf { it.quantity == q } ?: return@mapNotNull null
            q to u
        }
        var set = WEB_DEFAULT_UNITS
        chosen.forEach { (q, u) -> set = set.with(q, u) }
        val name = (o["name"] as? JsonPrimitive)?.content
        displayUnits = if (name != null && name != UnitSet.CUSTOM) set.copy(name = name) else set
    }

    /** Every typed input shown in the unit set's unit for its quantity: the same physical value, written in the units now chosen. */
    fun restateUnits(flowsheetJson: String): String {
        val f = fs(flowsheetJson)
        val wanted = unitQuantities.map { it.first }.toSet()
        fun restate(sp: app.flowstead.core.model.Spec): app.flowstead.core.model.Spec {
            val q = Units.byIdOrNull(sp.unit)?.quantity?.takeIf { it in wanted } ?: return sp
            return sp.copy(unit = displayUnits.unitFor(q).id)
        }
        return out(f.copy(objects = f.objects.map { o -> o.copy(specs = o.specs.mapValues { restate(it.value) }) }))
    }

    /** Hold (false) or Active (true) solver, as HYSYS's solver buttons (the app's Flowsheet.solverActive); saved with the flowsheet, not part of the solved model. */
    fun solverActive(flowsheetJson: String): Boolean = fs(flowsheetJson).solverActive
    fun setSolverActive(flowsheetJson: String, active: Boolean): String = out(fs(flowsheetJson).copy(solverActive = active))

    // [solvedModelHash] is the model hash taken when the solve ran (as the app's StoredSolve keeps it), not the current
    // one: if the flowsheet changed since, the workbook says its results are out of date.
    private fun sheets(projectName: String, flowsheetJson: String, solveResultJson: String, solvedModelHash: String, solvedAtMillis: Double) =
        ResultsWorkbook.build(projectName, fs(flowsheetJson), StoredSolve(majorOnly(FlowsheetJson.decodeFromString(SolveResult.serializer(), solveResultJson)),
            solvedModelHash, solvedAtMillis.toLong()), UnitSet.SI.with(Quantity.Temperature, Units.C).with(Quantity.Pressure, Units.bara))

    /** Results workbook (.xlsx bytes) for a stored solve, SI with °C and bara, as the app exports it. */
    fun resultsXlsx(projectName: String, flowsheetJson: String, solveResultJson: String, solvedModelHash: String, solvedAtMillis: Double): ByteArray =
        Xlsx.write(sheets(projectName, flowsheetJson, solveResultJson, solvedModelHash, solvedAtMillis))

    fun resultsCsv(projectName: String, flowsheetJson: String, solveResultJson: String, solvedModelHash: String, solvedAtMillis: Double): String =
        Csv.write(sheets(projectName, flowsheetJson, solveResultJson, solvedModelHash, solvedAtMillis))

    /** "1,250.0": grouped thousands, '.' decimals, as on every app screen. */
    fun formatNumber(value: Double, decimals: Int): String = ValueFormat.number(value, decimals)

    /** Help pages and examples, path → text, loaded by the web app from the repository bundle. */
    fun provideResources(filesJson: String) {
        val o = FlowsheetJson.parseToJsonElement(filesJson).jsonObject
        Resources.provide(o.mapValues { it.value.jsonPrimitive.content })
    }
}
