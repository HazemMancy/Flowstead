/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.flowsheet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.flowstead.core.api.Phases
import app.flowstead.core.format.ValueFormat
import app.flowstead.core.model.ResultKeys
import app.flowstead.core.model.ResultView
import app.flowstead.ui.components.ValueText
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.SimObject
import app.flowstead.core.model.Spec
import app.flowstead.core.model.SpecKey
import app.flowstead.core.model.SpecStatus
import app.flowstead.core.model.Specification
import app.flowstead.core.model.displayName
import app.flowstead.core.model.isFeed
import app.flowstead.core.units.EngUnit
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.UnitSet
import app.flowstead.core.units.Units
import app.flowstead.ui.theme.NumberStyles
import app.flowstead.ui.theme.valueColors

/** Edits an object; every change goes straight to the ViewModel (and is undoable). */
interface EditorActions {
    fun rename(id: String, tag: String)
    fun setSpec(id: String, key: String, spec: Spec?)
    fun setExclusiveSpec(id: String, key: String, spec: Spec, group: Collection<String>)
    fun setComposition(id: String, fractions: Map<String, Double>)
    fun normalise(id: String, fractions: Map<String, Double>)
    fun delete(id: String)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObjectEditorSheet(
    fs: Flowsheet,
    obj: SimObject,
    status: SpecStatus?,
    results: ResultView,
    unitSet: UnitSet,
    ctx: UnitContext,
    actions: EditorActions,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
                .testTag("object_editor"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(obj, actions)
            StatusChip(status)
            HorizontalDivider()
            when (obj.type) {
                ObjectType.MaterialStream ->
                    if (fs.isFeed(obj)) FeedStreamEditor(fs, obj, unitSet, ctx, actions) else Calculated("This stream is calculated from the unit it comes from.")
                ObjectType.EnergyStream -> Calculated("Energy streams carry the duty or power of the unit they connect to.")
                ObjectType.Valve -> ExclusiveSpecs(
                    obj, unitSet, ctx, actions,
                    listOf(SpecKey.OUTLET_PRESSURE to "Outlet pressure", SpecKey.PRESSURE_DROP to "Pressure drop"),
                )
                ObjectType.Heater, ObjectType.Cooler -> {
                    ExclusiveSpecs(
                        obj, unitSet, ctx, actions,
                        listOf(SpecKey.OUTLET_TEMPERATURE to "Outlet temperature", SpecKey.DUTY to "Duty"),
                    )
                    SpecField(obj, SpecKey.PRESSURE_DROP, unitSet, ctx, actions, optionalHint = "0 if left empty")
                }
                ObjectType.Separator, ObjectType.ThreePhaseSeparator ->
                    Calculated("Adiabatic flash at the lowest feed pressure. Nothing to specify: connect the feed and the product streams.")
                else -> Calculated("${obj.type.displayName()} editing arrives with the remaining unit operations.")
            }
            ResultsSection(obj, results, unitSet, ctx)
        }
    }
}

