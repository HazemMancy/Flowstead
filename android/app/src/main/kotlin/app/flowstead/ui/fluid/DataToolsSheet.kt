/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.fluid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.flowstead.core.api.BinaryDiagramKinds
import app.flowstead.core.api.ReactionFinderRequest
import app.flowstead.core.api.ReactionFinderResult
import app.flowstead.core.api.RegressionParameters
import app.flowstead.core.api.StructureCompoundRequest
import app.flowstead.core.api.StructureCompoundResult
import app.flowstead.core.api.VlePoint
import app.flowstead.core.api.VleRegressionRequest
import app.flowstead.core.api.VleRegressionResult
import app.flowstead.core.compounds.PubChemCompound
import app.flowstead.core.format.ValueFormat
import app.flowstead.core.model.BinaryParameter
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.Hypothetical
import app.flowstead.core.model.Reaction
import app.flowstead.core.units.Quantity
import app.flowstead.core.units.UnitContext
import app.flowstead.data.engine.EngineClient
import app.flowstead.data.engine.EngineResult
import app.flowstead.ui.flowsheet.Choice
import app.flowstead.ui.flowsheet.CurveCell
import app.flowstead.ui.flowsheet.PlainNumber
import app.flowstead.ui.theme.valueColors

private enum class DataTool(val label: String) { Structure("Compound from structure"), Regression("VLE regression"), Reactions("Reaction finder") }

/** Joback's group names by DWSIM's number, for typing counts by hand (the engine's JobackGroups.Names). */
private val JOBACK = listOf(
    "-CH3", "-CH2-", ">CH-", ">C<", "=CH2", "=CH-", "=C<", "=C=", "≡CH", "≡C-",
    "-CH2- (ring)", ">CH- (ring)", ">C< (ring)", "=CH- (ring)", "=C< (ring)",
    "-F", "-Cl", "-Br", "-I",
    "-OH (alcohol)", "-OH (phenol)", "-O- (non-ring)", "-O- (ring)", ">C=O (non-ring)", ">C=O (ring)", "O=CH- (aldehyde)",
    "-COOH (acid)", "-COO- (ester)", "=O (other)",
    "-NH2", ">NH (non-ring)", ">NH (ring)", ">N- (non-ring)", "-N= (non-ring)", "-N= (ring)", "=NH", "-CN", "-NO2",
    "-SH", "-S- (non-ring)", "-S- (ring)",
)

/** State of the PubChem look-up. */
sealed interface PubChemState {
    data object Idle : PubChemState
    data object Working : PubChemState
    data class Found(val compounds: List<PubChemCompound>) : PubChemState
    data class Failed(val reason: String) : PubChemState
}

