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
import app.flowstead.core.model.PvtTuning
import app.flowstead.core.api.PvtRegressionResult
import app.flowstead.core.api.PvtObservation
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

/** The last tuning of a stream's fluid to lab data (D53); [applied]: its constants were written into the flowsheet. */
data class TuningUi(
    val streamId: String,
    val loading: Boolean = false,
    val result: PvtRegressionResult? = null,
    val error: String? = null,
    val applied: Boolean = false,
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
    tuning: TuningUi? = null,
    onTune: (temperature: Double, observations: List<PvtObservation>) -> Unit = { _, _ -> },
    onApplyTuning: () -> Unit = {},
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
                            QuantityField("T, stage ${i + 1}", Spec(st.temperature, unitSet.unitFor(Quantity.Temperature).id), Quantity.Temperature, unitSet.unitFor(Quantity.Temperature), ctx, "pvt_st_t_$i") { s ->
                                s?.let { stages = stages.toMutableList().also { l -> l[i] = st.copy(temperature = it.si) } }
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            QuantityField("P, stage ${i + 1}", Spec(st.pressure, unitSet.unitFor(Quantity.Pressure).id), Quantity.Pressure, unitSet.unitFor(Quantity.Pressure), ctx, "pvt_st_p_$i") { s ->
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
            TuningSection(temperature?.si, tuning, unitSet, ctx, onTune, onApplyTuning)
        }
    }
}

/**
 * Regression to lab data, as a PVT package tunes a characterised fluid: the user types the measurements, the engine
 * adjusts the plus fraction's Tc, Pc and ω (each within ±20 %), and shows the fit before and after; nothing changes in
 * the flowsheet until the user applies the tuned constants.
 */
@Composable
private fun TuningSection(
    temperature: Double?,
    tuning: TuningUi?,
    unitSet: UnitSet,
    ctx: UnitContext,
    onTune: (Double, List<PvtObservation>) -> Unit,
    onApply: () -> Unit,
) {
    SectionLabel("Tune to lab data")
    Calculated("Type your lab measurements; Flowstead adjusts the plus fraction (hypothetical components: Tc, Pc, ω, and kij with methane) to match them.")
    var rows by remember { mutableStateOf(listOf<PvtObservation>()) }
    rows.forEachIndexed { i, o ->
        val kind = PvtTuning.kind(o) ?: PvtTuning.KINDS.first()
        fun set(n: PvtObservation) { rows = rows.toMutableList().also { it[i] = n } }
        Choice("Measurement ${i + 1}", kind.label, PvtTuning.KINDS.map { it.id to it.label }) { id ->
            val k = PvtTuning.KINDS.first { it.id == id }
            set(o.copy(experiment = k.experiment, key = k.key, pressure = if (k.atPressure) o.pressure else null))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (kind.atPressure) Column(Modifier.weight(1f)) {
                QuantityField("At pressure", o.pressure?.let { Spec(it, unitSet.unitFor(Quantity.Pressure).id) }, Quantity.Pressure, unitSet.unitFor(Quantity.Pressure), ctx, "tune_p_$i") { v ->
                    set(o.copy(pressure = v?.si))
                }
            }
            Column(Modifier.weight(1f)) {
                QuantityField("Lab value", o.value.takeIf { it != 0.0 }?.let { Spec(it, unitSet.unitFor(kind.quantity).id) }, kind.quantity, unitSet.unitFor(kind.quantity), ctx, "tune_v_$i") { v ->
                    set(o.copy(value = v?.si ?: 0.0))
                }
            }
            IconButton(onClick = { rows = rows.filterIndexed { j, _ -> j != i } }) { Icon(Icons.Filled.Close, contentDescription = "Remove measurement ${i + 1}") }
        }
    }
    TextButton(onClick = { rows = rows + PvtTuning.KINDS.first().let { PvtObservation(it.experiment, it.key, 0.0) } }, modifier = Modifier.testTag("tune_add")) { Text("Add a measurement") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = { temperature?.let { onTune(it, rows) } },
            enabled = temperature != null && rows.isNotEmpty() && tuning?.loading != true,
            modifier = Modifier.heightIn(min = 48.dp).testTag("tune_run"),
        ) { Text("Tune") }
        if (tuning?.loading == true) {
            Spacer(Modifier.width(12.dp))
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Regressing: each step re-runs the experiments…", style = MaterialTheme.typography.bodySmall)
        }
    }
    tuning?.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.valueColors.error) }
    val r = tuning?.result ?: return
    fun pct(v: Double?) = v?.let { "${ValueFormat.number(it * 100, 2)} %" } ?: "—"
    Text("RMS error ${pct(r.errorBefore)} → ${pct(r.errorAfter)} in ${r.iterations} iterations", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("tune_result"))
    r.multipliers.forEach { (name, m) -> Text("$name × ${ValueFormat.number(m, 4)}", style = MaterialTheme.typography.bodyMedium) }
    r.interactionParameters.filter { it.a == "Methane" || it.b == "Methane" }.forEach { k ->
        Text("kij ${k.a}–${k.b} = ${ValueFormat.number(k.kij, 4)}", style = MaterialTheme.typography.bodyMedium)
    }
    r.matches.forEach { m ->
        val kind = PvtTuning.kind(m.observation)
        val u = unitSet.unitFor(kind?.quantity ?: Quantity.Ratio)
        fun v(x: Double?) = x?.let { "${ValueFormat.number(u.fromSi(it, ctx), u.decimals + 1)} ${u.symbol}".trim() } ?: "—"
        val at = m.observation.pressure?.let { p -> unitSet.unitFor(Quantity.Pressure).let { pu -> " at ${ValueFormat.number(pu.fromSi(p, ctx), pu.decimals)} ${pu.symbol}" } } ?: ""
        Text("${kind?.label ?: m.observation.key}$at: lab ${v(m.observation.value)}, before ${v(m.before)}, after ${v(m.after)}", style = MaterialTheme.typography.bodySmall)
    }
    r.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.valueColors.underSpecifiedText) }
    if (tuning?.applied == true) Text("Applied: the hypotheticals now carry the tuned critical constants (undo to go back).", style = MaterialTheme.typography.bodySmall)
    else Button(onClick = onApply, modifier = Modifier.heightIn(min = 48.dp).testTag("tune_apply")) { Text("Apply to the fluid") }
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
