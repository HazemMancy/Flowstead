/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.fluid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.flowstead.core.api.HypotheticalProperties
import app.flowstead.core.model.DisplayValue
import app.flowstead.core.model.Hypothetical
import app.flowstead.core.model.Spec
import app.flowstead.core.units.EngUnit
import app.flowstead.core.units.Quantity
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.UnitSet
import app.flowstead.core.units.Units
import app.flowstead.ui.components.ValueText
import app.flowstead.ui.flowsheet.QuantityField
import app.flowstead.ui.theme.valueColors

/** Where an estimate stands for the hypothetical being edited. */
sealed interface EstimateState {
    data object Idle : EstimateState
    data object Working : EstimateState
    data class Done(val input: Hypothetical, val properties: HypotheticalProperties) : EstimateState
    data class Failed(val reason: String) : EstimateState
}

/**
 * Defines a hypothetical component, as in HYSYS: name, then the normal boiling point or the molecular weight and,
 * if known, the specific gravity; critical constants override the estimates. "Estimate" shows what the engine's
 * correlations make of it before saving.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HypotheticalSheet(
    initial: Hypothetical?,
    unitSet: UnitSet,
    estimate: EstimateState,
    onEstimate: (Hypothetical) -> Unit,
    onSave: (Hypothetical) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = UnitContext()
    val focus = LocalFocusManager.current
    val pressureUnit = absolute(unitSet.unitFor(Quantity.Pressure))
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    fun spec(si: Double?, unit: String) = si?.let { Spec(it, unit) }
    var nbp by remember { mutableStateOf(spec(initial?.normalBoilingPoint, unitSet.unitFor(Quantity.Temperature).id)) }
    var mw by remember { mutableStateOf(spec(initial?.molecularWeight, Units.kgPerKmol.id)) }
    var sg by remember { mutableStateOf(spec(initial?.specificGravity, Units.ratio.id)) }
    var tc by remember { mutableStateOf(spec(initial?.criticalTemperature, unitSet.unitFor(Quantity.Temperature).id)) }
    var pc by remember { mutableStateOf(spec(initial?.criticalPressure, pressureUnit.id)) }
    var w by remember { mutableStateOf(spec(initial?.acentricFactor, Units.ratio.id)) }
    fun current() = Hypothetical(name.trim(), nbp?.si, mw?.si, sg?.si, tc?.si, pc?.si, w?.si)
    val problem = current().problem()

    // A button tap doesn't take focus from the field being typed in, so commit that field first.
    fun withCommitted(action: (Hypothetical) -> Unit) {
        focus.clearFocus()
        val h = current()
        if (h.problem() == null) action(h)
    }

    ModalBottomSheet(onDismissRequest = { focus.clearFocus(); onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(if (initial == null) "New hypothetical" else "Hypothetical ${initial.name}", style = MaterialTheme.typography.titleLarge)
            Text(
                "A pseudo-component for a petroleum cut or an unknown compound. Give the normal boiling point or the " +
                    "molecular weight (both is better) and the specific gravity if you have it; DWSIM's petroleum " +
                    "correlations estimate the rest.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(32) },
                label = { Text("Name") },
                placeholder = { Text("e.g. C7+ or NBP_150") },
                singleLine = true,
                enabled = initial == null,
                modifier = Modifier.fillMaxWidth().testTag("hypo_name"),
            )
            QuantityField("Normal boiling point", nbp, Quantity.Temperature, unitSet.unitFor(Quantity.Temperature), ctx, "hypo.nbp", testTag = "hypo_nbp") { nbp = it }
            QuantityField("Molecular weight", mw, Quantity.MolecularWeight, Units.kgPerKmol, ctx, "hypo.mw", testTag = "hypo_mw") { mw = it }
            QuantityField("Specific gravity (60 °F/60 °F)", sg, Quantity.Ratio, Units.ratio, ctx, "hypo.sg", hint = "estimated if empty", testTag = "hypo_sg") { sg = it }
            Text("Override the estimates (optional)", style = MaterialTheme.typography.titleSmall)
            QuantityField("Critical temperature", tc, Quantity.Temperature, unitSet.unitFor(Quantity.Temperature), ctx, "hypo.tc", hint = "Riazi–Daubert") { tc = it }
            QuantityField("Critical pressure", pc, Quantity.Pressure, pressureUnit, ctx, "hypo.pc", hint = "Riazi–Daubert") { pc = it }
            QuantityField("Acentric factor", w, Quantity.Ratio, Units.ratio, ctx, "hypo.w", hint = "fitted to the boiling point") { w = it }
            problem?.let { Text(it, color = MaterialTheme.valueColors.underSpecifiedText, style = MaterialTheme.typography.bodySmall) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { withCommitted(onEstimate) },
                    enabled = problem == null && estimate !is EstimateState.Working,
                    modifier = Modifier.weight(1f).testTag("hypo_estimate"),
                ) { Text(if (estimate is EstimateState.Working) "Estimating…" else "Estimate") }
                Button(onClick = { withCommitted(onSave) }, enabled = problem == null, modifier = Modifier.weight(1f).testTag("hypo_save")) {
                    Text(if (initial == null) "Add to components" else "Save")
                }
            }
            when (estimate) {
                is EstimateState.Done -> if (estimate.input == current()) EstimateCard(estimate.properties, unitSet, ctx)
                is EstimateState.Failed -> Text(estimate.reason, color = MaterialTheme.valueColors.error, style = MaterialTheme.typography.bodySmall)
                else -> Unit
            }
            if (initial != null) {
                TextButton(onClick = { onDelete(initial.name) }) { Text("Delete ${initial.name}", color = MaterialTheme.valueColors.error) }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Close") }
        }
    }
}

/** The engine's constants for the hypothetical; "est." marks what its correlations supplied. */
@Composable
private fun EstimateCard(p: HypotheticalProperties, unitSet: UnitSet, ctx: UnitContext) {
    HorizontalDivider()
    Text("Engine values", style = MaterialTheme.typography.titleSmall)
    val rows = listOf(
        Triple("Normal boiling point", "normalBoilingPoint", p.normalBoilingPoint to unitSet.unitFor(Quantity.Temperature)),
        Triple("Molecular weight", "molecularWeight", p.molecularWeight to Units.kgPerKmol),
        Triple("Specific gravity", "specificGravity", p.specificGravity to Units.ratio),
        Triple("Critical temperature", "criticalTemperature", p.criticalTemperature to unitSet.unitFor(Quantity.Temperature)),
        Triple("Critical pressure", "criticalPressure", p.criticalPressure to absolute(unitSet.unitFor(Quantity.Pressure))),
        Triple("Acentric factor", "acentricFactor", p.acentricFactor to Units.ratio),
        Triple("Watson K", "", p.watsonK to Units.ratio),
    )
    for ((label, key, value) in rows) {
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label + if (key in p.estimated) "  · est." else "", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            ValueText(DisplayValue.Calculated(value.first), value.second, ctx = ctx)
        }
    }
    if (p.formula.isNotBlank()) Text("Approximate formula ${p.formula}", style = MaterialTheme.typography.bodySmall)
    Text(
        "The acentric factor is fitted so Peng–Robinson boils the component at its normal boiling point, and the " +
            "liquid-density parameters so it matches the specific gravity.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Critical pressures are absolute: show barg/psig/kPag users the matching absolute unit. */
private fun absolute(unit: EngUnit): EngUnit = when (unit) {
    Units.barg -> Units.bara
    Units.psig -> Units.psia
    Units.kPag -> Units.kPa
    else -> unit
}
