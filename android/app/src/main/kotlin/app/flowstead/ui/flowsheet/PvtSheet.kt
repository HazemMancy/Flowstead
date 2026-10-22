/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.flowsheet

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.flowstead.core.api.PvtExperiments
import app.flowstead.core.api.PvtResult
import app.flowstead.core.api.SeparatorStage
import app.flowstead.core.format.ValueFormat
import app.flowstead.core.model.DisplayValue
import app.flowstead.core.model.PvtTables
import app.flowstead.core.model.ResultView
import app.flowstead.core.model.SimObject
import app.flowstead.core.model.Spec
import app.flowstead.core.model.SpecKey
import app.flowstead.core.units.Quantity
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.UnitSet
import app.flowstead.ui.components.PlotsSection
import app.flowstead.ui.theme.valueColors

/** The last PVT experiment run on a stream (D53). [stale]: the flowsheet changed since, so the fluid may have too. */
data class PvtUi(
    val streamId: String,
    val experiment: String,
    val loading: Boolean = false,
    val result: PvtResult? = null,
    val error: String? = null,
    val stale: Boolean = false,
)

/**
 * The PVT laboratory (D53) on one stream's fluid: pick an experiment and the reservoir temperature, run it on the
 * engine, read the table and the plots. Every number is from DWSIM flashes with equation-of-state volumes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PvtSheet(
    stream: SimObject,
    results: ResultView,
    state: PvtUi?,
    unitSet: UnitSet,
    ctx: UnitContext,
    onRun: (experiment: String, temperature: Double, stages: List<SeparatorStage>) -> Unit,
    onDismiss: () -> Unit,
) {
    var experiment by rememberSaveable { mutableStateOf(state?.experiment ?: PvtExperiments.CCE) }
    // Reservoir temperature: the stream's own (solved, else as specified) until the user types another.
    val streamT = (results.value(stream.id, "temperature") as? DisplayValue.Calculated)?.si ?: stream.specs[SpecKey.TEMPERATURE]?.si
    var temperature by remember(stream.id) { mutableStateOf(streamT?.let { Spec(it, unitSet.unitFor(Quantity.Temperature).id) }) }
    var stages by remember(stream.id) { mutableStateOf(listOf(SeparatorStage(310.93, 7e5))) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
                .testTag("pvt"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("PVT analysis · ${stream.tag}", style = MaterialTheme.typography.titleLarge)
            Calculated("The stream's fluid in a PVT cell at reservoir temperature. Volumes from the equation of state for both phases; gas standard volumes at 60 °F and 101.325 kPa.")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(PvtExperiments.SATURATION, PvtExperiments.CCE, PvtExperiments.CVD, PvtExperiments.DL, PvtExperiments.SEPARATOR).forEach { e ->
                    FilterChip(selected = e == experiment, onClick = { experiment = e }, label = { Text(PvtTables.experimentLabel(e)) })
                }
            }
            QuantityField(
                "Reservoir temperature", temperature, Quantity.Temperature, unitSet.unitFor(Quantity.Temperature), ctx, "pvt_t_${stream.id}",
                testTag = "pvt_temperature",
            ) { temperature = it }
            if (experiment == PvtExperiments.SEPARATOR) {
                SectionLabel("Separator stages (then the stock tank at 60 °F, 1 atm)")
                stages.forEachIndexed { i, st ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            QuantityField("T, stage ${i + 1}", Spec(st.temperature, "K"), Quantity.Temperature, unitSet.unitFor(Quantity.Temperature), ctx, "pvt_st_t_$i") { s ->
                                s?.let { stages = stages.toMutableList().also { l -> l[i] = st.copy(temperature = it.si) } }
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            QuantityField("P, stage ${i + 1}", Spec(st.pressure, "Pa"), Quantity.Pressure, unitSet.unitFor(Quantity.Pressure), ctx, "pvt_st_p_$i") { s ->
                                s?.let { stages = stages.toMutableList().also { l -> l[i] = st.copy(pressure = it.si) } }
                            }
                        }
                        if (stages.size > 1) IconButton(onClick = { stages = stages.filterIndexed { j, _ -> j != i } }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove stage ${i + 1}")
                        }
                    }
                }
                if (stages.size < 4) TextButton(onClick = { stages = stages + SeparatorStage(stages.last().temperature, stages.last().pressure / 4) }) { Text("Add a stage") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { temperature?.let { onRun(experiment, it.si, stages) } },
                    enabled = temperature != null && state?.loading != true,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("pvt_run"),
                ) { Text("Run") }
                if (state?.loading == true) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Flashing the cell…", style = MaterialTheme.typography.bodySmall)
                }
            }
            state?.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.valueColors.error) }
            state?.let { st -> st.result?.let { PvtResults(it, st.stale, unitSet, ctx) } }
        }
    }
}

@Composable
private fun PvtResults(r: PvtResult, stale: Boolean, unitSet: UnitSet, ctx: UnitContext) {
    SectionLabel(PvtTables.experimentLabel(r.experiment))
    if (stale) Text("⚠ The flowsheet changed since this ran.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.valueColors.underSpecifiedText)
    val p = unitSet.unitFor(Quantity.Pressure)
    fun show(key: String, v: Double): String {
        val u = unitSet.unitFor(PvtTables.quantity(key))
        return "${ValueFormat.number(u.fromSi(v, ctx), if (PvtTables.quantity(key) == Quantity.Ratio) 4 else u.decimals)} ${u.symbol}".trimEnd()
    }
    r.saturationPressure?.let {
        Text(
            (if (r.saturationKind == "dew") "Dew point" else "Bubble point") + ": ${ValueFormat.number(p.fromSi(it, ctx), p.decimals)} ${p.symbol}" +
                if (r.saturationKind == "dew") " (gas condensate)" else " (oil)",
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("pvt_saturation"),
        )
    }
    r.summary.filterKeys { it != "saturationPressure" }.forEach { (k, v) ->
        Row(Modifier.fillMaxWidth()) {
            Text(PvtTables.label(k), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(show(k, v), style = MaterialTheme.typography.bodyMedium)
        }
    }
    r.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.valueColors.underSpecifiedText) }
    PlotsSection(PvtTables.plots(r), unitSet, ctx, stale, "pvt_${r.experiment}")
    if (r.columns.isEmpty()) return
    val scroll = rememberScrollState()
    Column(Modifier.horizontalScroll(scroll).testTag("pvt_table")) {
        Row {
            r.columns.forEach { k ->
                val u = unitSet.unitFor(PvtTables.quantity(k))
                Text(
                    PvtTables.label(k) + if (u.symbol.isNotBlank() && PvtTables.quantity(k) != Quantity.Ratio) ", ${u.symbol}" else "",
                    style = MaterialTheme.typography.labelMedium, maxLines = 3,
                    modifier = Modifier.width(104.dp).padding(end = 8.dp, bottom = 4.dp),
                )
            }
        }
        val rows = r.table.values.firstOrNull()?.size ?: 0
        for (i in 0 until rows) {
            Row {
                r.columns.forEach { k ->
                    val v = r.table[k]?.getOrNull(i)
                    val u = unitSet.unitFor(PvtTables.quantity(k))
                    Text(
                        if (v == null) "—" else ValueFormat.number(u.fromSi(v, ctx), if (PvtTables.quantity(k) == Quantity.Ratio) 4 else u.decimals),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(104.dp).padding(end = 8.dp, top = 2.dp, bottom = 2.dp),
                    )
                }
            }
        }
    }
}
