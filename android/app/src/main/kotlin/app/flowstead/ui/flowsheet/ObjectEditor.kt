/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.flowsheet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import app.flowstead.core.model.AdjustKeys
import app.flowstead.core.model.ChoiceKey
import app.flowstead.core.model.Columns
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.FlowsheetEditing
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.Port
import app.flowstead.core.model.PortKind
import app.flowstead.core.model.PortSide
import app.flowstead.core.model.Ports
import app.flowstead.core.model.SetKeys
import app.flowstead.core.model.SimObject
import app.flowstead.core.model.Spec
import app.flowstead.core.model.SpecKey
import app.flowstead.core.model.SpecStatus
import app.flowstead.core.model.Specification
import app.flowstead.core.model.displayName
import app.flowstead.core.model.isFeed
import app.flowstead.core.model.streamAt
import app.flowstead.core.units.EngUnit
import app.flowstead.core.units.Quantity
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
    fun setRef(id: String, key: String, value: String?)
    /** Attach [streamId] at [port] of [unitId]; null makes a new stream there. */
    fun connectPort(unitId: String, port: Port, streamId: String?)
    fun disconnectPort(unitId: String, port: Port)
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
                ObjectType.AirCooler -> {
                    Calculated("Process side only: the engine finds the duty to air; fan power is not modelled.")
                    ExclusiveSpecs(
                        obj, unitSet, ctx, actions,
                        listOf(SpecKey.OUTLET_TEMPERATURE to "Outlet temperature", SpecKey.DUTY to "Duty"),
                    )
                    SpecField(obj, SpecKey.PRESSURE_DROP, unitSet, ctx, actions, optionalHint = "0 if left empty")
                }
                ObjectType.Heater, ObjectType.Cooler -> {
                    ExclusiveSpecs(
                        obj, unitSet, ctx, actions,
                        listOf(SpecKey.OUTLET_TEMPERATURE to "Outlet temperature", SpecKey.DUTY to "Duty"),
                    )
                    SpecField(obj, SpecKey.PRESSURE_DROP, unitSet, ctx, actions, optionalHint = "0 if left empty")
                }
                ObjectType.Separator, ObjectType.ThreePhaseSeparator ->
                    Calculated("Adiabatic flash at the lowest feed pressure. Nothing to specify: connect the feed and the product streams.")
                ObjectType.Pump -> {
                    ExclusiveSpecs(obj, unitSet, ctx, actions, listOf(SpecKey.OUTLET_PRESSURE to "Outlet P", SpecKey.PRESSURE_RISE to "ΔP rise"))
                    SpecField(obj, SpecKey.EFFICIENCY, unitSet, ctx, actions, optionalHint = "75 % if empty")
                }
                ObjectType.Compressor -> {
                    ExclusiveSpecs(
                        obj, unitSet, ctx, actions,
                        listOf(SpecKey.OUTLET_PRESSURE to "Outlet P", SpecKey.PRESSURE_RISE to "ΔP", SpecKey.PRESSURE_RATIO to "Ratio"),
                    )
                    SpecField(obj, SpecKey.EFFICIENCY, unitSet, ctx, actions, optionalHint = "75 % adiabatic if empty")
                }
                ObjectType.Expander -> {
                    ExclusiveSpecs(
                        obj, unitSet, ctx, actions,
                        listOf(SpecKey.OUTLET_PRESSURE to "Outlet P", SpecKey.PRESSURE_DROP to "ΔP", SpecKey.PRESSURE_RATIO to "Ratio"),
                    )
                    SpecField(obj, SpecKey.EFFICIENCY, unitSet, ctx, actions, optionalHint = "75 % adiabatic if empty")
                }
                ObjectType.HeatExchanger -> {
                    Calculated("Counter-current. The engine finds the hot and cold sides from the inlet temperatures.")
                    ExclusiveSpecs(
                        obj, unitSet, ctx, actions,
                        listOf(SpecKey.DUTY to "Duty", SpecKey.HOT_OUTLET_TEMPERATURE to "Hot out T", SpecKey.COLD_OUTLET_TEMPERATURE to "Cold out T"),
                    )
                    SpecField(obj, SpecKey.HOT_PRESSURE_DROP, unitSet, ctx, actions, optionalHint = "0 if empty")
                    SpecField(obj, SpecKey.COLD_PRESSURE_DROP, unitSet, ctx, actions, optionalHint = "0 if empty")
                }
                ObjectType.Splitter -> {
                    SpecField(obj, SpecKey.SPLIT_1, unitSet, ctx, actions)
                    SpecField(obj, SpecKey.SPLIT_2, unitSet, ctx, actions, optionalHint = "the rest if empty")
                    Calculated("Outlet 3, when connected, takes whatever is left.")
                }
                ObjectType.Mixer -> Calculated("Adiabatic mixing; the outlet is at the lowest inlet pressure. Nothing to specify.")
                ObjectType.PipeSegment -> PipeEditor(obj, unitSet, ctx, actions)
                ObjectType.ComponentSplitter -> {
                    Calculated("Fraction of each component's feed sent to the overhead; the rest goes to the bottoms. Both leave at the feed temperature and pressure.")
                    if (fs.compounds.isEmpty()) Calculated("Add components in Fluid package first.")
                    for (c in fs.compounds) SpecField(obj, SpecKey.split(c), unitSet, ctx, actions, optionalHint = "0 (all to bottoms)")
                }
                ObjectType.Tank -> {
                    Calculated("Flash at the feed pressure, like a separator. Give the volume to see the liquid residence time.")
                    SpecField(obj, SpecKey.VOLUME, unitSet, ctx, actions, optionalHint = "optional")
                }
                ObjectType.Set -> SetEditor(fs, obj, unitSet, ctx, actions)
                ObjectType.DistillationColumn, ObjectType.Absorber, ObjectType.ReboiledAbsorber, ObjectType.RefluxedAbsorber ->
                    ColumnEditor(fs, obj, unitSet, ctx, actions)
                ObjectType.ShortcutColumn -> ShortcutColumnEditor(fs, obj, unitSet, ctx, actions)
                ObjectType.OrificePlate -> {
                    SpecField(obj, SpecKey.ORIFICE_DIAMETER, unitSet, ctx, actions)
                    SpecField(obj, SpecKey.PIPE_DIAMETER, unitSet, ctx, actions)
                    MethodChoice(obj, "Pressure taps", ChoiceKey.ORIFICE_TAPS, ChoiceKey.ORIFICE_TAP_OPTIONS, actions)
                    Calculated("ISO 5167 orifice meter. The outlet is at the permanent (recovered) pressure.")
                }
                ObjectType.Adjust -> AdjustEditor(fs, obj, unitSet, ctx, actions)
                ObjectType.Recycle -> Calculated(
                    "Tear stream: the engine iterates until the assumed and calculated streams agree (1e-6 kg/s, 0.001 K). " +
                        "Iterations are reported in the results.",
                )
                else -> Calculated("${obj.type.displayName()} editing arrives with the remaining unit operations.")
            }
            if (!obj.type.isStream && Ports.of(obj.type).isNotEmpty()) ConnectionsSection(fs, obj, actions)
            ResultsSection(obj, results, unitSet, ctx)
        }
    }
}