/** Calculated values for this object: only what the engine returned, "—" with the reason otherwise. */
@Composable
private fun ResultsSection(obj: SimObject, results: ResultView, unitSet: UnitSet, ctx: UnitContext) {
    val keys = when (obj.type) {
        ObjectType.MaterialStream -> listOf("temperature", "pressure", "vapourFraction", "molarFlow", "massFlow", "massEnthalpy", "density", "molecularWeight")
        ObjectType.EnergyStream -> listOf("energyFlow")
        ObjectType.Valve -> listOf("pressureDrop", "outletPressure", "temperatureChange")
        ObjectType.Heater, ObjectType.Cooler -> listOf("duty", "outletTemperature", "pressureDrop")
        else -> emptyList()
    }
    if (keys.isEmpty()) return
    HorizontalDivider()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Results", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (results.stale) Text("⚠ out of date", style = NumberStyles.tag, color = MaterialTheme.valueColors.underSpecifiedText)
    }
    for (key in keys) {
        val unit = unitSet.unitFor(ResultKeys.quantity(key))
        // Overall density/MW live on the overall phase.
        val value = if (key == "density" || key == "molecularWeight") results.phaseValue(obj.id, Phases.OVERALL, key)
        else results.value(obj.id, key)
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(ResultKeys.label(key), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            ValueText(value, unit, ctx = ctx)
        }
        (value as? app.flowstead.core.model.DisplayValue.Missing)?.takeIf { key == keys.first() }?.let {
            Text(it.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        (value as? app.flowstead.core.model.DisplayValue.Error)?.takeIf { key == keys.first() }?.let {
            Text(it.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.valueColors.error)
        }
    }
}

@Composable
private fun Header(obj: SimObject, actions: EditorActions) {
    var tag by remember(obj.id, obj.tag) { mutableStateOf(obj.tag) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(obj.type.displayName(), style = NumberStyles.tag, color = MaterialTheme.colorScheme.secondary)
            OutlinedTextField(
                value = tag,
                onValueChange = { tag = it.take(24) },
                label = { Text("Tag") },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { actions.rename(obj.id, tag) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (!it.isFocused && tag != obj.tag) actions.rename(obj.id, tag) }
                    .testTag("tag_field"),
            )
        }
        IconButton(onClick = { actions.delete(obj.id) }) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete ${obj.tag}", tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun StatusChip(status: SpecStatus?) {
    val v = MaterialTheme.valueColors
    val (text, color) = when (status) {
        null, SpecStatus.Complete -> "Fully specified" to v.convergedText
        is SpecStatus.UnderSpecified -> "Needs ${status.needs.joinToString("; ")}" to v.underSpecifiedText
        is SpecStatus.OverSpecified -> status.problem to v.error
        is SpecStatus.Invalid -> status.problem to v.error
    }
    val swatch = when (status) {
        null, SpecStatus.Complete -> v.converged
        is SpecStatus.UnderSpecified -> v.underSpecified
        else -> v.error
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("dof_status"),
    ) {
        Box(Modifier.padding(end = 8.dp).width(10.dp).heightIn(min = 10.dp).background(swatch, RoundedCornerShape(5.dp)))
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Calculated(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun FeedStreamEditor(fs: Flowsheet, obj: SimObject, unitSet: UnitSet, ctx: UnitContext, actions: EditorActions) {
    SectionLabel("Conditions — give two")
    for (key in SpecKey.STREAM_STATE) SpecField(obj, key, unitSet, ctx, actions)
    SectionLabel("Flow — give one")
    for (key in SpecKey.STREAM_FLOW) SpecField(obj, key, unitSet, ctx, actions)
    SectionLabel("Composition (mole fractions)")
    CompositionEditor(fs, obj, actions)
}

/** One of several alternative specs (e.g. outlet pressure OR pressure drop). */
@Composable
private fun ExclusiveSpecs(
    obj: SimObject,
    unitSet: UnitSet,
    ctx: UnitContext,
    actions: EditorActions,
    choices: List<Pair<String, String>>,
) {
    val keys = choices.map { it.first }
    var chosen by remember(obj.id) { mutableStateOf(keys.firstOrNull { it in obj.specs } ?: keys.first()) }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        choices.forEachIndexed { i, (key, label) ->
            SegmentedButton(
                selected = chosen == key,
                onClick = { chosen = key },
                shape = SegmentedButtonDefaults.itemShape(i, choices.size),
            ) { Text(label) }
        }
    }
    SpecField(obj, chosen, unitSet, ctx, actions, exclusiveGroup = keys)
}

/**
 * Number entry for one spec, with its own unit picker. Blue when the user specified it; empty
 * fields show "—" and are never filled with a guessed value.
 */
@Composable
fun SpecField(
    obj: SimObject,
    key: String,
    unitSet: UnitSet,
    ctx: UnitContext,
    actions: EditorActions,
    exclusiveGroup: Collection<String>? = null,
    optionalHint: String? = null,
) {
    val quantity = SpecKey.quantity(key)
    val spec = obj.specs[key]
    var unit by remember(obj.id, key, spec?.unit, unitSet) {
        mutableStateOf(spec?.unit?.let { Units.byIdOrNull(it) }?.takeIf { it.quantity == quantity } ?: unitSet.unitFor(quantity))
    }
    val shown = spec?.let { ValueFormat.number(unit.fromSi(it.si, ctx), unit.decimals).replace(",", "") } ?: ""
    var text by remember(obj.id, key, spec, unit) { mutableStateOf(shown) }
    var error by remember(obj.id, key) { mutableStateOf<String?>(null) }
    val colors = MaterialTheme.valueColors

    fun commit() {
        val trimmed = text.trim().replace(',', '.')
        if (trimmed.isEmpty()) {
            error = null
            if (spec != null) actions.setSpec(obj.id, key, null)
            return
        }
        val v = trimmed.toDoubleOrNull()
        if (v == null || !v.isFinite()) {
            error = "Not a number"
            return
        }
        error = null
        val newSpec = Spec(unit.toSi(v, ctx), unit.id)
        if (newSpec == spec) return
        if (exclusiveGroup != null) actions.setExclusiveSpec(obj.id, key, newSpec, exclusiveGroup)
        else actions.setSpec(obj.id, key, newSpec)
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(SpecKey.label(key)) },
            placeholder = { Text(optionalHint ?: "—") },
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            singleLine = true,
            textStyle = NumberStyles.valueSpecified.copy(color = if (spec != null) colors.specified else MaterialTheme.colorScheme.onSurface),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { if (!it.isFocused) commit() }
                .testTag("spec_$key"),
        )
        Spacer(Modifier.width(8.dp))
        UnitPicker(unit, Units.forQuantity(quantity)) { picked ->
            // Keep the physical value: re-express the stored spec in the new unit.
            unit = picked
            spec?.let { actions.setSpec(obj.id, key, it.copy(unit = picked.id)) }
        }
    }
}

@Composable
private fun UnitPicker(selected: EngUnit, options: List<EngUnit>, onPick: (EngUnit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { open = true },
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Unit ${selected.symbol.ifEmpty { "fraction" }}, change" },
        ) { Text(selected.symbol.ifEmpty { "frac" }, style = NumberStyles.value) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { u ->
                DropdownMenuItem(text = { Text(u.symbol.ifEmpty { "fraction" }, style = NumberStyles.value) }, onClick = { open = false; onPick(u) })
            }
        }
    }
}

