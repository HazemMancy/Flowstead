/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.flowsheet

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.flowstead.core.api.DistillationCurveKinds
import app.flowstead.core.api.PetroleumKeys
import app.flowstead.core.api.PetroleumPropertiesResult
import app.flowstead.core.format.ValueFormat
import app.flowstead.core.model.SimObject
import app.flowstead.core.units.EngUnit
import app.flowstead.core.units.Quantity
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.UnitSet
import app.flowstead.core.units.Units
import app.flowstead.ui.theme.valueColors

/** A stream's distillation curves and product properties, as the engine returned them (D99). */
data class PetroleumUi(
    val streamId: String,
    val loading: Boolean = false,
    val result: PetroleumPropertiesResult? = null,
    val error: String? = null,
    val stale: Boolean = false,
)

/**
 * HYSYS's Petroleum Assay / boiling-point curves page for a stream: DWSIM 10's TBP, D86, D1160, D2887 and EFV curves,
 * and the API correlations for the product properties. Only what the engine returned is shown.
 */
@Composable
internal fun PetroleumSection(obj: SimObject, state: PetroleumUi?, unitSet: UnitSet, ctx: UnitContext, actions: EditorActions) {
    SectionLabel("Distillation curves and product properties")
    val t = unitSet.unitFor(Quantity.Temperature)
    val r = state?.result
    if (r != null) {
        if (state.stale) Text("⚠ out of date: the flowsheet changed since these were calculated", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.valueColors.underSpecifiedText)
        val curves = listOf(DistillationCurveKinds.TBP, DistillationCurveKinds.D86, DistillationCurveKinds.D2887, DistillationCurveKinds.D1160, DistillationCurveKinds.EFV)
            .mapNotNull { k -> r.curves.firstOrNull { it.kind == k } }
        val percents = curves.flatMap { c -> c.points.map { it.percent } }.distinct().sorted()
        // One table: percent distilled down, one column per curve, in the user's temperature unit.
        Row(Modifier.horizontalScroll(rememberScrollState()).testTag("petroleum_curves")) {
            Column {
                Text("%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(44.dp))
                percents.forEach { p -> Text(ValueFormat.number(p, 0), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(44.dp).heightIn(min = 22.dp)) }
            }
            curves.forEach { c ->
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        DistillationCurveKinds.label(c.kind).removeSuffix(" (simulated)"),
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(84.dp),
                    )
                    percents.forEach { p ->
                        val v = c.points.firstOrNull { it.percent == p }?.value
                        Text(v?.let { ValueFormat.number(t.fromSi(it, ctx), t.decimals) } ?: "—", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(84.dp).heightIn(min = 22.dp))
                    }
                }
            }
        }
        Text(
            "Temperatures in ${t.symbol}. TBP on liquid volume; D86 and D1160 liquid volume, D1160 at ${curves.firstOrNull { it.kind == DistillationCurveKinds.D1160 }?.let { ValueFormat.number(it.pressure / 133.322, 0) } ?: "10"} mmHg; " +
                "D2887 weight %; EFV at 1 atm. Conversions by the API Technical Data Book (3A1.1, 3A2.1, 3A3.1).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val pressure = absolute(unitSet.unitFor(Quantity.Pressure))
        PetroleumKeys.ROWS.forEach { (key, label, kind) ->
            val v = r.properties[key] ?: return@forEach
            val (unit, text) = when (kind) {
                PetroleumKeys.Kind.Temperature -> t to t.fromSi(v, ctx)
                PetroleumKeys.Kind.Pressure -> pressure to pressure.fromSi(v, ctx)
                PetroleumKeys.Kind.Density -> unitSet.unitFor(Quantity.Density).let { it to it.fromSi(v, ctx) }
                PetroleumKeys.Kind.KinematicViscosity -> unitSet.unitFor(Quantity.KinematicViscosity).let { it to it.fromSi(v, ctx) }
                PetroleumKeys.Kind.Length -> Units.mm to Units.mm.fromSi(v, ctx)
                PetroleumKeys.Kind.Number -> null to v
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(ValueFormat.number(text, unit?.decimals ?: decimals(key)) + (unit?.let { " ${it.symbol}" } ?: ""), style = MaterialTheme.typography.bodyMedium)
            }
        }
        r.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.valueColors.underSpecifiedText) }
        Text(
            "Product properties are API correlations of the curves, SG and Watson K (pour point 2B8.1, flash point 2B7.1, cetane index 2B13.1); " +
                "they are estimates, best for petroleum fractions characterised from an assay.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    state?.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.valueColors.error) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { actions.petroleumProperties(obj.id) }, enabled = state?.loading != true, modifier = Modifier.heightIn(min = 48.dp).testTag("petroleum_run")) {
            Text(if (r == null) "Calculate curves" else "Calculate again")
        }
        if (state?.loading == true) CircularProgressIndicator(Modifier.size(24.dp))
    }
}

private fun decimals(key: String) = when (key) {
    PetroleumKeys.SPECIFIC_GRAVITY, PetroleumKeys.REFRACTIVE -> 4
    PetroleumKeys.API, PetroleumKeys.CETANE, PetroleumKeys.CETANE_D4737, PetroleumKeys.MOLECULAR_WEIGHT -> 1
    else -> 2
}

/** Vapour pressures are absolute: barg/psig/kPag users see the matching absolute unit. */
private fun absolute(unit: EngUnit): EngUnit = when (unit) {
    Units.barg -> Units.bara
    Units.psig -> Units.psia
    Units.kPag -> Units.kPa
    else -> unit
}
