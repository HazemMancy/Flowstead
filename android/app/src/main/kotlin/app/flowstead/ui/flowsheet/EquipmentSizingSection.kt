/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.flowsheet

import androidx.compose.foundation.layout.Arrangement
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
import app.flowstead.core.model.DataLibrary
import app.flowstead.core.model.EquipmentSizing
import app.flowstead.core.model.EquipmentSizingInput
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.ResultView
import app.flowstead.core.model.SimObject
import app.flowstead.core.model.Spec
import app.flowstead.core.units.Quantity
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.UnitSet
import app.flowstead.ui.theme.NumberStyles
import app.flowstead.ui.theme.valueColors

/**
 * Sizing of exchangers, heaters, coolers, air coolers, machines and tanks (D62): the user's basis over the data
 * library's defaults, and what it gives from the solved duty, power, temperatures and flows.
 */
@Composable
internal fun EquipmentSizingSection(fs: Flowsheet, obj: SimObject, results: ResultView, unitSet: UnitSet, ctx: UnitContext, actions: EditorActions) {
    HorizontalDivider()
    val machine = obj.type in setOf(ObjectType.Pump, ObjectType.Compressor, ObjectType.Expander)
    val column = obj.type in EquipmentSizing.COLUMNS
    Text(if (machine) "Driver sizing" else if (column) "Tray sizing" else "Equipment sizing", style = MaterialTheme.typography.titleSmall)
    val input = obj.equipmentSizing
    if (input == null) {
        Calculated(
            when {
                machine -> "The driver from the solved power: API 610 / 617 margin and the next standard IEC motor."
                column -> "Tray column diameter and height from each tray's solved vapour and liquid traffic (Fair's flooding correlation, as HYSYS's tray sizing)."
                obj.type == ObjectType.Tank -> "The tank volume and dimensions from the liquid flow and a residence time."
                else -> "The heat-transfer area from the solved duty and temperatures, with U from the data library or your own."
            },
        )
        TextButton(onClick = { actions.setEquipmentSizing(obj.id, EquipmentSizingInput()) }, modifier = Modifier.testTag("size_equipment")) { Text("Size this equipment") }
        return
    }
    fun save(x: EquipmentSizingInput) = actions.setEquipmentSizing(obj.id, x)
    val key = "eqs_${obj.id}"
    val temp = unitSet.unitFor(Quantity.Temperature)
    when {
        machine -> PlainNumber("Driver margin", input.margin?.let { it * 100 }, "${key}_m", "%", hint = "API") {
            save(input.copy(margin = it?.takeIf { v -> v >= 100 }?.div(100)))
        }
        column -> {
            val length = unitSet.unitFor(Quantity.Length)
            QuantityField("Tray spacing", input.traySpacing?.let { Spec(it, length.id) }, Quantity.Length, length, ctx, "${key}_ts", hint = "610 mm (24 in)") {
                save(input.copy(traySpacing = it?.si?.takeIf { v -> v > 0.2 && v < 1.5 }))
            }
            PlainNumber("Design flood", input.floodFraction?.let { it * 100 }, "${key}_ff", "%", hint = "80") {
                save(input.copy(floodFraction = it?.takeIf { v -> v > 30 && v <= 100 }?.div(100)))
            }
            PlainNumber("Downcomer area", input.downcomerFraction?.let { it * 100 }, "${key}_dc", "% of tower", hint = "12") {
                save(input.copy(downcomerFraction = it?.takeIf { v -> v >= 5 && v <= 40 }?.div(100)))
            }
            PlainNumber("Tray efficiency", input.trayEfficiency?.let { it * 100 }, "${key}_eff", "%", hint = "70") {
                save(input.copy(trayEfficiency = it?.takeIf { v -> v > 10 && v <= 100 }?.div(100)))
            }
            PlainNumber("System (foaming) factor", input.systemFactor, "${key}_sf", hint = "1.0 non-foaming") {
                save(input.copy(systemFactor = it?.takeIf { v -> v > 0.3 && v <= 1.0 }))
            }
        }
        obj.type == ObjectType.Tank -> {
            val time = unitSet.unitFor(Quantity.Time)
            QuantityField("Residence time", input.residenceTime?.let { Spec(it, time.id) }, Quantity.Time, time, ctx, "${key}_t", hint = "10 min") {
                save(input.copy(residenceTime = it?.si?.takeIf { v -> v > 0 }))
            }
            PlainNumber("Working fill", input.fillFraction?.let { it * 100 }, "${key}_f", "%", hint = "85") {
                save(input.copy(fillFraction = it?.takeIf { v -> v > 0 && v <= 100 }?.div(100)))
            }
        }
        else -> {
            val current = DataLibrary.service(input.service ?: EquipmentSizing.defaultService(obj.type))
            Choice("Service (data library)", current?.let { "${it.label} · ${it.low.toInt()}–${it.high.toInt()} W/m²·K" },
                DataLibrary.U_SERVICES.map { it.id to "${it.group}: ${it.label} (${it.low.toInt()}–${it.high.toInt()})" }) {
                save(input.copy(service = it))
            }
            PlainNumber("U (overall, clean + fouling)", input.u, "${key}_u", "W/m²·K", hint = current?.let { "typical ${it.typical.toInt()}" }) {
                save(input.copy(u = it?.takeIf { v -> v > 0 }))
            }
            if (obj.type != ObjectType.HeatExchanger) {
                val what = if (obj.type == ObjectType.AirCooler) "Air" else "Utility"
                QuantityField("$what inlet temperature", input.utilityIn?.let { Spec(it, temp.id) }, Quantity.Temperature, temp, ctx, "${key}_ui", hint = "data library") {
                    save(input.copy(utilityIn = it?.si))
                }
                QuantityField("$what outlet temperature", input.utilityOut?.let { Spec(it, temp.id) }, Quantity.Temperature, temp, ctx, "${key}_uo", hint = "data library") {
                    save(input.copy(utilityOut = it?.si))
                }
            }
        }
    }
    val r = EquipmentSizing.size(fs, results, obj, input)
    if (r == null) {
        Calculated("Solve the flowsheet (and connect the streams) to size it.")
    } else {
        if (results.stale) Calculated("The flowsheet changed since the solve: solve again to size on the current values.")
        for (l in r.lines) {
            val q = l.quantity
            val shown = l.text ?: l.si?.let { v ->
                when (q) {
                    null, Quantity.Ratio -> ValueFormat.number(v, 3)
                    Quantity.Count -> v.toInt().toString()
                    else -> ValueFormat.si(v, unitSet.unitFor(q), ctx)
                }
            } ?: "—"
            Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(l.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(shown, style = NumberStyles.value, color = MaterialTheme.valueColors.calculated)
            }
        }
        (r.notes + r.warnings).forEach { Calculated(it) }
    }
    TextButton(onClick = { actions.setEquipmentSizing(obj.id, null) }) { Text("Remove sizing") }
}