@Composable
private fun CompositionEditor(fs: Flowsheet, obj: SimObject, actions: EditorActions) {
    if (fs.compounds.isEmpty()) {
        Text(
            "Add components in Fluid package first.",
            color = MaterialTheme.valueColors.underSpecifiedText,
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    val texts = remember(obj.id, obj.composition, fs.compounds) {
        mutableStateMapOf<String, String>().apply {
            fs.compounds.forEach { c -> put(c, obj.composition[c]?.let { ValueFormat.number(it, 6).replace(",", "") } ?: "") }
        }
    }

    fun parsed(): Map<String, Double>? {
        val out = mutableMapOf<String, Double>()
        for ((c, t) in texts) {
            val s = t.trim().replace(',', '.')
            if (s.isEmpty()) continue
            out[c] = s.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return null
        }
        return out
    }

    fun commit() {
        parsed()?.let { if (it != obj.composition.filterValues { x -> x > 0 }) actions.setComposition(obj.id, it) }
    }

    for (c in fs.compounds) {
        OutlinedTextField(
            value = texts[c].orEmpty(),
            onValueChange = { texts[c] = it },
            label = { Text(c) },
            singleLine = true,
            placeholder = { Text("0") },
            textStyle = NumberStyles.valueSpecified.copy(color = MaterialTheme.valueColors.specified),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) commit() }.testTag("x_$c"),
        )
    }
    val values = parsed()
    val sum = values?.values?.sum()
    val v = MaterialTheme.valueColors
    val sumOk = sum != null && kotlin.math.abs(sum - 1.0) <= Specification.COMPOSITION_TOLERANCE
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            when {
                values == null -> "Check the fractions: numbers ≥ 0 only"
                else -> "Sum = ${ValueFormat.number(sum ?: 0.0, 6)}"
            },
            style = NumberStyles.value,
            color = if (sumOk) v.convergedText else v.underSpecifiedText,
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
        )
        TextButton(
            onClick = { values?.let { actions.normalise(obj.id, it) } },
            enabled = values != null && (sum ?: 0.0) > 0 && !sumOk,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text("Normalise") }
    }
}

/** Shown when a long-press opens the quick actions for an object. */
@Composable
fun ObjectQuickActions(obj: SimObject, onEdit: () -> Unit, onDisconnect: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${obj.type.displayName()} ${obj.tag}") },
        text = {
            Column {
                listOf(
                    "Edit" to onEdit,
                    "Disconnect all" to onDisconnect,
                    "Delete" to onDelete,
                ).forEach { (label, action) ->
                    TextButton(onClick = { action(); onDismiss() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(label, color = if (label == "Delete") MaterialTheme.colorScheme.error else Color.Unspecified, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