/**
 * Every port with what is attached, changeable from a list. The same wiring as dragging on the
 * canvas, reachable with TalkBack or a keyboard.
 */
@Composable
private fun ConnectionsSection(fs: Flowsheet, unit: SimObject, actions: EditorActions) {
    HorizontalDivider()
    SectionLabel("Connections")
    for (port in Ports.of(unit.type)) {
        val attached = fs.streamAt(unit.id, port)
        val options = buildList {
            add(NEW_STREAM to if (port.kind == PortKind.Energy) "New energy stream" else "New stream")
            FlowsheetEditing.attachableStreams(fs, unit.id, port).forEach { add(it.id to it.tag) }
            if (attached != null) add(DISCONNECT to "Disconnect ${attached.tag}")
        }
        val side = if (port.side == PortSide.Inlet) "in" else "out"
        Choice(
            label = "${port.label} ($side)" + if (port.required) "" else " · optional",
            selected = attached?.tag,
            options = options,
            placeholder = "Not connected",
            testTag = "port_${port.side.name.lowercase()}_${port.index}",
        ) { picked ->
            when (picked) {
                NEW_STREAM -> actions.connectPort(unit.id, port, null)
                DISCONNECT -> actions.disconnectPort(unit.id, port)
                else -> {
                    if (attached != null) actions.disconnectPort(unit.id, port)
                    actions.connectPort(unit.id, port, picked)
                }
            }
        }
    }
}

