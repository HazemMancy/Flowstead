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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.flowstead.core.api.MessageLevel
import app.flowstead.core.model.AdjustKeys
import app.flowstead.core.model.DynamicEvent
import app.flowstead.core.model.DynamicKey
import app.flowstead.core.model.Dynamics
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.ResultKeys
import app.flowstead.core.model.Spec
import app.flowstead.core.model.displayName
import app.flowstead.core.model.obj
import app.flowstead.core.units.Quantity
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.UnitSet
import app.flowstead.ui.components.PlotsSection
import app.flowstead.ui.theme.valueColors

interface DynamicsActions {
    fun setTiming(duration: Double?, step: Double?)
    fun setParameter(id: String, key: String, spec: Spec?)
    fun addEvent(e: DynamicEvent)
    fun removeEvent(index: Int)
    fun run()
}

/**
 * Dynamic simulation (D86), as HYSYS's dynamics: the flowsheet runs from its steady state through time, vessels holding
 * inventory, valves passing what the pressures drive, controllers acting, events changing things at set times.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DynamicsSheet(
    fs: Flowsheet,
    ui: DynamicsUi?,
    currentKey: String,
    unitSet: UnitSet,
    ctx: UnitContext,
    actions: DynamicsActions,
    onDismiss: () -> Unit,
) {
    val setup = Dynamics.setup(fs)
    fun p(id: String, key: String) = Dynamics.parameter(fs, id, key)
    @Composable
    fun Param(id: String, key: String, hint: String? = null, quantity: Quantity = DynamicKey.quantity(key), label: String = DynamicKey.label(key)) =
        QuantityField(label, p(id, key), quantity, defaultUnit(key, quantity, unitSet), ctx, "dyn_${id}_$key", hint, testTag = "dyn_${id}_$key") {
            actions.setParameter(id, key, it)
        }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
                .testTag("dynamics"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Dynamics", style = MaterialTheme.typography.titleLarge)
            Calculated(
                "Runs the flowsheet through time from its steady state: separators hold liquid and gas, valves pass what " +
                    "the pressures drive through them, controllers act as PIDs, and events change things at set times.",
            )
            val problem = Dynamics.problem(fs)
            problem?.let { Text(it, color = MaterialTheme.valueColors.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("dyn_problem")) }

            SectionLabel("Run")
            QuantityField("Duration", Spec(setup.duration, "s"), Quantity.Time, unitSet.unitFor(Quantity.Time), ctx, "dyn_duration", testTag = "dyn_duration") {
                it?.let { s -> actions.setTiming(s.si, null) }
            }
            QuantityField("Step", Spec(setup.step, "s"), Quantity.Time, unitSet.unitFor(Quantity.Time), ctx, "dyn_step",
                hint = "smaller steps are taken where a vessel changes fast", testTag = "dyn_step") {
                it?.let { s -> actions.setTiming(null, s.si) }
            }

            val vessels = fs.objects.filter { it.type == ObjectType.Separator }
            if (vessels.isNotEmpty()) {
                HorizontalDivider(); SectionLabel("Vessels")
                for (v in vessels) {
                    Text("${v.tag} · ${v.type.displayName()}", style = MaterialTheme.typography.titleSmall)
                    Param(v.id, DynamicKey.VOLUME)
                    Param(v.id, DynamicKey.HEIGHT, hint = "three diameters if empty")
                    Param(v.id, DynamicKey.INITIAL_LEVEL, hint = "50 % if empty")
                }
            }

            val valves = fs.objects.filter { it.type == ObjectType.Valve }
            if (valves.isNotEmpty()) {
                HorizontalDivider(); SectionLabel("Valves")
                for (v in valves) {
                    Text(v.tag, style = MaterialTheme.typography.titleSmall)
                    Param(v.id, DynamicKey.RATED_CV, hint = "sized to pass the steady flow if empty")
                    Param(v.id, DynamicKey.OPENING, hint = "50 %, or what passes the steady flow with a rated Cv")
                }
            }

            val controllers = fs.objects.filter { it.type == ObjectType.Controller }
            if (controllers.isNotEmpty()) {
                HorizontalDivider(); SectionLabel("Controllers (PID)")
                for (c in controllers) {
                    val pv = c.refs[AdjustKeys.CONTROLLED_PROPERTY]
                    val measured = c.refs[AdjustKeys.CONTROLLED_OBJECT]?.let { fs.obj(it)?.tag }
                    val moved = c.refs[AdjustKeys.MANIPULATED_OBJECT]?.let { fs.obj(it)?.tag }
                    Text("${c.tag} · ${measured ?: "?"} ${pv?.let { ResultKeys.label(it).lowercase() } ?: ""} → ${moved ?: "?"}", style = MaterialTheme.typography.titleSmall)
                    Param(c.id, DynamicKey.GAIN, hint = "%/% of span; level 2, pressure 2, flow 0.5, temperature 1 if empty")
                    Param(c.id, DynamicKey.INTEGRAL_TIME, hint = "level 300 s, pressure 30 s, flow 5 s, temperature 120 s if empty")
                    Param(c.id, DynamicKey.DERIVATIVE_TIME, hint = "none if empty")
                    pv?.let { key ->
                        val q = ResultKeys.quantity(key)
                        Param(c.id, DynamicKey.PV_MINIMUM, quantity = q, hint = "the span: twice the setpoint, the vessel's height for level, 100 K for temperature")
                        Param(c.id, DynamicKey.PV_MAXIMUM, quantity = q)
                    }
                    val action = p(c.id, DynamicKey.ACTION)?.si
                    Choice(
                        "Action",
                        when { action == null -> "From the process"; action > 0 -> "Direct (output rises with the measurement)"; else -> "Reverse (output falls as the measurement rises)" },
                        listOf("auto" to "From the process", "direct" to "Direct (output rises with the measurement)", "reverse" to "Reverse (output falls as the measurement rises)"),
                    ) { pick ->
                        actions.setParameter(c.id, DynamicKey.ACTION, when (pick) { "direct" -> Spec(1.0, "ratio"); "reverse" -> Spec(-1.0, "ratio"); else -> null })
                    }
                }
            }

            HorizontalDivider(); SectionLabel("Events")
            setup.events.forEachIndexed { i, e ->
                val o = fs.obj(e.objectId)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "At ${ctx.let { _ -> app.flowstead.core.format.ValueFormat.number(e.time, 0) }} s · ${o?.tag ?: "?"} ${Dynamics.eventLabel(e.key).lowercase()} → ${eventValue(fs, e, unitSet, ctx)}",
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    )
                    IconButton(onClick = { actions.removeEvent(i) }) { Icon(Icons.Filled.Delete, contentDescription = "Remove event") }
                }
            }
            AddEvent(fs, unitSet, ctx, actions)

            HorizontalDivider()
            Button(onClick = actions::run, enabled = problem == null && ui?.running != true, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("dyn_run")) {
                Text(if (ui?.running == true) "Running…" else "Run")
            }
            if (ui?.running == true) LinearProgressIndicator(Modifier.fillMaxWidth())
            ui?.error?.let { Text(it, color = MaterialTheme.valueColors.error, style = MaterialTheme.typography.bodyMedium) }
            ui?.result?.let { r ->
                val stale = ui.key != currentKey
                if (stale) Text("⚠ Out of date: the flowsheet or the setup changed after this run. Run again.", color = MaterialTheme.valueColors.underSpecifiedText, style = MaterialTheme.typography.bodyMedium)
                Text("${r.times.lastOrNull()?.let { "%.0f".format(java.util.Locale.ROOT, it) } ?: 0} s simulated in ${"%.1f".format(java.util.Locale.ROOT, r.elapsedMs / 1000.0)} s",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                r.messages.filter { it.level != MessageLevel.Info }.forEach {
                    Text(it.text, style = MaterialTheme.typography.bodySmall,
                        color = if (it.level == MessageLevel.Error) MaterialTheme.valueColors.error else MaterialTheme.valueColors.underSpecifiedText)
                }
                PlotsSection(Dynamics.plots(fs, r), unitSet, ctx, stale, "dynamics_plots")
            }
        }
    }
}

private fun eventValue(fs: Flowsheet, e: DynamicEvent, unitSet: UnitSet, ctx: UnitContext): String {
    val o = fs.obj(e.objectId) ?: return e.value.toString()
    val q = Dynamics.eventKeys(fs, o).firstOrNull { it.first == e.key }?.second ?: return e.value.toString()
    if (e.key == DynamicKey.MODE) return if (e.value >= 0.5) "automatic" else "manual"
    val u = unitSet.unitFor(q)
    return app.flowstead.core.format.ValueFormat.number(u.fromSi(e.value, ctx), u.decimals) + " " + u.symbol
}

@Composable
private fun AddEvent(fs: Flowsheet, unitSet: UnitSet, ctx: UnitContext, actions: DynamicsActions) {
    var time by rememberSaveable { mutableStateOf<Double?>(null) }
    var objectId by rememberSaveable { mutableStateOf<String?>(null) }
    var key by rememberSaveable { mutableStateOf<String?>(null) }
    var value by rememberSaveable { mutableStateOf<Double?>(null) }
    val candidates = fs.objects.filter { Dynamics.eventKeys(fs, it).isNotEmpty() }
    val o = objectId?.let { fs.obj(it) }
    val keys = o?.let { Dynamics.eventKeys(fs, it) }.orEmpty()
    QuantityField("At time", time?.let { Spec(it, "s") }, Quantity.Time, unitSet.unitFor(Quantity.Time), ctx, "dyn_event_time", testTag = "dyn_event_time") { time = it?.si }
    Choice("Object", o?.tag, candidates.map { it.id to "${it.tag} · ${it.type.displayName()}" }, testTag = "dyn_event_object") { objectId = it; key = null; value = null }
    if (o != null) Choice("Change", key?.let { Dynamics.eventLabel(it) }, keys.map { it.first to Dynamics.eventLabel(it.first) }, testTag = "dyn_event_key") { key = it; value = null }
    val q = keys.firstOrNull { it.first == key }?.second
    if (key != null && q != null) {
        QuantityField("To", value?.let { Spec(it, "SI") }, q, defaultUnit(key!!, q, unitSet), ctx, "dyn_event_value_$key", testTag = "dyn_event_value") { value = it?.si }
    }
    TextButton(
        onClick = {
            actions.addEvent(DynamicEvent(time!!, objectId!!, key!!, value!!))
            time = null; value = null
        },
        enabled = time != null && objectId != null && key != null && value != null,
        modifier = Modifier.heightIn(min = 48.dp).testTag("dyn_event_add"),
    ) { Text("Add event") }
}
