/*
 * Flowstead web (D126): the flowsheet spreadsheet and saved scenarios, as the app's ToolsSheet (SpreadsheetTool,
 * ScenariosTool) and FlowsheetViewModel. The evaluator, the snapshot, the restore and the comparison rows are the app's
 * own core (SpreadsheetEval, Scenarios); results come from the engine's last solve, in the user's display units. A cell's
 * number is the engineer's own arithmetic on the engine's results, shown as such.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:OptIn(ExperimentalJsExport::class)

package app.flowstead.web

import app.flowstead.core.api.SolveResult
import app.flowstead.core.format.ValueFormat
import app.flowstead.core.model.CellValue
import app.flowstead.core.model.DisplayValue
import app.flowstead.core.model.EditRejected
import app.flowstead.core.model.FlowsheetCheck
import app.flowstead.core.model.WatchItem
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.FlowsheetEditing
import app.flowstead.core.model.FlowsheetJson
import app.flowstead.core.model.ResultKeys
import app.flowstead.core.model.ResultView
import app.flowstead.core.model.Scenarios
import app.flowstead.core.model.Spec
import app.flowstead.core.model.SpecKey
import app.flowstead.core.model.Spreadsheet
import app.flowstead.core.model.SpreadsheetEval
import app.flowstead.core.model.SpreadsheetExport
import app.flowstead.core.model.StoredSolve
import app.flowstead.core.model.modelHash
import app.flowstead.core.model.obj
import app.flowstead.core.units.UnitContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

@JsExport
object SheetTools {
    private fun fs(json: String): Flowsheet = FlowsheetJson.decodeFromString(Flowsheet.serializer(), json)
    private fun err(message: String) = buildJsonObject { put("error", message) }.toString()
    private fun out(f: Flowsheet) = buildJsonObject { put("flowsheet", FlowsheetJson.encodeToJsonElement(Flowsheet.serializer(), f)) }.toString()
    private fun view(f: Flowsheet, solveJson: String?, solvedHash: String): ResultView {
        val s = solveJson?.takeIf { it.isNotBlank() && it != "null" }?.let { StoredSolve(FlowsheetJson.decodeFromString(SolveResult.serializer(), it), solvedHash, 0L) }
        return ResultView(s, f.modelHash())
    }

    /** [key] of [objectId] in the display unit of its quantity, as the app's `shown`, or null when the engine gave none. */
    private fun shown(f: Flowsheet, results: ResultView, objectId: String, key: String): Double? {
        val o = f.obj(objectId) ?: return null
        val v = results.value(objectId, key) as? DisplayValue.Calculated ?: return null
        return displayUnits.unitFor(ResultKeys.quantityFor(o, key)).fromSi(v.si, UnitContext(f.atmosphericPressurePa))
    }

    private fun values(f: Flowsheet, results: ResultView): Map<String, CellValue> {
        val byTag = f.objects.associateBy { it.tag.trim() }
        return SpreadsheetEval.evaluate(f.spreadsheet ?: Spreadsheet()) { tag, key -> byTag[tag]?.let { shown(f, results, it.id, key) } }
    }

    /**
     * The sheet as the page draws it: {columns, rows, cells: {A1: {raw, shown, error?}}, exports: [{cell, label}],
     * targets: [{id, tag, keys: [{key, label, unit}]}], stale}. Rows: the used ones plus three, at least 8 (the app's).
     */
    fun sheet(flowsheetJson: String, solveJson: String?, solvedHash: String): String {
        val f = fs(flowsheetJson)
        val results = view(f, solveJson, solvedHash)
        val sheet = f.spreadsheet ?: Spreadsheet()
        val v = values(f, results)
        val used = sheet.cells.keys.mapNotNull { SpreadsheetEval.CELL.find(it)?.groupValues?.get(2)?.toIntOrNull() }.maxOrNull() ?: 0
        return buildJsonObject {
            putJsonArray("columns") { (0 until SpreadsheetEval.COLUMNS).forEach { add(JsonPrimitive(SpreadsheetEval.columnName(it))) } }
            put("rows", (used + 3).coerceIn(8, SpreadsheetEval.ROWS))
            put("stale", results.stale)
            putJsonObject("cells") {
                for ((name, raw) in sheet.cells) putJsonObject(name) {
                    put("raw", raw)
                    when (val c = v[name]) {
                        is CellValue.Number -> put("shown", ValueFormat.number(c.value, 4))
                        is CellValue.Text -> put("shown", c.text)
                        is CellValue.Error -> { put("shown", "#ERR"); put("error", c.reason) }
                        else -> put("shown", "")
                    }
                }
            }
            putJsonArray("exports") {
                sheet.exports.forEach { e ->
                    addJsonObject { put("cell", e.cell); put("label", "${e.cell} → ${f.obj(e.objectId)?.tag ?: "?"} ${SpecKey.label(e.key)} (${runCatching { displayUnits.unitFor(SpecKey.quantity(e.key)).symbol }.getOrDefault("")})") }
                }
            }
            // What a cell can be exported to: the specifications the objects have (the app's choice), in their display unit.
            putJsonArray("targets") {
                f.objects.filter { it.specs.isNotEmpty() }.forEach { o ->
                    val keys = o.specs.keys.filter { runCatching { SpecKey.quantity(it) }.isSuccess }
                    if (keys.isEmpty()) return@forEach
                    addJsonObject {
                        put("id", o.id); put("tag", o.tag)
                        putJsonArray("keys") { keys.forEach { k -> addJsonObject { put("key", k); put("label", SpecKey.label(k)); put("unit", displayUnits.unitFor(SpecKey.quantity(k)).symbol) } } }
                    }
                }
            }
        }.toString()
    }

    /** Sets (or, blank, clears) one cell. Only A1…F30, as the app's sheet. */
    fun setCell(flowsheetJson: String, cell: String, text: String): String = try {
        val f = fs(flowsheetJson)
        if (SpreadsheetEval.CELL.find(cell) == null || (cell.substring(1).toIntOrNull() ?: 99) > SpreadsheetEval.ROWS || cell[0] - 'A' >= SpreadsheetEval.COLUMNS) throw EditRejected("No cell $cell")
        val sheet = f.spreadsheet ?: Spreadsheet()
        val next = sheet.copy(cells = if (text.isBlank()) sheet.cells - cell else sheet.cells + (cell to text.trim()))
        out(f.copy(spreadsheet = next.takeIf { it != Spreadsheet() }))
    } catch (e: IllegalArgumentException) { err(e.message ?: "Not allowed") }

    /** Exports [cell] to [key] of [objectId] (replacing an export to the same specification). */
    fun addExport(flowsheetJson: String, cell: String, objectId: String, key: String): String = try {
        val f = fs(flowsheetJson)
        val o = f.obj(objectId) ?: throw EditRejected("Choose the object")
        if (key !in o.specs) throw EditRejected("${o.tag} has no ${SpecKey.label(key).lowercase()} specification")
        val sheet = f.spreadsheet ?: Spreadsheet()
        out(f.copy(spreadsheet = sheet.copy(exports = sheet.exports.filterNot { it.objectId == objectId && it.key == key } + SpreadsheetExport(cell, objectId, key))))
    } catch (e: IllegalArgumentException) { err(e.message ?: "Not allowed") }

    fun removeExport(flowsheetJson: String, index: Int): String {
        val f = fs(flowsheetJson)
        val sheet = f.spreadsheet ?: return out(f)
        val next = sheet.copy(exports = sheet.exports.filterIndexed { i, _ -> i != index })
        return out(f.copy(spreadsheet = next.takeIf { it != Spreadsheet() }))
    }

    /**
     * Writes each exported cell's value, read in the display unit of the specification, into the specification (the
     * app's applySpreadsheet). Refused, naming the cell, when one has no number.
     */
    fun applyExports(flowsheetJson: String, solveJson: String?, solvedHash: String): String = try {
        val f = fs(flowsheetJson)
        val sheet = f.spreadsheet ?: throw EditRejected("Nothing to export")
        val v = values(f, view(f, solveJson, solvedHash))
        val ctx = UnitContext(f.atmosphericPressurePa)
        out(sheet.exports.fold(f) { acc, e ->
            val n = (v[e.cell] as? CellValue.Number)?.value ?: throw EditRejected("${e.cell} has no number to export")
            val u = displayUnits.unitFor(SpecKey.quantity(e.key))
            FlowsheetEditing.setSpec(acc, e.objectId, e.key, Spec(u.toSi(n, ctx), u.id))
        })
    } catch (e: IllegalArgumentException) { err(e.message ?: "Not allowed") }

    // ---------- the check and the watch panel (D136, the app's CheckTool and WatchTool) ----------

    /** The flowsheet check: [{level: error|warning|info, text, objectId?}], as the app's FlowsheetCheck. */
    fun check(flowsheetJson: String, solveJson: String?, solvedHash: String): String {
        val f = fs(flowsheetJson)
        return kotlinx.serialization.json.buildJsonArray {
            FlowsheetCheck.run(f, view(f, solveJson, solvedHash)).forEach { c ->
                addJsonObject { put("level", c.level.name.lowercase()); put("text", c.text); c.objectId?.let { put("objectId", it) } }
            }
        }.toString()
    }

    /**
     * The watch panel: {items: [{objectId, key, label, value, unit}], stale, targets: [{id, tag, keys: [{key, label}]}]};
     * each value the engine's from the last solve in the user's display units ("—" when it gave none).
     */
    fun watch(flowsheetJson: String, solveJson: String?, solvedHash: String): String {
        val f = fs(flowsheetJson)
        val results = view(f, solveJson, solvedHash)
        return buildJsonObject {
            put("stale", results.stale)
            putJsonArray("items") {
                f.watch.forEach { w ->
                    val o = f.obj(w.objectId) ?: return@forEach
                    val u = displayUnits.unitFor(ResultKeys.quantityFor(o, w.key))
                    addJsonObject {
                        put("objectId", w.objectId); put("key", w.key); put("label", "${o.tag} ${ResultKeys.label(w.key)}"); put("unit", u.symbol)
                        put("value", shown(f, results, w.objectId, w.key)?.let { ValueFormat.number(it, 3) } ?: "—")
                    }
                }
            }
            putJsonArray("targets") {
                f.objects.filter { results.propertyKeys(it.id).isNotEmpty() }.forEach { o ->
                    addJsonObject {
                        put("id", o.id); put("tag", o.tag)
                        putJsonArray("keys") { results.propertyKeys(o.id).forEach { k -> addJsonObject { put("key", k); put("label", ResultKeys.label(k)) } } }
                    }
                }
            }
        }.toString()
    }

    /** Adds a result to the watch panel, or takes it off. */
    fun toggleWatch(flowsheetJson: String, objectId: String, key: String): String {
        val f = fs(flowsheetJson)
        val item = WatchItem(objectId, key)
        return out(f.copy(watch = if (item in f.watch) f.watch - item else f.watch + item))
    }

    // ---------- scenarios (the app's ScenariosTool) ----------

    /**
     * The saved scenarios and their comparison: {list: [{name, solved}], rows: [{label, unit, values: [text]}]}, every
     * value the engine's when the scenario was saved, in today's display units ("—" where a scenario has none).
     */
    fun scenarios(flowsheetJson: String): String {
        val f = fs(flowsheetJson)
        val ctx = UnitContext(f.atmosphericPressurePa)
        val list = f.scenarios
        return buildJsonObject {
            putJsonArray("list") { list.forEach { s -> addJsonObject { put("name", s.name); put("solved", s.solved) } } }
            putJsonArray("rows") {
                Scenarios.rows(f, list).forEach { (id, key) ->
                    val o = f.obj(id) ?: return@forEach
                    val u = displayUnits.unitFor(ResultKeys.quantityFor(o, key))
                    addJsonObject {
                        put("label", "${o.tag} ${ResultKeys.label(key)}"); put("unit", u.symbol)
                        putJsonArray("values") { list.forEach { s -> add(JsonPrimitive(s.values[Scenarios.key(id, key)]?.let { ValueFormat.number(u.fromSi(it, ctx), u.decimals) } ?: "—")) } }
                    }
                }
            }
        }.toString()
    }

    /** Saves the case as it stands with its current results: {flowsheet, solved}. Without current results it saves the specifications only. */
    fun saveScenario(flowsheetJson: String, solveJson: String?, solvedHash: String, name: String, nowMillis: Double): String {
        val f = fs(flowsheetJson)
        val s = Scenarios.snapshot(f, view(f, solveJson, solvedHash), name.trim().ifBlank { "Scenario ${f.scenarios.size + 1}" }, nowMillis.toLong())
        return buildJsonObject {
            put("flowsheet", FlowsheetJson.encodeToJsonElement(Flowsheet.serializer(), f.copy(scenarios = f.scenarios + s)))
            put("solved", s.solved)
        }.toString()
    }

    /** Puts a scenario's specifications back on the objects that still exist. */
    fun restoreScenario(flowsheetJson: String, index: Int): String {
        val f = fs(flowsheetJson)
        val s = f.scenarios.getOrNull(index) ?: return err("That scenario no longer exists")
        return out(Scenarios.restore(f, s))
    }

    fun deleteScenario(flowsheetJson: String, index: Int): String {
        val f = fs(flowsheetJson)
        return out(f.copy(scenarios = f.scenarios.filterIndexed { i, _ -> i != index }))
    }
}