private const val NEW_STREAM = "__new"
private const val DISCONNECT = "__disconnect"

/** One pipe section: geometry, method and, optionally, heat exchange with the surroundings. */
@Composable
private fun PipeEditor(obj: SimObject, unitSet: UnitSet, ctx: UnitContext, actions: EditorActions) {
    SpecField(obj, SpecKey.LENGTH, unitSet, ctx, actions)
    SpecField(obj, SpecKey.INNER_DIAMETER, unitSet, ctx, actions)
    SpecField(obj, SpecKey.ELEVATION_CHANGE, unitSet, ctx, actions, optionalHint = "0 (horizontal) if empty")
    SpecField(obj, SpecKey.ROUGHNESS, unitSet, ctx, actions, optionalHint = "0.0457 mm (commercial steel) if empty")
    SpecField(obj, SpecKey.INCREMENTS, unitSet, ctx, actions, optionalHint = "10 if empty")
    MethodChoice(obj, "Flow correlation", ChoiceKey.PIPE_CORRELATION, ChoiceKey.PIPE_CORRELATIONS, actions)
    SectionLabel("Heat transfer")
    SpecField(obj, SpecKey.HEAT_TRANSFER_COEFFICIENT, unitSet, ctx, actions, optionalHint = "adiabatic if empty")
    SpecField(obj, SpecKey.AMBIENT_TEMPERATURE, unitSet, ctx, actions, optionalHint = "needed with U")
}

/** A method stored in the object's refs; the first option is the engine's default. */
@Composable
private fun MethodChoice(obj: SimObject, label: String, key: String, options: List<Pair<String, String>>, actions: EditorActions) {
    val current = obj.refs[key] ?: options.first().first
    Choice(label, options.firstOrNull { it.first == current }?.second, options, testTag = "choice_$key") { picked ->
        actions.setRef(obj.id, key, picked.takeIf { it != options.first().first })
    }
}

/** A rigorous column, HYSYS-style: trays, feed trays, pressures, then one condenser and one reboiler specification. */
@Composable
private fun ColumnEditor(fs: Flowsheet, obj: SimObject, unitSet: UnitSet, ctx: UnitContext, actions: EditorActions) {
    Calculated(
        when (obj.type) {
            ObjectType.Absorber -> "No condenser or reboiler: liquid enters on tray 1, gas under the last tray."
            ObjectType.ReboiledAbsorber -> "Stripper: liquid feed on tray 1, a reboiler at the bottom, vapour leaves the top."
            else -> "Trays are theoretical stages counted from the top, condenser and reboiler not included."
        },
    )
    SpecField(obj, SpecKey.TRAYS, unitSet, ctx, actions)
    for (port in Columns.variableFeedPorts(obj.type)) {
        if (fs.connections.any { it.toObject == obj.id && it.toPort == port }) SpecField(obj, SpecKey.feedStage(port), unitSet, ctx, actions)
    }
    SpecField(obj, SpecKey.TOP_PRESSURE, unitSet, ctx, actions)
    SpecField(obj, SpecKey.COLUMN_PRESSURE_DROP, unitSet, ctx, actions, optionalHint = "0 if empty")
    if (Columns.hasCondenser(obj.type)) {
        SectionLabel("Condenser")
        MethodChoice(obj, "Type", ChoiceKey.CONDENSER, ChoiceKey.CONDENSER_OPTIONS, actions)
        if (obj.refs[ChoiceKey.CONDENSER] == "partial") SpecField(obj, SpecKey.VAPOUR_RATE, unitSet, ctx, actions)
        SpecChoice(fs, obj, unitSet, ctx, actions, SpecKey.CONDENSER_SPECS, ChoiceKey.DISTILLATE_COMPONENT)
    }
    if (Columns.hasReboiler(obj.type)) {
        SectionLabel("Reboiler")
        SpecChoice(fs, obj, unitSet, ctx, actions, SpecKey.REBOILER_SPECS, ChoiceKey.BOTTOMS_COMPONENT)
    }
    if (obj.type != ObjectType.Absorber) {
        SectionLabel("Solver")
        MethodChoice(obj, "Method", ChoiceKey.COLUMN_SOLVER, ChoiceKey.COLUMN_SOLVERS, actions)
        Calculated("Most reliable: reflux ratio with a bottoms rate, boil-up ratio or bottoms purity. A column that misses its specs or its balances is shown as an error, never with numbers.")
    }
}

