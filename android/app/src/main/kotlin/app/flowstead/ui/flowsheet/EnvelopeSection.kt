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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.flowstead.core.api.PhaseEnvelope
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
    val env = state?.envelope
    if (env != null) {
        // The stream's own state, from the same solve, when it is current.
        fun si(key: String) = (results.value(obj.id, key) as? DisplayValue.Calculated)?.takeIf { !it.stale }?.si
        val plot = Plots.envelope(env, si("temperature"), si("pressure"))
        if (plot == null) Text("DWSIM traced no bubble or dew curve for this composition.", style = MaterialTheme.typography.bodySmall)
        else PlotView(plot, unitSet, ctx, stale = state?.stale == true)
        val t = unitSet.unitFor(Quantity.Temperature)
        val p = unitSet.unitFor(Quantity.Pressure)
        fun row(label: String, tv: Double?, pv: Double?) {
            if (tv == null || pv == null) return
            "$label: ${ValueFormat.number(p.fromSi(pv, ctx), p.decimals)} ${p.symbol} at ${ValueFormat.number(t.fromSi(tv, ctx), t.decimals)} ${t.symbol}"
                .let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            row("Cricondenbar", env.cricondenbarTemperature, env.cricondenbarPressure)
            row("Cricondentherm", env.cricondenthermTemperature, env.cricondenthermPressure)
            row("Critical point", env.criticalTemperature, env.criticalPressure)
        }
        env.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.valueColors.underSpecifiedText) }
    } else {
        Calculated("Bubble and dew curves of this stream's composition, from the engine.")
    }
    state?.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.valueColors.error) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = { actions.phaseEnvelope(obj.id) },
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
}