/**
 * Compound and data tools (D104, D106): a compound from its molecular structure (SMILES, Joback groups, or a name
 * looked up on PubChem), binary interaction parameters regressed from measured VLE, and DWSIM's reaction finder.
 * Every property number is the engine's.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DataToolsSheet(
    ui: FluidUi,
    state: ThermoState,
    pubChem: PubChemState,
    engine: EngineClient,
    onRun: (suspend (String, String?, Flowsheet) -> EngineResult<Any>) -> Unit,
    onClear: () -> Unit,
    onPubChem: (String) -> Unit,
    onSaveCompound: (Hypothetical) -> Unit,
    onSaveParameter: (BinaryParameter) -> Unit,
    onAddReaction: (Reaction) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = UnitContext()
    val focus = LocalFocusManager.current
    val unitSet = ui.unitSet
    val tu = unitSet.unitFor(Quantity.Temperature)
    val pu = unitSet.unitFor(Quantity.Pressure)
    var tool by remember { mutableStateOf(DataTool.Structure) }
    fun run(call: suspend (String, String?, Flowsheet) -> EngineResult<Any>) { focus.clearFocus(); onRun(call) }

    ModalBottomSheet(onDismissRequest = { focus.clearFocus(); onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Compound and data tools", style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DataTool.entries.forEach { x -> FilterChip(selected = tool == x, onClick = { tool = x; onClear() }, label = { Text(x.label) }, modifier = Modifier.testTag("data_${x.name}")) }
            }
            HorizontalDivider()
            when (tool) {
                DataTool.Structure -> StructureTool(state, pubChem, engine, ::run, onPubChem, onSaveCompound)
                DataTool.Regression -> RegressionTool(ui, state, engine, ::run, onSaveParameter, tu, pu, ctx)
                DataTool.Reactions -> ReactionsTool(ui, state, engine, ::run, onAddReaction)
            }
            if (state is ThermoState.Failed) Text(state.reason, color = MaterialTheme.valueColors.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StructureTool(
    state: ThermoState,
    pubChem: PubChemState,
    engine: EngineClient,
    run: (suspend (String, String?, Flowsheet) -> EngineResult<Any>) -> Unit,
    onPubChem: (String) -> Unit,
    onSave: (Hypothetical) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var smiles by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var byHand by remember { mutableStateOf(false) }
    val counts = remember { mutableStateMapOf<Int, Int>() }
    var nbp by remember { mutableStateOf<Double?>(null) }
    Text(
        "Joback's group contributions give the boiling point, critical constants, ideal-gas heat capacity and formation " +
            "energies; ω by Lee–Kesler. Give a SMILES structure, look a name up on PubChem, or count the groups by hand.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(query, { query = it }, label = { Text("Name or CAS number (PubChem)") }, singleLine = true, modifier = Modifier.weight(1f).testTag("pubchem_query"))
        OutlinedButton(onClick = { onPubChem(query) }, enabled = query.isNotBlank() && pubChem !is PubChemState.Working, modifier = Modifier.heightIn(min = 48.dp).testTag("pubchem_search")) {
            Text(if (pubChem is PubChemState.Working) "…" else "Look up")
        }
    }
    when (pubChem) {
        is PubChemState.Found -> pubChem.compounds.take(3).forEach { c ->
            TextButton(onClick = { smiles = c.smiles; if (name.isBlank()) name = c.title.ifBlank { query.trim() } }, modifier = Modifier.fillMaxWidth()) {
                Text("${c.title} · ${c.formula}${c.molecularWeight?.let { " · M ${ValueFormat.number(it, 2)}" } ?: ""} · CID ${c.cid}  — use", style = MaterialTheme.typography.bodySmall)
            }
        }
        is PubChemState.Failed -> Text(pubChem.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.valueColors.underSpecifiedText)
        else -> Unit
    }
    OutlinedTextField(name, { name = it }, label = { Text("Component name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("structure_name"))
    OutlinedTextField(smiles, { smiles = it }, label = { Text("SMILES") }, placeholder = { Text("CCCCCC, Cc1ccccc1, CCO…") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("structure_smiles"))
    TextButton(onClick = { byHand = !byHand }) { Text(if (byHand) "Hide the group counts" else "Count the groups by hand instead") }
    if (byHand) {
        Text("Used when no SMILES is given.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        JOBACK.forEachIndexed { i, label ->
            val id = i + 1
            PlainNumber("$id  $label", counts[id]?.toDouble(), "joback_$id") { v -> if (v == null || v <= 0.0) counts.remove(id) else counts[id] = v.toInt() }
        }
    }
    PlainNumber("Known normal boiling point (optional)", nbp, "structure_nbp", unit = "K", hint = "Joback's estimate") { nbp = it }
    fun compound() = Hypothetical(
        name = name.trim(), normalBoilingPoint = nbp,
        smiles = smiles.trim().ifBlank { null },
        jobackGroups = if (smiles.isBlank()) counts.toMap().ifEmpty { null } else null,
    )
    val ready = name.isNotBlank() && (smiles.isNotBlank() || counts.isNotEmpty())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { val c = compound(); run { url, key, _ -> engine.structureCompound(url, key, StructureCompoundRequest(c)) } },
            enabled = ready && state !is ThermoState.Working, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("structure_estimate"),
        ) { Text(if (state is ThermoState.Working) "Estimating…" else "Estimate") }
        Button(onClick = { onSave(compound()) }, enabled = ready, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("structure_add")) { Text("Add to components") }
    }
    ((state as? ThermoState.Done)?.value as? StructureCompoundResult)?.let { r ->
        Text("${r.formula} · M ${ValueFormat.number(r.molecularWeight, 3)}", style = MaterialTheme.typography.titleSmall)
        Text(r.groups.joinToString(" · ") { "${it.count} × ${it.name}" }, style = MaterialTheme.typography.bodySmall)
        val lines = listOf(
            "Normal boiling point" to "${ValueFormat.number(r.normalBoilingPoint, 2)} K",
            "Critical temperature" to "${ValueFormat.number(r.criticalTemperature, 2)} K",
            "Critical pressure" to "${ValueFormat.number(r.criticalPressure / 1e5, 3)} bar",
            "Critical volume" to "${ValueFormat.number(r.criticalVolume * 1e6, 1)} cm³/mol",
            "Acentric factor" to ValueFormat.number(r.acentricFactor, 4),
            "ΔHf° (ideal gas, 25 °C)" to "${ValueFormat.number(r.enthalpyOfFormation / 1000, 2)} kJ/mol",
            "ΔGf° (ideal gas, 25 °C)" to "${ValueFormat.number(r.gibbsEnergyOfFormation / 1000, 2)} kJ/mol",
        )
        lines.forEach { (k, v) -> Row(Modifier.fillMaxWidth()) { Text(k, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Text(v, style = MaterialTheme.typography.bodyMedium) } }
        r.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.valueColors.underSpecifiedText) }
    }
}

/** "x, y, value" per line (y optional): the measured points, value in the shown temperature or pressure unit. */
internal fun parsePoints(text: String): List<Triple<Double, Double?, Double>>? {
    val rows = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    return rows.map { line ->
        val parts = line.split(Regex("[,;\\t ]+")).filter { it.isNotEmpty() }.map { it.replace(',', '.').toDoubleOrNull() ?: return null }
        when (parts.size) {
            2 -> Triple(parts[0], null, parts[1])
            3 -> Triple(parts[0], parts[1], parts[2])
            else -> return null
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RegressionTool(
    ui: FluidUi,
    state: ThermoState,
    engine: EngineClient,
    run: (suspend (String, String?, Flowsheet) -> EngineResult<Any>) -> Unit,
    onSave: (BinaryParameter) -> Unit,
    tu: app.flowstead.core.units.EngUnit,
    pu: app.flowstead.core.units.EngUnit,
    ctx: UnitContext,
) {
    val options = ui.compounds.map { it to it }
    var a by remember { mutableStateOf(ui.compounds.getOrNull(0)) }
    var b by remember { mutableStateOf(ui.compounds.getOrNull(1)) }
    var kind by remember { mutableStateOf(BinaryDiagramKinds.TXY) }
    var fixed by remember { mutableStateOf<Double?>(101325.0) }
    var data by remember { mutableStateOf("") }
    val pkg = ui.propertyPackage.orEmpty()
    val params = when {
        "NRTL" in pkg -> RegressionParameters.NRTL
        "UNIQUAC" in pkg -> RegressionParameters.UNIQUAC
        else -> RegressionParameters.KIJ
    }
    Text(
        "Fits ${if (params == RegressionParameters.KIJ) "the pair's kij" else "the pair's ${params.uppercase()} A12 and A21"} to measured bubble points " +
            "with $pkg: each point's bubble ${if (kind == BinaryDiagramKinds.TXY) "temperature at its pressure" else "pressure at its temperature"}, and y when given.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Choice("First compound (x, y)", a, options) { a = it }
    Choice("Second compound", b, options) { b = it }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = kind == BinaryDiagramKinds.TXY, onClick = { kind = BinaryDiagramKinds.TXY; fixed = 101325.0 }, label = { Text("Isobaric (T–x–y)") })
        FilterChip(selected = kind == BinaryDiagramKinds.PXY, onClick = { kind = BinaryDiagramKinds.PXY; fixed = 298.15 }, label = { Text("Isothermal (P–x–y)") })
    }
    val valueUnit = if (kind == BinaryDiagramKinds.TXY) tu else pu
    if (kind == BinaryDiagramKinds.TXY) CurveCell("Pressure, ${pu.symbol}", fixed, pu, ctx, "vle_fixed", Modifier.fillMaxWidth()) { fixed = it }
    else CurveCell("Temperature, ${tu.symbol}", fixed, tu, ctx, "vle_fixed", Modifier.fillMaxWidth()) { fixed = it }
    OutlinedTextField(
        data, { data = it }, minLines = 5,
        label = { Text("x, y, ${if (kind == BinaryDiagramKinds.TXY) "T" else "P"} (${valueUnit.symbol}) — one point per line, y optional") },
        modifier = Modifier.fillMaxWidth().testTag("vle_data"),
    )
    val parsed = parsePoints(data)
    if (data.isNotBlank() && parsed == null) Text("Each line needs x and the ${if (kind == BinaryDiagramKinds.TXY) "temperature" else "pressure"}, with y between them if measured.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.valueColors.error)
    val first = a; val second = b
    Button(
        onClick = {
            val pts = parsed!!.map { (x, y, v) ->
                val si = valueUnit.toSi(v, ctx)
                if (kind == BinaryDiagramKinds.TXY) VlePoint(x, y, si, fixed!!) else VlePoint(x, y, fixed!!, si)
            }
            run { url, key, fs -> engine.regressVle(url, key, VleRegressionRequest(fs, first!!, second!!, kind, pts, params)) }
        },
        enabled = first != null && second != null && first != second && fixed != null && (parsed?.size ?: 0) >= 2 && state !is ThermoState.Working,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("vle_run"),
    ) { Text(if (state is ThermoState.Working) "Fitting…" else "Fit") }
    ((state as? ThermoState.Done)?.value as? VleRegressionResult)?.let { r ->
        val p = r.parameter
        Text(
            if (p.a12 != null) "A12 = ${ValueFormat.number(p.a12, 2)} cal/mol, A21 = ${ValueFormat.number(p.a21 ?: 0.0, 2)} cal/mol" + (p.alpha12?.let { ", α = ${ValueFormat.number(it, 2)}" } ?: "")
            else "kij = ${ValueFormat.number(p.kij, 5)}",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            "Average deviation in ${if (kind == BinaryDiagramKinds.TXY) "T" else "P"}: ${ValueFormat.number(r.deviationBefore, 3)} % before, ${ValueFormat.number(r.deviationAfter, 3)} % after" +
                (r.vapourDeviationAfter?.let { "; in y: ${ValueFormat.number(r.vapourDeviationBefore ?: 0.0, 4)} → ${ValueFormat.number(it, 4)}" } ?: "") + " (${r.evaluations} evaluations).",
            style = MaterialTheme.typography.bodySmall,
        )
        r.after.forEach { pt ->
            Text(
                "x ${ValueFormat.number(pt.x, 4)}: measured ${ValueFormat.number(valueUnit.fromSi(pt.measured, ctx), 2)}, fitted ${ValueFormat.number(valueUnit.fromSi(pt.calculated, ctx), 2)} ${valueUnit.symbol}" +
                    (pt.yCalculated?.let { " · y ${ValueFormat.number(it, 4)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        r.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.valueColors.underSpecifiedText) }
        Button(onClick = { onSave(p) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("vle_save")) { Text("Use these parameters in the fluid") }
    }
}

@Composable
private fun ReactionsTool(
    ui: FluidUi,
    state: ThermoState,
    engine: EngineClient,
    run: (suspend (String, String?, Flowsheet) -> EngineResult<Any>) -> Unit,
    onAdd: (Reaction) -> Unit,
) {
    val added = remember { mutableStateMapOf<String, Boolean>() }
    Text(
        "DWSIM's reaction finder: combustion, kinetic and catalytic reactions from its literature tables, and their Gibbs-energy " +
            "equilibria, among this fluid's ${ui.compounds.size} components. Added reactions appear in the reactors' lists.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(
        onClick = { added.clear(); run { url, key, fs -> engine.findReactions(url, key, ReactionFinderRequest(fs)) } },
        enabled = ui.compounds.size >= 2 && state !is ThermoState.Working,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("reactions_find"),
    ) { Text(if (state is ThermoState.Working) "Searching…" else "Find reactions") }
    ((state as? ThermoState.Done)?.value as? ReactionFinderResult)?.let { r ->
        r.reactions.forEach { s ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("${s.category.replaceFirstChar(Char::uppercase)} · ${s.reaction.type.label}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(s.name, style = MaterialTheme.typography.bodyMedium)
                Text(s.reaction.equation(), style = MaterialTheme.typography.bodySmall)
                s.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                val key = s.reaction.id
                TextButton(onClick = { onAdd(s.reaction); added[key] = true }, enabled = added[key] != true) { Text(if (added[key] == true) "Added" else "Add to the flowsheet") }
            }
            HorizontalDivider()
        }
        r.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.valueColors.underSpecifiedText) }
    }
}
