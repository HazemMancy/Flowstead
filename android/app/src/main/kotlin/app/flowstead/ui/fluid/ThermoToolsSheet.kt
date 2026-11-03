/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.fluid

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.flowstead.core.api.BinaryDiagramKinds
import app.flowstead.core.api.BinaryDiagramRequest
import app.flowstead.core.api.BinaryDiagramResult
import app.flowstead.core.api.CriticalPointRequest
import app.flowstead.core.api.CriticalPointResult
import app.flowstead.core.api.PackageComparisonRequest
import app.flowstead.core.api.PackageComparisonResult
import app.flowstead.core.api.Phases
import app.flowstead.core.api.PropertySweepRequest
import app.flowstead.core.api.PropertySweepResult
import app.flowstead.core.api.SweepVariables
import app.flowstead.core.api.TernaryRequest
import app.flowstead.core.api.TernaryResult
import app.flowstead.core.format.ValueFormat
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.ThermoPlots
import app.flowstead.core.units.Quantity
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.UnitSet
import app.flowstead.data.engine.EngineClient
import app.flowstead.data.engine.EngineResult
import app.flowstead.ui.components.PlotView
import app.flowstead.ui.flowsheet.Choice
import app.flowstead.ui.flowsheet.CurveCell
import app.flowstead.ui.theme.valueColors

sealed interface ThermoState {
    data object Idle : ThermoState
    data object Working : ThermoState
    data class Done(val value: Any) : ThermoState
    data class Failed(val reason: String) : ThermoState
}

private enum class Tool(val label: String) {
    Binary("Binary diagram"), Ternary("Ternary"), Critical("Critical point"), Compare("Compare packages"), Chart("Property chart")
}

private const val EQUAL = "(equal amounts of every component)"