/** One of [keys] as the active spec, with a component picker for fractions and recoveries. */
@Composable
private fun SpecChoice(
    fs: Flowsheet, obj: SimObject, unitSet: UnitSet, ctx: UnitContext, actions: EditorActions,
    keys: List<String>, componentRef: String,
) {
    var chosen by remember(obj.id, keys) { mutableStateOf(keys.firstOrNull { it in obj.specs } ?: keys.first()) }
    Choice("Specification", SpecKey.label(chosen), keys.map { it to SpecKey.label(it) }, testTag = "spec_choice_${keys.first()}") { chosen = it }
    if (chosen.endsWith("Fraction") || chosen.endsWith("Recovery")) {
        Choice("Component", obj.refs[componentRef], fs.compounds.map { it to it }, testTag = "choice_$componentRef") {
            actions.setRef(obj.id, componentRef, it)
        }
    }
    SpecField(obj, chosen, unitSet, ctx, actions, exclusiveGroup = keys)
}

/** Fenske–Underwood–Gilliland: keys, their purities, reflux and pressures. */
@Composable
private fun ShortcutColumnEditor(fs: Flowsheet, obj: SimObject, unitSet: UnitSet, ctx: UnitContext, actions: EditorActions) {
    Calculated("Shortcut design: minimum reflux and stages, the stages for this reflux, and the duties.")
    Choice("Light key", obj.refs[ChoiceKey.LIGHT_KEY], fs.compounds.map { it to it }, testTag = "choice_lightKey") { actions.setRef(obj.id, ChoiceKey.LIGHT_KEY, it) }
    Choice("Heavy key", obj.refs[ChoiceKey.HEAVY_KEY], fs.compounds.map { it to it }, testTag = "choice_heavyKey") { actions.setRef(obj.id, ChoiceKey.HEAVY_KEY, it) }
    SpecField(obj, SpecKey.LIGHT_KEY_IN_BOTTOMS, unitSet, ctx, actions)
    SpecField(obj, SpecKey.HEAVY_KEY_IN_DISTILLATE, unitSet, ctx, actions)
    SpecField(obj, SpecKey.REFLUX_RATIO, unitSet, ctx, actions)
    SpecField(obj, SpecKey.TOP_PRESSURE, unitSet, ctx, actions, labelOverride = "Condenser pressure")
    SpecField(obj, SpecKey.REBOILER_PRESSURE, unitSet, ctx, actions, optionalHint = "condenser pressure if empty")
    MethodChoice(obj, "Condenser", ChoiceKey.CONDENSER, ChoiceKey.CONDENSER_OPTIONS, actions)
}

