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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.flowstead.core.format.ValueFormat
import app.flowstead.core.model.PipeFittings
import app.flowstead.core.model.PipeSegmentSection
import app.flowstead.core.model.SimObject
import app.flowstead.core.model.SpecKey
import app.flowstead.core.units.Quantity
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.UnitSet

/**
 * A pipe made of straight runs and fittings in flow order, as HYSYS's Pipe Segment and DWSIM's pipe profile (D98).
 * Off: the pipe is one straight run of its length. On: the runs and fittings replace that length, the elevation
 * change and the increments; the pipe's size and roughness are every section's unless a run gives its own.
 */
@Composable
internal fun PipeSectionsSection(obj: SimObject, unitSet: UnitSet, ctx: UnitContext, actions: EditorActions) {
    val sections = obj.pipeSections.orEmpty()
    val on = sections.isNotEmpty()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = on, role = Role.Switch) { want ->
                actions.setPipeSections(obj.id, if (want) listOf(PipeSegmentSection(length = obj.specs[SpecKey.LENGTH]?.si, elevationChange = obj.specs[SpecKey.ELEVATION_CHANGE]?.si)) else null)
            }
            .testTag("pipe_sections_toggle"),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Sections and fittings", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Several runs (each with its own length, rise and, if it differs, size) with elbows, valves, tees, entrances and exits between them.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = on, onCheckedChange = null)
    }
    if (!on) return

    val length = unitSet.unitFor(Quantity.Length)
    val small = smallLength(unitSet)
    fun save(list: List<PipeSegmentSection>) = actions.setPipeSections(obj.id, list.ifEmpty { null })
    fun replace(i: Int, s: PipeSegmentSection) = save(sections.toMutableList().also { it[i] = s })

    sections.forEachIndexed { i, s ->
        val kind = PipeFittings.get(s.fitting)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${i + 1}. " + (kind?.let { "${it.name} · ${PipeFittings.describe(it)}" } ?: "Straight run"),
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { save(sections.filterIndexed { j, _ -> j != i }) }, modifier = Modifier.testTag("pipe_section_delete_$i")) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove section ${i + 1}")
            }
        }
        if (kind != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CurveCell("Number", s.count.toDouble(), null, ctx, "pipe_section_count_$i", Modifier.weight(1f)) { replace(i, s.copy(count = (it ?: 1.0).toInt())) }
                CurveCell("Inside diameter, ${small.symbol}", s.innerDiameter, small, ctx, "pipe_section_id_$i", Modifier.weight(1.4f)) { replace(i, s.copy(innerDiameter = it)) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CurveCell("Length, ${length.symbol}", s.length, length, ctx, "pipe_section_length_$i", Modifier.weight(1f)) { replace(i, s.copy(length = it)) }
                CurveCell("Rise, ${length.symbol}", s.elevationChange, length, ctx, "pipe_section_rise_$i", Modifier.weight(1f)) { replace(i, s.copy(elevationChange = it)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CurveCell("Inside diameter, ${small.symbol}", s.innerDiameter, small, ctx, "pipe_section_id_$i", Modifier.weight(1.4f)) { replace(i, s.copy(innerDiameter = it)) }
                CurveCell("Increments", s.increments?.toDouble(), null, ctx, "pipe_section_increments_$i", Modifier.weight(1f)) { replace(i, s.copy(increments = it?.toInt())) }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { save(sections + PipeSegmentSection(length = sections.lastOrNull { !it.isFitting }?.length)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("pipe_add_run")) {
            Text("Add straight run")
        }
    }
    Choice(
        label = "Add a fitting",
        selected = null,
        options = PipeFittings.ALL.map { it.id to "${it.name}  ·  ${PipeFittings.describe(it)}" },
        placeholder = "Choose a fitting…",
        testTag = "pipe_add_fitting",
    ) { picked -> save(sections + PipeSegmentSection(fitting = picked)) }

    // Totals, from the sections as typed: the straight length, and the L/D fittings as pipe of the same size.
    val d0 = obj.specs[SpecKey.INNER_DIAMETER]?.si
    val run = sections.filter { !it.isFitting }.sumOf { (it.length ?: 0.0) * it.count }
    val rise = sections.filter { !it.isFitting }.sumOf { (it.elevationChange ?: 0.0) * it.count }
    val equivalent = sections.mapNotNull { s -> PipeFittings.get(s.fitting)?.takeIf { it.lOverD }?.let { k -> (s.innerDiameter ?: d0)?.let { k.value * it * s.count } } }.sum()
    val k = sections.mapNotNull { s -> PipeFittings.get(s.fitting)?.takeIf { !it.lOverD }?.let { it.value * s.count } }.sum()
    Calculated(
        "Straight length ${fmt(run, length, ctx)} ${length.symbol}, rise ${fmt(rise, length, ctx)} ${length.symbol}" +
            (if (equivalent > 0) "; fittings as ${fmt(equivalent, length, ctx)} ${length.symbol} more pipe" else "") +
            (if (k > 0) "; K ${ValueFormat.number(k, 2)} velocity heads" else "") +
            ". Each fitting's loss is DWSIM's (Crane L/D or K); the profile shows it as a step.",
    )
}

private fun fmt(si: Double, unit: app.flowstead.core.units.EngUnit, ctx: UnitContext) = ValueFormat.number(unit.fromSi(si, ctx), unit.decimals)
