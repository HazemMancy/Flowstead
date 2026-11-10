/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.flowsheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.flowstead.core.format.ValueFormat
import app.flowstead.core.model.LineService
import app.flowstead.core.model.PipeSizes
import app.flowstead.core.model.ResultView
import app.flowstead.core.model.SimObject
import app.flowstead.core.model.Spec
import app.flowstead.core.model.StreamLineSizing
import app.flowstead.core.units.Quantity
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.UnitSet
import app.flowstead.core.units.Units
import app.flowstead.ui.theme.valueColors

/** A line sizing run's answer, or why there is none (D148). [hash]: the flowsheet it was sized for. */
data class LineSizingUi(val outcome: StreamLineSizing.Outcome? = null, val error: String? = null, val hash: String? = null, val total: Double? = null)

/**
 * Line sizing of a stream, as HYSYS's stream analysis (D148): design (every size of a schedule against the project's
 * criteria, the smallest that passes marked) or rating (one size or diameter), from the stream's solved state with no
 * pipe segment. ΔP per length, velocity, ρv², Mach and the total ΔP over a length, all the engine's.
 */
@Composable
internal fun LineSizingSection(obj: SimObject, results: ResultView, hash: String, unitSet: UnitSet, ctx: UnitContext, actions: EditorActions) {
    var design by remember(obj.id) { mutableStateOf(true) }
    var service by remember(obj.id) { mutableStateOf<LineService?>(null) }
    var schedule by remember(obj.id) { mutableStateOf("40") }
    var nps by remember(obj.id) { mutableStateOf("4") }
    var id by remember(obj.id) { mutableStateOf<Double?>(null) }
    var roughness by remember(obj.id) { mutableStateOf<Double?>(null) }
    var length by remember(obj.id) { mutableStateOf<Double?>(null) }
    var busy by remember(obj.id) { mutableStateOf(false) }
    var answer by remember(obj.id) { mutableStateOf<LineSizingUi?>(null) }
    val small = smallLength(unitSet)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("line_sizing")) {
        SectionLabel("Line sizing")
        Calculated("As HYSYS's stream line sizing: from this stream's solved state, with no pipe segment. Design tries every size of a schedule against the project's criteria; rating takes one size.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = design, onClick = { design = true }, label = { Text("Design") }, modifier = Modifier.testTag("ls_design"))
            FilterChip(selected = !design, onClick = { design = false }, label = { Text("Rating") }, modifier = Modifier.testTag("ls_rating"))
        }
        Choice("Service", service?.label ?: "Automatic (${StreamLineSizing.service(fs = emptyFs, results, obj.id, null).label.lowercase()})",
            listOf("" to "Automatic (from the stream's phases)") + LineService.entries.map { it.name to it.label }) { picked ->
            service = LineService.entries.firstOrNull { it.name == picked }
        }
        Choice("Schedule", schedule, StreamLineSizing.SCHEDULES.map { it to it }) { schedule = it }
        if (!design) {
            Choice("Size, NPS", nps, PipeSizes.ALL.map { it.nps to it.nps }) { nps = it }
            QuantityField("or inside diameter", id?.let { Spec(it, small.id) }, Quantity.Length, small, ctx, "ls_id_${obj.id}", hint = "from the size") { id = it?.si?.takeIf { v -> v > 0 } }
        }
        QuantityField("Roughness", roughness?.let { Spec(it, small.id) }, Quantity.Length, small, ctx, "ls_rough_${obj.id}", hint = "0.0457 mm (steel)") { roughness = it?.si?.takeIf { v -> v >= 0 } }
        QuantityField("Total length", length?.let { Spec(it, Units.km.id) }, Quantity.Length, Units.km, ctx, "ls_len_${obj.id}", hint = "for the total ΔP") { length = it?.si?.takeIf { v -> v > 0 } }
        // The criteria every line of the service is checked against, the project's (one screen for the case).
        androidx.compose.material3.TextButton(onClick = { actions.editSizingCriteria() }, modifier = Modifier.heightIn(min = 48.dp).testTag("ls_criteria")) {
            Text("Line sizing criteria…")
        }
        OutlinedButton(
            enabled = !busy,
            onClick = {
                busy = true
                val input = StreamLineSizing.Input(design, schedule, nps.takeIf { !design }, id.takeIf { !design }, roughness ?: StreamLineSizing.DEFAULT_ROUGHNESS, length, service)
                actions.streamLineSizing(obj.id, input) { busy = false; answer = it }
            },
            modifier = Modifier.heightIn(min = 48.dp).testTag("ls_run"),
        ) { Text(if (busy) "Calculating…" else if (design) "Size the line" else "Rate the line") }
        answer?.let { a -> LineSizingTable(a, a.hash != null && a.hash != hash, unitSet, ctx) }
    }
}