/** "<target · variable> = multiplier × <source · variable> + offset", applied before every solve. */
@Composable
private fun SetEditor(fs: Flowsheet, obj: SimObject, unitSet: UnitSet, ctx: UnitContext, actions: EditorActions) {
    val candidates = fs.objects.filter {
        it.id != obj.id && SpecKey.forType(it.type).isNotEmpty() && (it.type != ObjectType.MaterialStream || fs.isFeed(it))
    }
    val target = candidates.firstOrNull { it.id == obj.refs[SetKeys.TARGET_OBJECT] }
    val key = obj.refs[SetKeys.VARIABLE]
    val source = fs.objects.firstOrNull { it.id == obj.refs[SetKeys.SOURCE_OBJECT] }

    SectionLabel("Target")
    Choice("Object", target?.tag, candidates.map { it.id to "${it.tag} · ${it.type.displayName()}" }) {
        actions.setRef(obj.id, SetKeys.TARGET_OBJECT, it)
        actions.setRef(obj.id, SetKeys.VARIABLE, null)
        actions.setRef(obj.id, SetKeys.SOURCE_OBJECT, null)
    }
    if (target != null) {
        Choice("Variable", key?.let { SpecKey.label(it) }, SpecKey.forType(target.type).map { it to SpecKey.label(it) }) {
            actions.setRef(obj.id, SetKeys.VARIABLE, it)
            actions.setRef(obj.id, SetKeys.SOURCE_OBJECT, null)
        }
    }
    if (target != null && key != null) {
        SectionLabel("Source")
        val sources = fs.objects.filter { it.id != target.id && it.id != obj.id && key in it.specs }
        Choice("Object", source?.tag, sources.map { it.id to "${it.tag} · ${it.type.displayName()}" }) {
            actions.setRef(obj.id, SetKeys.SOURCE_OBJECT, it)
        }
        val q = SpecKey.quantity(key)
        SpecField(obj, SetKeys.MULTIPLIER, unitSet, ctx, actions, quantityOverride = Quantity.Ratio, labelOverride = "Multiplier", optionalHint = "1 if empty")
        SpecField(obj, SetKeys.OFFSET, unitSet, ctx, actions, quantityOverride = differenceOf(q), labelOverride = "Offset", optionalHint = "0 if empty")
        Calculated("${target.tag} ${SpecKey.label(key).lowercase()} = multiplier × ${source?.tag ?: "source"} ${SpecKey.label(key).lowercase()} + offset")
    }
}

/** "Vary <object · spec> until <object · result> = target", with optional tolerance and bounds. */
@Composable
private fun AdjustEditor(fs: Flowsheet, obj: SimObject, unitSet: UnitSet, ctx: UnitContext, actions: EditorActions) {
    val others = fs.objects.filter { it.id != obj.id && it.type != ObjectType.Adjust }
    val mId = obj.refs[AdjustKeys.MANIPULATED_OBJECT]
    val mKey = obj.refs[AdjustKeys.MANIPULATED_SPEC]
    val cId = obj.refs[AdjustKeys.CONTROLLED_OBJECT]
    val cKey = obj.refs[AdjustKeys.CONTROLLED_PROPERTY]
    val manipulated = others.firstOrNull { it.id == mId }
    val controlled = others.firstOrNull { it.id == cId }

    SectionLabel("Vary")
    Choice("Object", manipulated?.tag, others.filter { it.specs.isNotEmpty() }.map { it.id to "${it.tag} · ${it.type.displayName()}" }) {
        actions.setRef(obj.id, AdjustKeys.MANIPULATED_OBJECT, it)
        actions.setRef(obj.id, AdjustKeys.MANIPULATED_SPEC, null)
    }
    if (manipulated != null) {
        Choice("Specification", mKey?.let { SpecKey.label(it) }, manipulated.specs.keys.map { it to SpecKey.label(it) }) {
            actions.setRef(obj.id, AdjustKeys.MANIPULATED_SPEC, it)
        }
    }
    SectionLabel("Until")
    Choice("Object", controlled?.tag, others.map { it.id to "${it.tag} · ${it.type.displayName()}" }) {
        actions.setRef(obj.id, AdjustKeys.CONTROLLED_OBJECT, it)
        actions.setRef(obj.id, AdjustKeys.CONTROLLED_PROPERTY, null)
    }
    if (controlled != null) {
        val keys = if (controlled.type == ObjectType.MaterialStream) ResultKeys.STREAM else ResultKeys.forType(controlled.type)
        Choice("Result", cKey?.let { ResultKeys.label(it) }, keys.map { it to ResultKeys.label(it) }) {
            actions.setRef(obj.id, AdjustKeys.CONTROLLED_PROPERTY, it)
        }
    }
    if (cKey != null) {
        val q = ResultKeys.quantity(cKey)
        SpecField(obj, AdjustKeys.TARGET, unitSet, ctx, actions, quantityOverride = q, labelOverride = "Target ${ResultKeys.label(cKey).lowercase()}")
        SpecField(obj, AdjustKeys.TOLERANCE, unitSet, ctx, actions, quantityOverride = differenceOf(q),
            labelOverride = "Tolerance (optional)", optionalHint = "1e-6 relative if empty")
    }
    if (mKey != null) {
        val q = SpecKey.quantity(mKey)
        SpecField(obj, AdjustKeys.MINIMUM, unitSet, ctx, actions, quantityOverride = q, labelOverride = "Lowest allowed ${SpecKey.label(mKey).lowercase()}", optionalHint = "no limit")
        SpecField(obj, AdjustKeys.MAXIMUM, unitSet, ctx, actions, quantityOverride = q, labelOverride = "Highest allowed ${SpecKey.label(mKey).lowercase()}", optionalHint = "no limit")
    }
}

