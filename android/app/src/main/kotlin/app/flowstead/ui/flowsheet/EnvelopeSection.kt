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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import app.flowstead.core.api.PhaseEnvelope
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import app.flowstead.core.api.HydrateInhibitors
import app.flowstead.core.format.ValueFormat
import app.flowstead.core.model.DisplayValue
import app.flowstead.core.model.Plots
import app.flowstead.core.model.ResultView
import app.flowstead.core.model.SimObject
import app.flowstead.core.units.Quantity
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.UnitSet
import app.flowstead.ui.components.PlotView
import app.flowstead.ui.theme.valueColors

/**
 * The phase envelope the engine traced for one stream (D50). [stale]: the flowsheet changed since, so the stream's
 * composition may have too.
 */
data class EnvelopeUi(
    val streamId: String,
    val loading: Boolean = false,
    val envelope: PhaseEnvelope? = null,
    val error: String? = null,
    val stale: Boolean = false,
)

@Composable
internal fun EnvelopeSection(obj: SimObject, results: ResultView, state: EnvelopeUi?, unitSet: UnitSet, ctx: UnitContext, actions: EditorActions) {
    SectionLabel("Phase envelope")
    // The inhibitor of the last trace, for the dose sentence (methanol when none was chosen).
    var lastInhibitor by rememberSaveable { mutableStateOf<String?>(null) }
    val env = state?.envelope
    if (env != null) {
        // The stream's own state, from the same solve, when it is current.
        fun si(key: String) = (results.value(obj.id, key) as? DisplayValue.Calculated)?.takeIf { !it.stale }?.si
        val plot = Plots.envelope(env, si("temperature"), si("pressure"))
        if (plot == null) Text("DWSIM traced no bubble or dew curve for this composition.", style = MaterialTheme.typography.bodySmall)
        else PlotView(plot, unitSet, ctx, stale = state?.stale == true)
        val t = unitSet.unitFor(Quantity.Temperature)
        val p = unitSet.unitFor(Quantity.Pressure)
        @Composable
        fun row(label: String, tv: Double?, pv: Double?) {
            if (tv == null || pv == null) return
            "$label: ${ValueFormat.number(p.fromSi(pv, ctx), p.decimals)} ${p.symbol} at ${ValueFormat.number(t.fromSi(tv, ctx), t.decimals)} ${t.symbol}"
                .let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            row("Cricondenbar", env.cricondenbarTemperature, env.cricondenbarPressure)
            row("Cricondentherm", env.cricondenthermTemperature, env.cricondenthermPressure)
            row("Critical point", env.criticalTemperature, env.criticalPressure)
            env.hydrateTemperatureAtPressure?.let { th ->
                Text(
                    "Hydrates (${env.hydrateStructureAtPressure ?: "?"}) form below ${ValueFormat.number(t.fromSi(th, ctx), t.decimals)} ${t.symbol} at this stream's pressure",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.valueColors.underSpecifiedText,
                    modifier = Modifier.testTag("hydrate_temperature"),
                )
            }
            env.hydrateDepression?.let { d ->
                Text(
                    "With the inhibitor in the water, hydrates form ${ValueFormat.number(d, 1)} K lower" +
                        (env.inhibitedHydrateTemperatureAtPressure?.let { ti -> ", below ${ValueFormat.number(t.fromSi(ti, ctx), t.decimals)} ${t.symbol} at this stream's pressure" } ?: "") + " (dashed curve).",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            env.requiredInhibitorFraction?.let { w ->
                val what = HydrateInhibitors.label(lastInhibitor ?: HydrateInhibitors.METHANOL)
                Text(
                    if (w <= 0) "This stream is warmer than its hydrate temperature: no inhibitor needed."
                    else "To stay out of hydrates at this stream's temperature, its free water needs ${ValueFormat.number(w * 100, 1)} wt % $what.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("inhibitor_needed"),
                )
            }
        }
        env.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.valueColors.underSpecifiedText) }
    } else {
        Calculated("Bubble and dew curves of this stream's composition, from the engine.")
    }
    state?.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.valueColors.error) }
    // What to draw besides the envelope, as HYSYS's envelope utility: quality lines and the hydrate curve.
    var hydrates by rememberSaveable { mutableStateOf(true) }
    var inhibitor by rememberSaveable { mutableStateOf<String?>(null) }
    var inhibitorPercent by rememberSaveable { mutableStateOf("") }
    var qualities by remember { mutableStateOf(listOf(0.5)) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(selected = hydrates, onClick = { hydrates = !hydrates }, label = { Text("Hydrate curve") }, modifier = Modifier.testTag("envelope_hydrates"))
        for (q in listOf(0.1, 0.25, 0.5, 0.75, 0.9)) {
            FilterChip(
                selected = q in qualities,
                onClick = { qualities = if (q in qualities) qualities - q else (qualities + q).sorted() },
                label = { Text("VF ${ValueFormat.number(q, 2).trimEnd('0').trimEnd('.')}") },
            )
        }
    }
    if (hydrates) {
        // Thermodynamic inhibitor in the free water (methanol: Nielsen–Bucklin; glycols: Hammerschmidt).
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = inhibitor == null, onClick = { inhibitor = null }, label = { Text("No inhibitor") })
            for (id in HydrateInhibitors.ALL) {
                FilterChip(selected = inhibitor == id, onClick = { inhibitor = id }, label = { Text(HydrateInhibitors.label(id)) }, modifier = Modifier.testTag("inhibitor_$id"))
            }
        }
        if (inhibitor != null) {
            OutlinedTextField(
                value = inhibitorPercent,
                onValueChange = { inhibitorPercent = it },
                label = { Text("${HydrateInhibitors.label(inhibitor!!)} in the free water, wt %") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = inhibitorPercent.isNotBlank() && inhibitorPercent.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it < 100 } == null,
                modifier = Modifier.fillMaxWidth().testTag("inhibitor_percent"),
            )
        }
    }
    val fraction = inhibitorPercent.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it < 100 }?.div(100) ?: 0.0
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = { lastInhibitor = inhibitor; actions.phaseEnvelope(obj.id, qualities, hydrates, inhibitor, if (inhibitor == null) 0.0 else fraction) },
            enabled = state?.loading != true,
            modifier = Modifier.heightIn(min = 48.dp).testTag("phase_envelope"),
        ) { Text(if (env == null) "Trace phase envelope" else "Trace again") }
        if (state?.loading == true) {
            Spacer(Modifier.width(12.dp))
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Tracing…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
        }
    }
    SectionLabel("PVT laboratory")
    Calculated("Saturation pressure, constant composition expansion, constant volume depletion, differential liberation and separator tests on this stream's fluid.")
    OutlinedButton(onClick = { actions.openPvt(obj.id) }, modifier = Modifier.heightIn(min = 48.dp).testTag("open_pvt")) { Text("PVT analysis") }
}
