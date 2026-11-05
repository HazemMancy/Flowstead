/*
 * Flowstead web core (D109): what the web app calls. Flowsheets cross as JSON in the app's own format
 * (shared/schema/flowsheet.schema.json), so a project file opens the same in the app and in the browser.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:OptIn(ExperimentalJsExport::class)

package app.flowstead.web

import app.flowstead.core.api.SolveResult
import app.flowstead.core.compat.Resources
import app.flowstead.core.export.Csv
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
            put("id", serialName(t)); put("name", t.name); put("category", t.category.label)
            put("available", t.available); put("isStream", t.isStream)
            val (w, h) = Symbols.size(t); put("width", w); put("height", h)
            putJsonArray("ports") { Ports.of(t).forEach { add(portJson(it)) } }
        })
    }.toString()

    /** A new, empty flowsheet in the app's format. */
    fun newFlowsheet(propertyPackage: String): String = out(Flowsheet(propertyPackage = propertyPackage))

    /** Adds an object of [typeId] at ([x], [y]) on the grid; returns {flowsheet, id}. */
    fun add(flowsheetJson: String, typeId: String, x: Double, y: Double): String {
        val (f, o) = FlowsheetEditing.add(fs(flowsheetJson), type(typeId), FlowsheetEditing.snap(x), FlowsheetEditing.snap(y))
        return buildJsonObject { put("flowsheet", FlowsheetJson.parseToJsonElement(out(f))); put("id", o.id) }.toString()
    }

    fun move(flowsheetJson: String, id: String, x: Double, y: Double): String =
        out(FlowsheetEditing.move(fs(flowsheetJson), id, FlowsheetEditing.snap(x), FlowsheetEditing.snap(y)))

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

    /** Results workbook (.xlsx bytes) for a solve, SI with °C and bara, as the app exports it. */
    fun resultsXlsx(projectName: String, flowsheetJson: String, solveResultJson: String, solvedAtMillis: Double): ByteArray {
        val f = fs(flowsheetJson)
        val r = FlowsheetJson.decodeFromString(SolveResult.serializer(), solveResultJson)
        val units = UnitSet.SI.with(Quantity.Temperature, Units.C).with(Quantity.Pressure, Units.bara)
        return Xlsx.write(ResultsWorkbook.build(projectName, f, StoredSolve(r, f.modelHash(), solvedAtMillis.toLong()), units))
    }

    fun resultsCsv(projectName: String, flowsheetJson: String, solveResultJson: String, solvedAtMillis: Double): String {
        val f = fs(flowsheetJson)
        val r = FlowsheetJson.decodeFromString(SolveResult.serializer(), solveResultJson)
        val units = UnitSet.SI.with(Quantity.Temperature, Units.C).with(Quantity.Pressure, Units.bara)
        return Csv.write(ResultsWorkbook.build(projectName, f, StoredSolve(r, f.modelHash(), solvedAtMillis.toLong()), units))
    }

    /** "1,250.0": grouped thousands, '.' decimals, as on every app screen. */
    fun formatNumber(value: Double, decimals: Int): String = ValueFormat.number(value, decimals)

    /** Help pages and examples, path → text, loaded by the web app from the repository bundle. */
    fun provideResources(filesJson: String) {
        val o = FlowsheetJson.parseToJsonElement(filesJson).jsonObject
        Resources.provide(o.mapValues { it.value.jsonPrimitive.content })
    }
}