/** A tolerance on a temperature or pressure is a difference, not an absolute value. */
private fun differenceOf(q: Quantity): Quantity = when (q) {
    Quantity.Temperature -> Quantity.TemperatureDifference
    Quantity.Pressure -> Quantity.PressureDifference
    else -> q
}

@Composable
private fun Choice(
    label: String,
    selected: String?,
    options: List<Pair<String, String>>,
    placeholder: String = "Choose…",
    testTag: String? = null,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().then(if (testTag != null) Modifier.testTag(testTag) else Modifier)) {
        OutlinedTextField(
            value = selected ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // Transparent tap target over the read-only field.
        Box(
            Modifier
                .matchParentSize()
                .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Choose $label") { open = true }
                .semantics { contentDescription = "$label: ${selected ?: placeholder}" },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (options.isEmpty()) DropdownMenuItem(text = { Text("Nothing to choose yet") }, onClick = { open = false }, enabled = false)
            options.forEach { (id, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(id) }) }
        }
    }
}

/** Calculated values for this object: only what the engine returned, "—" with the reason otherwise. */
@Composable
private fun ResultsSection(obj: SimObject, results: ResultView, unitSet: UnitSet, ctx: UnitContext) {
    val keys = when (obj.type) {
        ObjectType.MaterialStream -> listOf("temperature", "pressure", "vapourFraction", "molarFlow", "massFlow", "massEnthalpy", "density", "molecularWeight")
        ObjectType.Adjust -> listOf("manipulatedValue", "controlledValue", "iterations")
        else -> ResultKeys.forType(obj.type)
    }
    if (keys.isEmpty()) return
    HorizontalDivider()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Results", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (results.stale) Text("⚠ out of date", style = NumberStyles.tag, color = MaterialTheme.valueColors.underSpecifiedText)
    }
    for (key in keys) {
        val unit = unitSet.unitFor(ResultKeys.quantityFor(obj, key))
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
    quantityOverride: Quantity? = null,
    labelOverride: String? = null,
) {
    val quantity = quantityOverride ?: SpecKey.quantity(key)
    val spec = obj.specs[key]
    var unit by remember(obj.id, key, spec?.unit, unitSet) {
        mutableStateOf(
            spec?.unit?.let { Units.byIdOrNull(it) }?.takeIf { it.quantity == quantity }
                // Engineers think of efficiency in percent.
                ?: if (key == SpecKey.EFFICIENCY) Units.percent else defaultUnit(key, quantity, unitSet),
        )
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
            label = { Text(labelOverride ?: SpecKey.label(key)) },
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

/** The unit set's unit, except that diameters and roughness use mm or inches. */
private fun defaultUnit(key: String, quantity: Quantity, unitSet: UnitSet): EngUnit {
    val u = unitSet.unitFor(quantity)
    if (key !in SpecKey.SMALL_LENGTHS) return u
    return when (u) {
        Units.m -> Units.mm
        Units.ft -> Units.inch
        else -> u
    }
}

@Composable
private fun UnitPicker(selected: EngUnit, options: List<EngUnit>, onPick: (EngUnit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { open = true },
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Unit ${selected.symbol.ifEmpty { "fraction" }}, change" },
        ) { Text(selected.symbol.ifEmpty { selected.id }, style = NumberStyles.value) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { u ->
                DropdownMenuItem(text = { Text(u.symbol.ifEmpty { u.id }, style = NumberStyles.value) }, onClick = { open = false; onPick(u) })
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
