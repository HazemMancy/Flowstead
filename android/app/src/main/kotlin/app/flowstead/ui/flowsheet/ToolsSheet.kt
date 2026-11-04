/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.flowsheet

import android.content.Intent
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.flowstead.core.api.OptimiseRequest
import app.flowstead.core.api.OptimiserTarget
import app.flowstead.core.api.OptimiserVariable
import app.flowstead.core.format.ValueFormat
import app.flowstead.core.model.CellValue
import app.flowstead.core.model.CheckLevel
import app.flowstead.core.model.DisplayValue
import app.flowstead.core.model.FlowsheetCheck
import app.flowstead.core.model.McCabeThiele
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.Plot
import app.flowstead.core.model.PlotSeries
import app.flowstead.core.model.PidTuning
import app.flowstead.core.model.ResultKeys
import app.flowstead.core.model.Scenarios
import app.flowstead.core.model.SpecKey
import app.flowstead.core.model.Spreadsheet
import app.flowstead.core.model.SpreadsheetEval
import app.flowstead.core.model.SpreadsheetExport
import app.flowstead.core.model.WatchItem
import app.flowstead.core.model.obj
import app.flowstead.core.units.Quantity
import app.flowstead.ui.components.PlotView
import app.flowstead.ui.theme.valueColors

private enum class FlowTool(val label: String) {
    Check("Check"), Watch("Watch"), Sheet("Spreadsheet"), Scenarios("Scenarios"), Optimiser("Optimiser"),
    Pid("PID tuning"), McCabe("McCabe–Thiele"), Report("Report"),
}

/** The value of [key] of [objectId] in display units, with its unit, or null when not solved. */
private fun shown(ui: FlowsheetUi, objectId: String, key: String): Pair<Double, String>? {
    val o = ui.flowsheet.obj(objectId) ?: return null
    val v = ui.results.value(objectId, key) as? DisplayValue.Calculated ?: return null
    val u = ui.unitSet.unitFor(ResultKeys.quantityFor(o, key))
    return u.fromSi(v.si, ui.unitContext) to u.symbol
}

/**
 * Flowsheet tools (D105, D106): the check, the watch panel, the spreadsheet, scenarios, the optimiser, PID tuning from a
 * step test, McCabe–Thiele and the report. Every number is the engine's, or the engineer's own spreadsheet arithmetic.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ToolsSheet(ui: FlowsheetUi, vm: FlowsheetViewModel) {
    val focus = LocalFocusManager.current
    var tool by remember { mutableStateOf(FlowTool.Check) }
    ModalBottomSheet(onDismissRequest = { focus.clearFocus(); vm.closeTools() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Flowsheet tools", style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowTool.entries.forEach { x -> FilterChip(selected = tool == x, onClick = { tool = x }, label = { Text(x.label) }, modifier = Modifier.testTag("tool_${x.name}")) }
            }
            HorizontalDivider()
            when (tool) {
                FlowTool.Check -> CheckTool(ui, vm)
                FlowTool.Watch -> WatchTool(ui, vm)
                FlowTool.Sheet -> SpreadsheetTool(ui, vm)
                FlowTool.Scenarios -> ScenariosTool(ui, vm)
                FlowTool.Optimiser -> OptimiserTool(ui, vm)
                FlowTool.Pid -> PidTool(ui, vm)
                FlowTool.McCabe -> McCabeTool(ui, vm)
                FlowTool.Report -> ReportTool(vm)
            }
        }
    }
}

@Composable
private fun CheckTool(ui: FlowsheetUi, vm: FlowsheetViewModel) {
    val items = remember(ui.flowsheet, ui.results) { FlowsheetCheck.run(ui.flowsheet, ui.results) }
    if (items.isEmpty()) Text("Nothing to report: the flowsheet is specified, connected and solved without warnings.")
    items.forEach { item ->
        val colour = when (item.level) {
            CheckLevel.Error -> MaterialTheme.valueColors.error
            CheckLevel.Warning -> MaterialTheme.valueColors.underSpecifiedText
            CheckLevel.Info -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Row(
            Modifier.fillMaxWidth().then(item.objectId?.let { id -> Modifier.clickable { vm.closeTools(); vm.openEditor(id) } } ?: Modifier).padding(vertical = 6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(item.level.name, color = colour, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(72.dp))
            Text(item.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}

/** A result picker: an object, then one of the keys the engine returned for it. */
@Composable
private fun ResultPicker(ui: FlowsheetUi, label: String, onPick: (String, String) -> Unit) {
    var objectId by remember { mutableStateOf<String?>(null) }
    val solved = ui.flowsheet.objects.filter { ui.results.propertyKeys(it.id).isNotEmpty() }
    Choice("$label: object", ui.flowsheet.obj(objectId ?: "")?.tag, solved.map { it.id to it.tag }) { objectId = it }
    objectId?.let { id ->
        val keys = ui.results.propertyKeys(id)
        Choice("$label: result", null, keys.map { it to ResultKeys.label(it) }) { k -> onPick(id, k) }
    }
}