private val emptyFs = app.flowstead.core.model.Flowsheet()

@Composable
private fun LineSizingTable(a: LineSizingUi, stale: Boolean, unitSet: UnitSet, ctx: UnitContext) {
    a.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.valueColors.error); return }
    val o = a.outcome ?: return
    fun fmt(v: Double?, q: Quantity) = v?.let { ValueFormat.si(it, unitSet.unitFor(q), ctx) } ?: "—"
    val g = unitSet.unitFor(Quantity.PressureGradient).symbol
    val vu = unitSet.unitFor(Quantity.Velocity).symbol
    val mf = unitSet.unitFor(Quantity.MomentumFlux).symbol
    if (stale) Calculated("The flowsheet changed since: size the line again for the current values.")
    val rec = o.recommended
    Text(
        when {
            rec != null -> "Meets the criteria: ${rec.candidate.label}" + (rec.candidate.schedule?.let { ", Sch $it" } ?: "")
            !o.criteriaSet -> "${o.service.label} service: no criteria set (Line sizing criteria) for a verdict"
            else -> "No size meets the criteria"
        },
        style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("ls_result"),
    )
    for (row in o.rows) {
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(
                row.candidate.label + " · ID ${ValueFormat.number(row.candidate.insideDiameter * 1000, 1)} mm" + if (row === rec) "  ✓" else "",
                style = MaterialTheme.typography.labelLarge,
            )
            val error = row.error
            if (error != null) Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.valueColors.error)
            else {
                Text("ΔP ${fmt(row.gradient, Quantity.PressureGradient)} $g · v ${fmt(row.velocity, Quantity.Velocity)} $vu · ρv² ${fmt(row.momentumFlux, Quantity.MomentumFlux)} $mf" +
                    (row.mach?.let { " · Mach ${ValueFormat.number(it, 3)}" } ?: ""), style = MaterialTheme.typography.bodySmall)
                a.total?.let { len ->
                    Text("Over ${fmt(len, Quantity.Length)} ${unitSet.unitFor(Quantity.Length).symbol}: " +
                        (row.totalError ?: "${fmt(row.totalDrop, Quantity.PressureDifference)} ${unitSet.unitFor(Quantity.PressureDifference).symbol}"),
                        style = MaterialTheme.typography.bodySmall)
                }
                if (o.criteriaSet) Text(if (row.passes) "Passes" else "Fails: " + row.checks.filter { !it.passes }.joinToString("; ") { it.label },
                    style = MaterialTheme.typography.bodySmall, color = if (row.passes) MaterialTheme.colorScheme.primary else MaterialTheme.valueColors.error)
            }
        }
    }
    Calculated("DWSIM's ΔP through a horizontal, adiabatic test pipe of each size, ${ValueFormat.number(StreamLineSizing.TEST_LENGTH, 0)} m for the gradient" +
        (if (a.total != null) " and one of the whole length for its ΔP" else "") + ". No fittings or elevation: add a pipe segment for those.")
}