/**
 * The thermodynamics tools (D100) with this fluid: DWSIM's binary Txy/Pxy diagram (with the liquid–liquid region),
 * a ternary flashed on a grid, the true critical point, one composition with several packages, and a property along
 * T or P. Every number and curve is the engine's.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ThermoToolsSheet(
    ui: FluidUi,
    state: ThermoState,
    engine: EngineClient,
    onRun: (suspend (String, String?, Flowsheet) -> EngineResult<Any>) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = UnitContext()
    val focus = LocalFocusManager.current
    val unitSet = ui.unitSet
    val t = unitSet.unitFor(Quantity.Temperature)
    val pu = unitSet.unitFor(Quantity.Pressure)
    var tool by remember { mutableStateOf(Tool.Binary) }
    val compounds = ui.compounds
    var a by remember { mutableStateOf(compounds.getOrNull(0)) }
    var b by remember { mutableStateOf(compounds.getOrNull(1)) }
    var c by remember { mutableStateOf(compounds.getOrNull(2)) }
    var kind by remember { mutableStateOf(BinaryDiagramKinds.TXY) }
    var lle by remember { mutableStateOf(true) }
    var temperature by remember { mutableStateOf<Double?>(298.15) }
    var pressure by remember { mutableStateOf<Double?>(101325.0) }
    var step by remember { mutableStateOf(0.1) }
    var source by remember { mutableStateOf(ui.streams.firstOrNull()?.first ?: EQUAL) }
    val packages = remember { mutableStateListOf<String>().apply { ui.propertyPackage?.let { add(it) } } }
    var variable by remember { mutableStateOf(SweepVariables.TEMPERATURE) }
    var from by remember { mutableStateOf<Double?>(250.0) }
    var to by remember { mutableStateOf<Double?>(450.0) }
    var property by remember { mutableStateOf(ThermoPlots.SWEEP_PROPERTIES.first()) }

    fun composition(): Map<String, Double> =
        ui.streams.firstOrNull { it.first == source }?.second?.filterKeys { it in compounds } ?: compounds.associateWith { 1.0 / compounds.size }

    fun run(call: suspend (String, String?, Flowsheet) -> EngineResult<Any>) { focus.clearFocus(); onRun(call) }

    ModalBottomSheet(onDismissRequest = { focus.clearFocus(); onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Thermodynamics tools", style = MaterialTheme.typography.titleLarge)
            Text("With ${ui.propertyPackage ?: "no package yet"} and this fluid's components.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tool.entries.forEach { x -> FilterChip(selected = tool == x, onClick = { tool = x; onClear() }, label = { Text(x.label) }, modifier = Modifier.testTag("thermo_${x.name}")) }
            }
            HorizontalDivider()
            val options = compounds.map { it to it }
            when (tool) {
                Tool.Binary -> {
                    Choice("First compound (x axis)", a, options) { a = it }
                    Choice("Second compound", b, options) { b = it }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = kind == BinaryDiagramKinds.TXY, onClick = { kind = BinaryDiagramKinds.TXY }, label = { Text("T–x–y at a pressure") })
                        FilterChip(selected = kind == BinaryDiagramKinds.PXY, onClick = { kind = BinaryDiagramKinds.PXY }, label = { Text("P–x–y at a temperature") })
                    }
                    if (kind == BinaryDiagramKinds.TXY) CurveCell("Pressure, ${pu.symbol}", pressure, pu, ctx, "thermo_p", Modifier.fillMaxWidth()) { pressure = it }
                    else CurveCell("Temperature, ${t.symbol}", temperature, t, ctx, "thermo_t", Modifier.fillMaxWidth()) { temperature = it }
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(lle, role = Role.Switch) { lle = it }, verticalAlignment = Alignment.CenterVertically) {
                        Text("Also the liquid–liquid region", modifier = Modifier.weight(1f)); Switch(checked = lle, onCheckedChange = null)
                    }
                    val first = a; val second = b
                    Button(
                        onClick = { run { url, key, fs -> engine.binaryDiagram(url, key, BinaryDiagramRequest(fs, first!!, second!!, kind, pressure ?: 101325.0, temperature ?: 298.15, 40, lle)) } },
                        enabled = first != null && second != null && first != second && state !is ThermoState.Working,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("thermo_run"),
                    ) { Text(if (state is ThermoState.Working) "Calculating…" else "Draw") }
                    ((state as? ThermoState.Done)?.value as? BinaryDiagramResult)?.let { r ->
                        ThermoPlots.binary(r, first ?: "")?.let { PlotView(it, unitSet, ctx) } ?: Text("DWSIM traced no curve for this pair.")
                        r.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.valueColors.underSpecifiedText) }
                    }
                }
                Tool.Ternary -> {
                    Choice("Top corner", a, options) { a = it }
                    Choice("Bottom left", b, options) { b = it }
                    Choice("Bottom right", c, options) { c = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        CurveCell("Temperature, ${t.symbol}", temperature, t, ctx, "thermo_tt", Modifier.weight(1f)) { temperature = it }
                        CurveCell("Pressure, ${pu.symbol}", pressure, pu, ctx, "thermo_tp", Modifier.weight(1f)) { pressure = it }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0.1, 0.05).forEach { s -> FilterChip(selected = step == s, onClick = { step = s }, label = { Text("Grid ${ValueFormat.number(s, 2)}") }) }
                    }
                    val three = listOfNotNull(a, b, c)
                    Button(
                        onClick = { run { url, key, fs -> engine.ternary(url, key, TernaryRequest(fs, three, temperature!!, pressure!!, step)) } },
                        enabled = three.distinct().size == 3 && temperature != null && pressure != null && state !is ThermoState.Working,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("thermo_run"),
                    ) { Text(if (state is ThermoState.Working) "Flashing the grid…" else "Draw") }
                    ((state as? ThermoState.Done)?.value as? TernaryResult)?.let { TernaryView(it) }
                }
                Tool.Critical -> {
                    Choice("Composition", source, (ui.streams.map { it.first } + EQUAL).map { it to it }) { source = it }
                    Button(
                        onClick = { val x = composition(); run { url, key, fs -> engine.criticalPoints(url, key, CriticalPointRequest(fs, x)) } },
                        enabled = state !is ThermoState.Working, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("thermo_run"),
                    ) { Text(if (state is ThermoState.Working) "Calculating…" else "Find the critical point") }
                    ((state as? ThermoState.Done)?.value as? CriticalPointResult)?.let { r ->
                        r.points.forEach { p ->
                            Text(
                                "${ValueFormat.number(t.fromSi(p.temperature, ctx), t.decimals)} ${t.symbol} · ${ValueFormat.number(pu.fromSi(p.pressure, ctx), pu.decimals)} ${pu.symbol} · " +
                                    "${ValueFormat.number(p.molarVolume * 1000, 4)} m³/kmol",
                                style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("thermo_critical"),
                            )
                        }
                        r.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.valueColors.underSpecifiedText) }
                        Text("Heidemann–Khalil criticality conditions on the package's equation of state (DWSIM).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Tool.Compare -> {
                    Choice("Composition", source, (ui.streams.map { it.first } + EQUAL).map { it to it }) { source = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        CurveCell("Temperature, ${t.symbol}", temperature, t, ctx, "thermo_ct", Modifier.weight(1f)) { temperature = it }
                        CurveCell("Pressure, ${pu.symbol}", pressure, pu, ctx, "thermo_cp", Modifier.weight(1f)) { pressure = it }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ui.packages.forEach { p -> FilterChip(selected = p in packages, onClick = { if (p in packages) packages.remove(p) else if (packages.size < 8) packages.add(p) }, label = { Text(p) }) }
                    }
                    Button(
                        onClick = { val x = composition(); val list = packages.toList(); run { url, key, fs -> engine.comparePackages(url, key, PackageComparisonRequest(fs, x, temperature!!, pressure!!, list)) } },
                        enabled = packages.isNotEmpty() && temperature != null && pressure != null && state !is ThermoState.Working,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("thermo_run"),
                    ) { Text(if (state is ThermoState.Working) "Solving…" else "Compare") }
                    ((state as? ThermoState.Done)?.value as? PackageComparisonResult)?.let { ComparisonTable(it, unitSet, ctx) }
                }
                Tool.Chart -> {
                    Choice("Composition", source, (ui.streams.map { it.first } + EQUAL).map { it to it }) { source = it }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = variable == SweepVariables.TEMPERATURE, onClick = { variable = SweepVariables.TEMPERATURE; from = 250.0; to = 450.0 }, label = { Text("Along temperature") })
                        FilterChip(selected = variable == SweepVariables.PRESSURE, onClick = { variable = SweepVariables.PRESSURE; from = 1e5; to = 100e5 }, label = { Text("Along pressure") })
                    }
                    val su = if (variable == SweepVariables.TEMPERATURE) t else pu
                    val fu = if (variable == SweepVariables.TEMPERATURE) pu else t
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        CurveCell("From, ${su.symbol}", from, su, ctx, "thermo_from", Modifier.weight(1f)) { from = it }
                        CurveCell("To, ${su.symbol}", to, su, ctx, "thermo_to", Modifier.weight(1f)) { to = it }
                    }
                    if (variable == SweepVariables.TEMPERATURE) CurveCell("At pressure, ${fu.symbol}", pressure, fu, ctx, "thermo_fixed", Modifier.fillMaxWidth()) { pressure = it }
                    else CurveCell("At temperature, ${fu.symbol}", temperature, fu, ctx, "thermo_fixed", Modifier.fillMaxWidth()) { temperature = it }
                    Choice("Property", property.label, ThermoPlots.SWEEP_PROPERTIES.map { it.label to it.label }) { l -> property = ThermoPlots.SWEEP_PROPERTIES.first { it.label == l } }
                    val fixed = if (variable == SweepVariables.TEMPERATURE) pressure else temperature
                    Button(
                        onClick = { val x = composition(); val v = variable; run { url, key, fs -> engine.propertySweep(url, key, PropertySweepRequest(fs, x, v, from!!, to!!, 25, fixed!!)) } },
                        enabled = from != null && to != null && fixed != null && state !is ThermoState.Working,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("thermo_run"),
                    ) { Text(if (state is ThermoState.Working) "Flashing…" else "Chart") }
                    ((state as? ThermoState.Done)?.value as? PropertySweepResult)?.let { r ->
                        ThermoPlots.sweep(r, variable, property)?.let { PlotView(it, unitSet, ctx) } ?: Text("The engine returned no ${property.label.lowercase()} in this range.")
                        val missing = r.results.count { it == null }
                        if (missing > 0) Text("$missing of ${r.results.size} points did not flash; they are left out.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.valueColors.underSpecifiedText)
                    }
                }
            }
            (state as? ThermoState.Failed)?.let { Text(it.reason, color = MaterialTheme.valueColors.error, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Close") }
        }
    }
}

/** The triangle: one-liquid points as dots, two-liquid points' liquids joined by their tie lines, vapour points hollow. */
@Composable
private fun TernaryView(r: TernaryResult) {
    val edge = MaterialTheme.colorScheme.outline
    val liquid = MaterialTheme.colorScheme.primary
    val tie = MaterialTheme.colorScheme.tertiary
    val vapour = MaterialTheme.colorScheme.secondary
    val ties = ThermoPlots.tieLines(r)
    Text("${r.compounds.getOrNull(0)} at the top, ${r.compounds.getOrNull(1)} bottom left, ${r.compounds.getOrNull(2)} bottom right (mole fractions).", style = MaterialTheme.typography.bodySmall)
    Canvas(
        Modifier.fillMaxWidth().aspectRatio(1.15f).semantics { contentDescription = "Ternary diagram with ${ties.size} tie lines" }.testTag("thermo_ternary"),
    ) {
        val pad = 12f
        val w = size.width - 2 * pad
        val h = w * Math.sqrt(3.0).toFloat() / 2
        fun at(z: List<Double>): Offset { val (x, y) = ThermoPlots.triangle(z); return Offset(pad + (x * w).toFloat(), pad + h - (y * w).toFloat()) }
        val top = at(listOf(1.0, 0.0, 0.0)); val left = at(listOf(0.0, 1.0, 0.0)); val right = at(listOf(0.0, 0.0, 1.0))
        drawLine(edge, top, left, 3f); drawLine(edge, left, right, 3f); drawLine(edge, right, top, 3f)
        ties.forEach { (l1, l2) -> drawLine(tie, at(l1), at(l2), 2f); drawCircle(liquid, 5f, at(l1)); drawCircle(liquid, 5f, at(l2)) }
        r.points.filter { it.liquid2 == null }.forEach { p ->
            if (p.vapourFraction > 1e-9) drawCircle(vapour, 4f, at(p.feed), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
            else drawCircle(liquid.copy(alpha = 0.35f), 3f, at(p.feed))
        }
    }
    Text(
        "${ties.size} tie lines (two liquids), ${r.points.count { it.vapourFraction > 1e-9 }} points with vapour (hollow), the rest one liquid. " +
            "Each point is a DWSIM flash with the three-phase search.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    r.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.valueColors.underSpecifiedText) }
}

/** One column per package: vapour fraction, then density, Cp and viscosity of each phase present, and enthalpy. */
@Composable
private fun ComparisonTable(r: PackageComparisonResult, unitSet: UnitSet, ctx: UnitContext) {
    data class Line(val label: String, val quantity: Quantity, val pick: (app.flowstead.core.api.ObjectResult) -> Double?)
    fun ph(phase: String, key: String): (app.flowstead.core.api.ObjectResult) -> Double? = { it.phases?.get(phase)?.properties?.get(key) }
    val lines = listOf(
        Line("Vapour fraction", Quantity.Fraction) { it.properties["vapourFraction"] },
        Line("Mass enthalpy", Quantity.MassEnthalpy) { it.properties["massEnthalpy"] },
        Line("Mixture density", Quantity.Density, ph(Phases.OVERALL, "density")),
        Line("Vapour density", Quantity.Density, ph(Phases.VAPOUR, "density")),
        Line("Liquid density", Quantity.Density, ph(Phases.LIQUID1, "density")),
        Line("Aqueous density", Quantity.Density, ph(Phases.LIQUID2, "density")),
        Line("Mixture Cp", Quantity.MassHeatCapacity, ph(Phases.OVERALL, "massHeatCapacity")),
        Line("Vapour viscosity", Quantity.Viscosity, ph(Phases.VAPOUR, "viscosity")),
        Line("Liquid viscosity", Quantity.Viscosity, ph(Phases.LIQUID1, "viscosity")),
        Line("Vapour Z factor", Quantity.Ratio, ph(Phases.VAPOUR, "compressibilityFactor")),
    ).filter { l -> r.rows.any { row -> row.result?.let(l.pick) != null } }
    Row(Modifier.horizontalScroll(rememberScrollState()).testTag("thermo_compare")) {
        Column {
            Text("", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(140.dp).heightIn(min = 40.dp))
            lines.forEach { l -> Text("${l.label}, ${unitSet.unitFor(l.quantity).symbol}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(140.dp).heightIn(min = 28.dp)) }
        }
        r.rows.forEach { row ->
            Column(horizontalAlignment = Alignment.End) {
                Text(row.`package`, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(110.dp).heightIn(min = 40.dp))
                lines.forEach { l ->
                    val u = unitSet.unitFor(l.quantity)
                    val v = row.result?.let(l.pick)
                    Text(v?.let { ValueFormat.number(u.fromSi(it, ctx), u.decimals) } ?: "—", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(110.dp).heightIn(min = 28.dp))
                }
            }
        }
    }
    r.rows.filter { it.error != null }.forEach { Text("${it.`package`}: ${it.error}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.valueColors.error) }
}