@Composable
private fun WatchTool(ui: FlowsheetUi, vm: FlowsheetViewModel) {
    Text("Results kept in view on the canvas, updated after every solve.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    ui.flowsheet.watch.forEach { w ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            val tag = ui.flowsheet.obj(w.objectId)?.tag ?: "?"
            Text("$tag · ${ResultKeys.label(w.key)}", Modifier.weight(1f))
            Text(shown(ui, w.objectId, w.key)?.let { (v, u) -> "${ValueFormat.number(v, 3)} $u" } ?: "—")
            TextButton(onClick = { vm.toggleWatch(w) }) { Text("Remove") }
        }
    }
    ResultPicker(ui, "Watch") { id, k -> vm.toggleWatch(WatchItem(id, k)) }
    if (ui.results.status == null) Text("Solve the flowsheet to pick results.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun SpreadsheetTool(ui: FlowsheetUi, vm: FlowsheetViewModel) {
    val sheet = ui.flowsheet.spreadsheet ?: Spreadsheet()
    val byTag = remember(ui.flowsheet) { ui.flowsheet.objects.associateBy { it.tag.trim() } }
    val values = remember(sheet, ui.results, ui.unitSet) {
        SpreadsheetEval.evaluate(sheet) { tag, key -> byTag[tag]?.let { shown(ui, it.id, key)?.first } }
    }
    var selected by remember { mutableStateOf("A1") }
    var text by remember(selected, sheet) { mutableStateOf(sheet.cells[selected].orEmpty()) }
    Text(
        "Numbers, text or formulas: =A1*2, =sum(A1:A5), =[K-100.power] imports a solved result in its display unit. " +
            "A cell can be exported to a specification and applied.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(selected, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(32.dp))
        OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.weight(1f).testTag("sheet_input"))
        Button(onClick = { vm.setSpreadsheet(sheet.copy(cells = if (text.isBlank()) sheet.cells - selected else sheet.cells + (selected to text))) }) { Text("Set") }
    }
    (values[selected] as? CellValue.Error)?.let { Text("$selected: ${it.reason}", color = MaterialTheme.valueColors.error, style = MaterialTheme.typography.bodySmall) }
    val usedRows = sheet.cells.keys.mapNotNull { SpreadsheetEval.CELL.find(it)?.groupValues?.get(2)?.toIntOrNull() }.maxOrNull() ?: 0
    val rows = (usedRows + 3).coerceIn(8, SpreadsheetEval.ROWS)
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        Row {
            Box(Modifier.width(28.dp))
            (0 until SpreadsheetEval.COLUMNS).forEach { c -> Text(SpreadsheetEval.columnName(c), Modifier.width(88.dp).padding(2.dp), style = MaterialTheme.typography.labelSmall) }
        }
        for (r in 0 until rows) Row {
            Text("${r + 1}", Modifier.width(28.dp).padding(2.dp), style = MaterialTheme.typography.labelSmall)
            for (c in 0 until SpreadsheetEval.COLUMNS) {
                val name = SpreadsheetEval.cellName(c, r)
                val shownValue = when (val v = values[name]) {
                    is CellValue.Number -> ValueFormat.number(v.value, 4)
                    is CellValue.Text -> v.text
                    is CellValue.Error -> "#ERR"
                    else -> ""
                }
                Box(
                    Modifier.width(88.dp).heightIn(min = 36.dp)
                        .border(if (name == selected) 2.dp else 0.5.dp, if (name == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                        .clickable { selected = name }.padding(4.dp).testTag("cell_$name"),
                    contentAlignment = Alignment.CenterEnd,
                ) { Text(shownValue, style = MaterialTheme.typography.bodySmall, maxLines = 1, color = if (values[name] is CellValue.Error) MaterialTheme.valueColors.error else MaterialTheme.colorScheme.onSurface) }
            }
        }
    }
    SectionLabel("Exports")
    sheet.exports.forEach { e ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${e.cell} → ${ui.flowsheet.obj(e.objectId)?.tag ?: "?"} ${SpecKey.label(e.key)}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { vm.setSpreadsheet(sheet.copy(exports = sheet.exports - e)) }) { Text("Remove") }
        }
    }
    var target by remember { mutableStateOf<String?>(null) }
    val withSpecs = ui.flowsheet.objects.filter { it.specs.isNotEmpty() }
    Choice("Export $selected to: object", ui.flowsheet.obj(target ?: "")?.tag, withSpecs.map { it.id to it.tag }) { target = it }
    target?.let { id ->
        val keys = ui.flowsheet.obj(id)?.specs?.keys.orEmpty().filter { runCatching { SpecKey.quantity(it) }.isSuccess }
        Choice("Specification", null, keys.map { it to SpecKey.label(it) }) { k ->
            vm.setSpreadsheet(sheet.copy(exports = sheet.exports.filterNot { it.objectId == id && it.key == k } + SpreadsheetExport(selected, id, k)))
            target = null
        }
    }
    if (sheet.exports.isNotEmpty()) Button(onClick = { vm.applySpreadsheet(values) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Apply the exports to the flowsheet") }
}

@Composable
private fun ScenariosTool(ui: FlowsheetUi, vm: FlowsheetViewModel) {
    var name by remember { mutableStateOf("") }
    Text("Save the case as it stands, change it, save again, and compare. Restoring puts a scenario's specifications back.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("Scenario name") }, singleLine = true, modifier = Modifier.weight(1f).testTag("scenario_name"))
        Button(onClick = { vm.saveScenario(name); name = "" }) { Text("Save") }
    }
    val list = ui.flowsheet.scenarios
    list.forEachIndexed { i, s ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.name + if (!s.solved) " (no results)" else "", Modifier.weight(1f))
            TextButton(onClick = { vm.restoreScenario(i) }) { Text("Restore") }
            TextButton(onClick = { vm.deleteScenario(i) }) { Text("Delete") }
        }
    }
    if (list.size >= 1) {
        SectionLabel("Comparison")
        val rows = Scenarios.rows(ui.flowsheet, list)
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row {
                Text("", Modifier.width(150.dp))
                list.forEach { Text(it.name, Modifier.width(96.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1) }
            }
            rows.forEach { (id, key) ->
                val o = ui.flowsheet.obj(id) ?: return@forEach
                val u = ui.unitSet.unitFor(ResultKeys.quantityFor(o, key))
                Row {
                    Text("${o.tag} ${ResultKeys.label(key)} (${u.symbol})", Modifier.width(150.dp), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    list.forEach { s ->
                        Text(s.values[Scenarios.key(id, key)]?.let { ValueFormat.number(u.fromSi(it, ui.unitContext), u.decimals) } ?: "—", Modifier.width(96.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun OptimiserTool(ui: FlowsheetUi, vm: FlowsheetViewModel) {
    val ctx = ui.unitContext
    val variables = remember { mutableStateListOf<OptimiserVariable>() }
    val objective = remember { mutableStateListOf<OptimiserTarget>() }
    val constraints = remember { mutableStateListOf<OptimiserTarget>() }
    var maximise by remember { mutableStateOf(false) }
    Text(
        "Moves specifications within bounds to minimise or maximise a result, or the sum of several, keeping others within limits: " +
            "Nelder–Mead on full engine solves.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SectionLabel("Variables (up to six)")
    variables.forEachIndexed { i, v ->
        val o = ui.flowsheet.obj(v.objectId)
        val u = ui.unitSet.unitFor(SpecKey.quantity(v.key))
        Text("${o?.tag} ${SpecKey.label(v.key)} (${u.symbol})", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CurveCell("From", v.min, u, ctx, "opt_min_$i", Modifier.weight(1f)) { x -> x?.let { variables[i] = v.copy(min = it) } }
            CurveCell("To", v.max, u, ctx, "opt_max_$i", Modifier.weight(1f)) { x -> x?.let { variables[i] = v.copy(max = it) } }
            TextButton(onClick = { variables.removeAt(i) }) { Text("✕") }
        }
    }
    if (variables.size < 6) {
        var target by remember { mutableStateOf<String?>(null) }
        Choice("Add a variable: object", ui.flowsheet.obj(target ?: "")?.tag, ui.flowsheet.objects.filter { it.specs.isNotEmpty() }.map { it.id to it.tag }) { target = it }
        target?.let { id ->
            val o = ui.flowsheet.obj(id)
            val keys = o?.specs?.keys.orEmpty().filter { runCatching { SpecKey.quantity(it) }.isSuccess }
            Choice("Specification", null, keys.map { it to SpecKey.label(it) }) { k ->
                val now = o!!.specs.getValue(k).si
                variables += OptimiserVariable(id, k, if (now > 0) now * 0.5 else now - 1, if (now > 0) now * 1.5 else now + 1)
                target = null
            }
        }
    }
    SectionLabel("Objective")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (maximise) "Maximise" else "Minimise", Modifier.weight(1f))
        Switch(checked = maximise, onCheckedChange = { maximise = it })
    }
    objective.forEachIndexed { i, t ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text((if (i > 0) "+ " else "") + "${ui.flowsheet.obj(t.objectId)?.tag} ${ResultKeys.label(t.property)}", Modifier.weight(1f))
            TextButton(onClick = { objective.removeAt(i) }) { Text("✕") }
        }
    }
    ResultPicker(ui, "Add to the objective") { id, k -> objective += OptimiserTarget(id, k) }
    SectionLabel("Constraints")
    constraints.forEachIndexed { i, c ->
        val o = ui.flowsheet.obj(c.objectId) ?: return@forEachIndexed
        val u = ui.unitSet.unitFor(ResultKeys.quantityFor(o, c.property))
        Text("${o.tag} ${ResultKeys.label(c.property)} (${u.symbol})", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CurveCell("At least", c.min, u, ctx, "con_min_$i", Modifier.weight(1f)) { constraints[i] = c.copy(min = it) }
            CurveCell("At most", c.max, u, ctx, "con_max_$i", Modifier.weight(1f)) { constraints[i] = c.copy(max = it) }
            TextButton(onClick = { constraints.removeAt(i) }) { Text("✕") }
        }
    }
    ResultPicker(ui, "Add a constraint") { id, k -> constraints += OptimiserTarget(id, k) }
    val run = ui.optimiser
    Button(
        onClick = {
            val v = variables.toList(); val o = objective.toList(); val c = constraints.filter { it.min != null || it.max != null }; val max = maximise
            vm.runOptimiser { fs -> OptimiseRequest(fs, v, o, max, c) }
        },
        enabled = variables.isNotEmpty() && objective.isNotEmpty() && run?.running != true,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("optimiser_run"),
    ) { Text(if (run?.running == true) "Optimising… (each step is a solve)" else "Optimise") }
    run?.error?.let { Text(it, color = MaterialTheme.valueColors.error) }
    run?.result?.let { r ->
        Text(
            (if (r.feasible) "Optimum" else "Best found (constraints not met)") + " after ${r.solves} solves" + if (r.converged) "" else ", not settled",
            style = MaterialTheme.typography.titleSmall,
        )
        r.values.zip(variables).forEach { (x, v) ->
            val u = ui.unitSet.unitFor(SpecKey.quantity(v.key))
            Text("${ui.flowsheet.obj(v.objectId)?.tag} ${SpecKey.label(v.key)} = ${ValueFormat.number(u.fromSi(x, ctx), u.decimals + 1)} ${u.symbol}")
        }
        r.objective?.let { obj ->
            val first = objective.firstOrNull()?.let { t -> ui.flowsheet.obj(t.objectId)?.let { ResultKeys.quantityFor(it, t.property) } } ?: Quantity.Ratio
            val u = ui.unitSet.unitFor(first)
            Text("Objective = ${ValueFormat.number(u.fromSi(obj, ctx), u.decimals + 1)} ${u.symbol}")
        }
        r.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.valueColors.underSpecifiedText) }
        if (r.values.size == variables.size) Button(onClick = { vm.applyOptimum(variables.toList(), r.values) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Put the optimum in the flowsheet") }
    }
}

@Composable
private fun PidTool(ui: FlowsheetUi, vm: FlowsheetViewModel) {
    val controllers = ui.flowsheet.objects.filter { it.type == ObjectType.Controller }
    var id by remember { mutableStateOf(controllers.firstOrNull()?.id) }
    var step by remember { mutableStateOf<Double?>(10.0) }
    var duration by remember { mutableStateOf<Double?>(1800.0) }
    Text(
        "An open-loop step test in the dynamic run: the controller goes to manual and its output steps; a first-order-plus-dead-time " +
            "model is fitted to the response (Smith's two-point method) and four rules give the settings.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (controllers.isEmpty()) { Text("Add a controller (PIC, LIC…) to the flowsheet first."); return }
    Choice("Controller", ui.flowsheet.obj(id ?: "")?.tag, controllers.map { it.id to it.tag }) { id = it }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PlainNumber("Output step", step, "pid_step", unit = "%", modifier = Modifier.weight(1f)) { step = it }
        PlainNumber("Run for", duration, "pid_duration", unit = "s", modifier = Modifier.weight(1f)) { duration = it }
    }
    val run = ui.pidTest
    Button(
        onClick = { vm.runStepTest(id!!, step!!, duration!!) },
        enabled = id != null && (step ?: 0.0) != 0.0 && (duration ?: 0.0) > 0 && run?.running != true,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pid_run"),
    ) { Text(if (run?.running == true) "Running the step test…" else "Run the step test") }
    run?.error?.let { Text(it, color = MaterialTheme.valueColors.error) }
    run?.result?.let { t ->
        val series = mutableListOf(PlotSeries("Measurement, % of span", t.times, t.pvPercent))
        t.model?.let { m ->
            val y0 = t.pvPercent.first()
            series += PlotSeries("Fitted model", t.times, t.times.map { PidTuning.response(m, y0, t.step, it - t.stepTime) }, dashed = true)
        }
        PlotView(Plot("Step test", "Time", Quantity.Time, "Measurement", Quantity.Ratio, series), ui.unitSet, ui.unitContext)
        val m = t.model
        if (m == null) Text("The response did not settle within the run, or hardly moved: run longer or step further.", color = MaterialTheme.valueColors.underSpecifiedText)
        else {
            Text("K = ${ValueFormat.number(m.gain, 3)} %/%, τ = ${ValueFormat.number(m.timeConstant, 1)} s, θ = ${ValueFormat.number(m.deadTime, 1)} s", style = MaterialTheme.typography.titleSmall)
            t.settings.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${s.rule}: Kc ${ValueFormat.number(s.gain, 3)}, Ti ${ValueFormat.number(s.integralTime, 1)} s" + if (s.derivativeTime > 0) ", Td ${ValueFormat.number(s.derivativeTime, 1)} s" else "",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { vm.applyTuning(t.controllerId, s, m) }) { Text("Use") }
                }
            }
        }
    }
}

@Composable
private fun McCabeTool(ui: FlowsheetUi, vm: FlowsheetViewModel) {
    val compounds = ui.flowsheet.compounds
    var light by remember { mutableStateOf(compounds.getOrNull(0)) }
    var heavy by remember { mutableStateOf(compounds.getOrNull(1)) }
    var pressure by remember { mutableStateOf<Double?>(101325.0) }
    var xD by remember { mutableStateOf<Double?>(0.95) }
    var xB by remember { mutableStateOf<Double?>(0.05) }
    var zF by remember { mutableStateOf<Double?>(0.5) }
    var q by remember { mutableStateOf<Double?>(1.0) }
    var reflux by remember { mutableStateOf<Double?>(2.0) }
    val pu = ui.unitSet.unitFor(Quantity.Pressure)
    Text(
        "A binary column stage by stage on the engine's equilibrium curve at the column pressure: constant molar overflow, total condenser, " +
            "partial reboiler counted as a stage.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Choice("Light key", light, compounds.map { it to it }) { light = it }
    Choice("Heavy key", heavy, compounds.map { it to it }) { heavy = it }
    CurveCell("Column pressure, ${pu.symbol}", pressure, pu, ui.unitContext, "mt_p", Modifier.fillMaxWidth()) { pressure = it }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PlainNumber("xD", xD, "mt_xd", modifier = Modifier.weight(1f)) { xD = it }
        PlainNumber("xB", xB, "mt_xb", modifier = Modifier.weight(1f)) { xB = it }
        PlainNumber("zF", zF, "mt_zf", modifier = Modifier.weight(1f)) { zF = it }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PlainNumber("Feed q", q, "mt_q", hint = "1 = saturated liquid", modifier = Modifier.weight(1f)) { q = it }
        PlainNumber("Reflux ratio L/D", reflux, "mt_r", modifier = Modifier.weight(1f)) { reflux = it }
    }
    val run = ui.mcCabe
    OutlinedButton(
        onClick = { vm.runMcCabe(light!!, heavy!!, pressure!!) },
        enabled = light != null && heavy != null && light != heavy && pressure != null && run?.running != true,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("mt_run"),
    ) { Text(if (run?.running == true) "Tracing the equilibrium…" else "Trace the equilibrium curve") }
    run?.error?.let { Text(it, color = MaterialTheme.valueColors.error) }
    run?.result?.let { d ->
        val curve = McCabeThiele.equilibrium(d)
        val r = if (listOf(xD, xB, zF, q, reflux).all { it != null }) McCabeThiele.solve(curve, xD!!, xB!!, zF!!, q!!, reflux!!) else null
        if (r == null) Text("Give 0 < xB < zF < xD < 1 and a positive reflux ratio.", color = MaterialTheme.valueColors.underSpecifiedText)
        else {
            if (r.stages.isNotEmpty()) PlotView(McCabeThiele.plot(r, xD!!, xB!!, reflux!!, light!!), ui.unitSet, ui.unitContext)
            Text(
                (if (r.stages.isNotEmpty()) "${ValueFormat.number(r.theoreticalStages, 2)} theoretical stages (reboiler included), feed on stage ${r.feedStage}. " else "") +
                    "Minimum stages ${ValueFormat.number(r.minimumStages, 2)}" + (r.minimumReflux?.let { ", minimum reflux ${ValueFormat.number(it, 3)}" } ?: "") + ".",
                style = MaterialTheme.typography.titleSmall,
            )
            r.warnings.forEach { Text(it, color = MaterialTheme.valueColors.underSpecifiedText, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun ReportTool(vm: FlowsheetViewModel) {
    val context = LocalContext.current
    Text(
        "The case as a Markdown report: fluid, streams, equipment results, the engine's messages and the flowsheet check, in your units.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(
        onClick = {
            val text = vm.report() ?: return@Button
            val send = Intent(Intent.ACTION_SEND).apply { type = "text/markdown"; putExtra(Intent.EXTRA_TEXT, text); putExtra(Intent.EXTRA_SUBJECT, "Flowstead report") }
            context.startActivity(Intent.createChooser(send, "Share the report"))
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("report_share"),
    ) { Text("Share the report") }
}

/** The watch panel over the canvas (D105): each watched result with its current value. */
@Composable
fun WatchPanel(ui: FlowsheetUi, modifier: Modifier = Modifier) {
    if (ui.flowsheet.watch.isEmpty()) return
    Column(
        modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant).padding(8.dp).testTag("watch_panel"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ui.flowsheet.watch.forEach { w ->
            val tag = ui.flowsheet.obj(w.objectId)?.tag ?: return@forEach
            val v = shown(ui, w.objectId, w.key)
            Text(
                "$tag ${ResultKeys.label(w.key)}: " + (v?.let { (x, u) -> "${ValueFormat.number(x, 3)} $u" } ?: "—"),
                style = MaterialTheme.typography.labelMedium,
                color = if (ui.results.stale) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
