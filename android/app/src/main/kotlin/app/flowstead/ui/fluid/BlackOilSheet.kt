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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import app.flowstead.core.format.ValueFormat
import app.flowstead.core.model.BlackOil
import app.flowstead.core.units.UnitContext
import app.flowstead.ui.flowsheet.CurveCell
import app.flowstead.ui.theme.valueColors

/**
 * A black oil (D101), as a field engineer has it: API gravity of the stock-tank oil, gas gravity, producing GOR and
 * water cut. The Black Oil package works from these with Standing's correlations (Vasquez–Beggs above the bubble point).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlackOilSheet(initial: BlackOil?, onSave: (BlackOil) -> Unit, onDelete: (String) -> Unit, onDismiss: () -> Unit) {
    val ctx = UnitContext()
    val focus = LocalFocusManager.current
    var name by remember { mutableStateOf(initial?.name ?: "Oil-1") }
    var api by remember { mutableStateOf<Double?>(initial?.api ?: 35.0) }
    var sgg by remember { mutableStateOf<Double?>(initial?.gasSpecificGravity ?: 0.75) }
    var gor by remember { mutableStateOf<Double?>(initial?.gasOilRatio ?: 100.0) }
    var cut by remember { mutableStateOf<Double?>((initial?.waterCut ?: 0.0) * 100) }
    fun current() = BlackOil(name.trim(), BlackOil.sgFromApi(api ?: 35.0), sgg ?: 0.75, gor ?: 0.0, (cut ?: 0.0) / 100,
        initial?.viscosity1, initial?.viscosityTemperature1, initial?.viscosity2, initial?.viscosityTemperature2)
    val problem = if (api == null || sgg == null || gor == null) "Give the API gravity, gas gravity and GOR" else current().problem()

    ModalBottomSheet(onDismissRequest = { focus.clearFocus(); onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(if (initial == null) "New black oil" else "Black oil ${initial.name}", style = MaterialTheme.typography.titleLarge)
            Text(
                "A reservoir fluid from its well-test data. The Black Oil package gives the solution gas, oil formation " +
                    "volume factor and density by Standing's correlations (Vasquez–Beggs above the bubble point) and the " +
                    "viscosity by Beggs–Robinson.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(name, { name = it.take(24) }, label = { Text("Name") }, singleLine = true, enabled = initial == null, modifier = Modifier.fillMaxWidth().testTag("bo_name"))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CurveCell("Oil API gravity", api, null, ctx, "bo_api", Modifier.weight(1f)) { api = it }
                CurveCell("Gas SG (air = 1)", sgg, null, ctx, "bo_sgg", Modifier.weight(1f), decimals = 3) { sgg = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CurveCell("GOR, Sm³/m³", gor, null, ctx, "bo_gor", Modifier.weight(1f)) { gor = it }
                CurveCell("Water cut, %", cut, null, ctx, "bo_cut", Modifier.weight(1f)) { cut = it }
            }
            gor?.let { Text("GOR ${ValueFormat.number(it * 5.6146, 0)} scf/STB; oil SG ${ValueFormat.number(BlackOil.sgFromApi(api ?: 35.0), 4)}", style = MaterialTheme.typography.bodySmall) }
            problem?.let { Text(it, color = MaterialTheme.valueColors.underSpecifiedText, style = MaterialTheme.typography.bodySmall) }
            Button(onClick = { focus.clearFocus(); if (problem == null) onSave(current()) }, enabled = problem == null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("bo_save")) {
                Text(if (initial == null) "Add to components" else "Save")
            }
            if (initial != null) TextButton(onClick = { onDelete(initial.name) }) { Text("Delete ${initial.name}", color = MaterialTheme.valueColors.error) }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Close") }
        }
    }
}
