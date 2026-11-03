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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.flowstead.core.api.AssayBases
import app.flowstead.core.api.AssayPoint
import app.flowstead.core.api.AssayRequest
import app.flowstead.core.api.AssayResult
import app.flowstead.core.api.DistillationCurveKinds
import app.flowstead.core.format.ValueFormat
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.units.Quantity
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.UnitSet
import app.flowstead.ui.flowsheet.CurveCell
import app.flowstead.ui.theme.valueColors

sealed interface AssayState {
    data object Idle : AssayState
    data object Working : AssayState
    data class Done(val request: AssayRequest, val result: AssayResult) : AssayState
    data class Failed(val reason: String) : AssayState
}

/**
 * HYSYS's Oil Manager in one sheet (D99): a distillation curve (TBP, D86, D1160 or D2887) with bulk properties and
 * light ends, or a plus fraction's bulk properties, cut into pseudo-components by the engine. The result is shown
 * before anything is added; "Add" makes the cuts hypotheticals and a stream of the assay's composition.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AssaySheet(
    unitSet: UnitSet,
    compounds: List<String>,
    state: AssayState,
    onCharacterise: (AssayRequest) -> Unit,
    onApply: (AssayResult) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = UnitContext()
    val focus = LocalFocusManager.current
    val t = unitSet.unitFor(Quantity.Temperature)
    var name by remember { mutableStateOf("CRUDE") }
    var bulkOnly by remember { mutableStateOf(false) }
    var curveType by remember { mutableStateOf(DistillationCurveKinds.TBP) }
    var basis by remember { mutableStateOf(AssayBases.VOLUME) }
    val percents = remember { mutableStateListOf(0.0, 10.0, 30.0, 50.0, 70.0, 90.0, 100.0) }
    val temps = remember { mutableStateListOf<Double?>(null, null, null, null, null, null, null) }
    var sg by remember { mutableStateOf<Double?>(null) }
    var mw by remember { mutableStateOf<Double?>(null) }
    var nbp by remember { mutableStateOf<Double?>(null) }
    var cuts by remember { mutableStateOf<Double?>(10.0) }
    val lightEnds = remember { mutableStateMapOf<String, Double>() }
    var lightBasis by remember { mutableStateOf(AssayBases.VOLUME) }
    var lightInCurve by remember { mutableStateOf(false) }

    fun request() = AssayRequest(
        flowsheet = Flowsheet(),
        name = name.trim(),
        curveType = curveType,
        basis = if (curveType == DistillationCurveKinds.TBP) basis else AssayBases.VOLUME,
        points = if (bulkOnly) emptyList() else percents.indices.mapNotNull { i -> temps[i]?.let { AssayPoint(percents[i], it) } },
        bulkSpecificGravity = sg,
        bulkMolecularWeight = mw,
        bulkBoilingPoint = if (bulkOnly) nbp else null,
        cuts = (cuts ?: 10.0).toInt(),
        lightEnds = if (bulkOnly) emptyMap() else lightEnds.filterValues { it > 0 },
        lightEndsBasis = lightBasis,
        lightEndsInCurve = lightInCurve,
    )

    ModalBottomSheet(onDismissRequest = { focus.clearFocus(); onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Characterise an assay", style = MaterialTheme.typography.titleLarge)
            Text(
                "A crude or a cut from its distillation curve, or a plus fraction from its bulk properties, divided into " +
                    "pseudo-components as HYSYS's Oil Manager does. Each becomes a hypothetical; DWSIM's petroleum " +
                    "correlations give its critical properties.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(12) }, label = { Text("Assay name") }, singleLine = true,
                supportingText = { Text("Pseudo-components are named NBP[$name]145* (boiling point, °C)") },
                modifier = Modifier.fillMaxWidth().testTag("assay_name"),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !bulkOnly, onClick = { bulkOnly = false }, label = { Text("Distillation curve") })
                FilterChip(selected = bulkOnly, onClick = { bulkOnly = true }, label = { Text("Plus fraction (bulk)") }, modifier = Modifier.testTag("assay_bulk"))
            }

            if (!bulkOnly) {
                Text("Curve", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(DistillationCurveKinds.TBP, DistillationCurveKinds.D86, DistillationCurveKinds.D1160, DistillationCurveKinds.D2887).forEach { k ->
                        FilterChip(selected = curveType == k, onClick = { curveType = k }, label = { Text(DistillationCurveKinds.label(k)) })
                    }
                }
                if (curveType == DistillationCurveKinds.TBP) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(AssayBases.VOLUME, AssayBases.MASS, AssayBases.MOLE).forEach { b ->
                            FilterChip(selected = basis == b, onClick = { basis = b }, label = { Text(AssayBases.label(b)) })
                        }
                    }
                } else {
                    Text(
                        when (curveType) {
                            DistillationCurveKinds.D86 -> "ASTM D86 at 1 atm, liquid volume %; converted to TBP by API 3A1.1 (Daubert)."
                            DistillationCurveKinds.D1160 -> "ASTM D1160 at 10 mmHg, liquid volume %; converted by Edmister–Okamoto and Maxwell–Bonnell (give the bulk SG)."
                            else -> "ASTM D2887 simulated distillation, weight %; converted to TBP by API 3A3.1 (Daubert)."
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                percents.indices.forEach { i ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        CurveCell("Distilled, %", percents[i], null, ctx, "assay_pct_$i", Modifier.weight(1f)) { v -> percents[i] = v ?: 0.0 }
                        CurveCell("Temperature, ${t.symbol}", temps[i], t, ctx, "assay_t_$i", Modifier.weight(1.3f)) { v -> temps[i] = v }
                        IconButton(onClick = { percents.removeAt(i); temps.removeAt(i) }) { Icon(Icons.Filled.Delete, contentDescription = "Remove point ${i + 1}") }
                    }
                }
                TextButton(onClick = { percents.add(((percents.lastOrNull() ?: 0.0) + 5).coerceAtMost(100.0)); temps.add(null) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Add point")
                }
            }

            Text(if (bulkOnly) "Plus fraction" else "Bulk properties (optional, recommended)", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CurveCell("SG 60/60 °F", sg, null, ctx, "assay_sg", Modifier.weight(1f), decimals = 4) { sg = it }
                CurveCell("Molecular weight", mw, null, ctx, "assay_mw", Modifier.weight(1f)) { mw = it }
            }
            if (bulkOnly) CurveCell("Normal boiling point, ${t.symbol} (optional)", nbp, t, ctx, "assay_nbp", Modifier.fillMaxWidth()) { nbp = it }
            sg?.let { Text("API gravity ${ValueFormat.number(141.5 / it - 131.5, 1)}", style = MaterialTheme.typography.bodySmall) }
            CurveCell("Pseudo-components (2–50)", cuts, null, ctx, "assay_cuts", Modifier.fillMaxWidth(), decimals = 0) { cuts = it }

            if (!bulkOnly) {
                Text("Light ends (optional)", style = MaterialTheme.typography.titleSmall)
                if (compounds.isEmpty()) {
                    Text("Add methane to pentane (and N2, CO2, H2S) to the components first to give light ends.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(AssayBases.VOLUME, AssayBases.MASS, AssayBases.MOLE).forEach { b ->
                            FilterChip(selected = lightBasis == b, onClick = { lightBasis = b }, label = { Text(AssayBases.label(b)) })
                        }
                    }
                    compounds.forEach { c ->
                        CurveCell("$c, % of the sample", lightEnds[c]?.times(100), null, ctx, "assay_le_$c", Modifier.fillMaxWidth(), decimals = 3) { v ->
                            if (v == null || v <= 0) lightEnds.remove(c) else lightEnds[c] = v / 100
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = lightInCurve, role = Role.Switch) { lightInCurve = it },
                    ) {
                        Text("The curve includes them (cut above the light ends)", modifier = Modifier.weight(1f))
                        Switch(checked = lightInCurve, onCheckedChange = null)
                    }
                }
            }

            Button(
                onClick = { focus.clearFocus(); onCharacterise(request()) },
                enabled = state !is AssayState.Working && name.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("assay_run"),
            ) { Text(if (state is AssayState.Working) "Characterising…" else "Characterise") }

            when (state) {
                is AssayState.Failed -> Text(state.reason, color = MaterialTheme.valueColors.error, style = MaterialTheme.typography.bodySmall)
                is AssayState.Done -> AssayResultCard(state.result, unitSet, ctx, onApply)
                else -> Unit
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Close") }
        }
    }
}

/** The cuts as the engine made them, then the button that adds them. */
@Composable
private fun AssayResultCard(r: AssayResult, unitSet: UnitSet, ctx: UnitContext, onApply: (AssayResult) -> Unit) {
    val t = unitSet.unitFor(Quantity.Temperature)
    HorizontalDivider()
    Text("${r.cuts.size} pseudo-components", style = MaterialTheme.typography.titleSmall)
    Text(
        "SG ${ValueFormat.number(r.specificGravity, 4)} · API ${ValueFormat.number(141.5 / r.specificGravity - 131.5, 1)} · " +
            "MW ${ValueFormat.number(r.molecularWeight, 1)} · Watson K ${ValueFormat.number(r.watsonK, 2)}",
        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("assay_summary"),
    )
    r.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.valueColors.underSpecifiedText) }
    r.cuts.forEach { c ->
        Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(c.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                "NBP ${ValueFormat.number(t.fromSi(c.normalBoilingPoint, ctx), t.decimals)} ${t.symbol} · SG ${ValueFormat.number(c.specificGravity, 4)} · " +
                    "MW ${ValueFormat.number(c.molecularWeight, 1)} · ${ValueFormat.number(c.moleFraction * 100, 2)} mol %",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    val light = r.composition.filterKeys { k -> r.cuts.none { it.name == k } }
    if (light.isNotEmpty()) {
        Text("Light ends: " + light.entries.joinToString(" · ") { "${it.key} ${ValueFormat.number(it.value * 100, 2)} mol %" }, style = MaterialTheme.typography.bodySmall)
    }
    Button(onClick = { onApply(r) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("assay_apply")) {
        Text("Add to the fluid and make a stream")
    }
}
