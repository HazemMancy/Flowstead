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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.flowstead.core.format.ValueFormat
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.InletDevice
import app.flowstead.core.model.MistEliminator
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.ResultView
import app.flowstead.core.model.SeparatorSizing
import app.flowstead.core.model.SeparatorSizingInput
import app.flowstead.core.model.SimObject
import app.flowstead.core.model.Spec
import app.flowstead.core.units.Quantity
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.UnitSet
import app.flowstead.ui.theme.NumberStyles
import app.flowstead.ui.theme.valueColors

/**
 * Separator sizing (D61): the user's basis, with the data library's defaults, and the vessel, levels and nozzles it
 * gives from the solved outlet streams. Nothing is shown until the separator has been solved.
 */
@Composable
internal fun SeparatorSizingSection(fs: Flowsheet, obj: SimObject, results: ResultView, unitSet: UnitSet, ctx: UnitContext, actions: EditorActions) {
    HorizontalDivider()
    Text("Vessel sizing", style = MaterialTheme.typography.titleSmall)
    val input = obj.sizing
    if (input == null) {
        Calculated("Size the vessel from the solved flows: diameter, length, liquid levels, nozzles and mist eliminator (GPSA, Svrcek & Monnery, API 12J).")
        TextButton(onClick = { actions.setSizing(obj.id, SeparatorSizingInput()) }, modifier = Modifier.testTag("size_vessel")) { Text("Size this vessel") }
        return
    }
    fun save(x: SeparatorSizingInput) = actions.setSizing(obj.id, x)
    val threePhase = obj.type == ObjectType.ThreePhaseSeparator
    val key = "sep_${obj.id}"
    val time = unitSet.unitFor(Quantity.Time)
    Choice("Orientation", if (input.horizontal) "Horizontal" else "Vertical", listOf("v" to "Vertical", "h" to "Horizontal")) { save(input.copy(horizontal = it == "h")) }
    Choice("Mist eliminator", input.mistEliminator.label, MistEliminator.entries.map { it.name to "${it.label} · K ${it.k} m/s" }) {
        save(input.copy(mistEliminator = MistEliminator.valueOf(it)))
    }
    Choice("Inlet device", input.inletDevice.label, InletDevice.entries.map { it.name to "${it.label} · ρv² ≤ ${it.maxMomentum.toInt()} Pa" }) {
        save(input.copy(inletDevice = InletDevice.valueOf(it)))
    }
    val pressure = SeparatorSizing.loads(fs, results, obj).first?.pressure
    PlainNumber(
        "Souders–Brown K", input.k, "${key}_k", "m/s",
        hint = pressure?.let { "library: " + ValueFormat.number(SeparatorSizing.k(input.copy(k = null), it), 3) } ?: "library",
    ) { save(input.copy(k = it?.takeIf { v -> v > 0 })) }
    if (threePhase) {
        QuantityField("Oil retention time", Spec(input.oilRetention, time.id), Quantity.Time, time, ctx, "${key}_oil") { s -> s?.si?.takeIf { it > 0 }?.let { save(input.copy(oilRetention = it)) } }
        QuantityField("Water retention time", Spec(input.waterRetention, time.id), Quantity.Time, time, ctx, "${key}_water") { s -> s?.si?.takeIf { it > 0 }?.let { save(input.copy(waterRetention = it)) } }
        PlainNumber("Water droplet out of the oil", input.waterDroplet * 1e6, "${key}_wd", "µm") { it?.takeIf { v -> v > 0 }?.let { v -> save(input.copy(waterDroplet = v * 1e-6)) } }
        PlainNumber("Oil droplet out of the water", input.oilDroplet * 1e6, "${key}_od", "µm") { it?.takeIf { v -> v > 0 }?.let { v -> save(input.copy(oilDroplet = v * 1e-6)) } }
    } else {
        QuantityField("Hold-up time (NLL–LLL)", Spec(input.holdupTime, time.id), Quantity.Time, time, ctx, "${key}_hold") { s -> s?.si?.takeIf { it > 0 }?.let { save(input.copy(holdupTime = it)) } }
    }
    QuantityField("Surge time (HLL–NLL)", Spec(input.surgeTime, time.id), Quantity.Time, time, ctx, "${key}_surge") { s -> s?.si?.takeIf { it >= 0 }?.let { save(input.copy(surgeTime = it)) } }
    if (input.horizontal) {
        PlainNumber("L/D", input.lengthToDiameter, "${key}_ld", hint = pressure?.let { "by pressure: " + ValueFormat.number(SeparatorSizing.slenderness(it), 1) } ?: "by pressure") {
            save(input.copy(lengthToDiameter = it?.takeIf { v -> v > 1 }))
        }
    }

    val (loads, why) = SeparatorSizing.loads(fs, results, obj)
    if (loads == null) {
        Calculated(why ?: "Solve the flowsheet first")
    } else {
        val r = SeparatorSizing.size(loads, input, threePhase)
        val length = unitSet.unitFor(Quantity.Length)
        val velocity = unitSet.unitFor(Quantity.Velocity)
        val volume = unitSet.unitFor(Quantity.Volume)
        if (results.stale) Calculated("The flowsheet changed since the solve: solve again to size on the current flows.")
        SectionLabel("Vessel")
        Line("Inside diameter", ValueFormat.si(r.diameter, length, ctx))
        Line(if (r.horizontal) "Length (T/T)" else "Height (T/T)", ValueFormat.si(r.length, length, ctx))
        Line(if (r.horizontal) "L/D" else "H/D", ValueFormat.number(r.slenderness, 2))
        Line("K used", ValueFormat.number(r.k, 3) + " m/s")
        if (r.gasVelocity > 0) {
            Line("Allowable gas velocity", ValueFormat.si(r.maxGasVelocity, velocity, ctx))
            Line("Gas velocity", ValueFormat.si(r.gasVelocity, velocity, ctx))
        }
        Line("Liquid volume (retention and surge)", ValueFormat.si(r.liquidVolume, volume, ctx))
        r.mistEliminatorArea?.let { Line("Mist eliminator face area", ValueFormat.number(it, 2) + " m²") }
        if (r.levels.isNotEmpty()) {
            SectionLabel("Levels from the bottom")
            for (l in r.levels) Line(l.name, ValueFormat.si(l.height, length, ctx))
        }
        SectionLabel("Nozzles")
        for (n in r.nozzles) Line(n.name, (n.nps?.let { "NPS $it" } ?: "above NPS 36") + " · " + n.limit)
        (r.notes + r.warnings).forEach { Calculated(it) }
        Calculated("Defaults are the data library's typical values (GPSA section 7, Svrcek & Monnery 1993, API 12J); replace them with your company's practice.")
    }
    TextButton(onClick = { actions.setSizing(obj.id, null) }) { Text("Remove sizing") }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(value, style = NumberStyles.value, color = MaterialTheme.valueColors.calculated)
        }
    }
}
