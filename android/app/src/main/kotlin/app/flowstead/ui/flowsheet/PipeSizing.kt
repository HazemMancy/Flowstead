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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.flowstead.core.format.ValueFormat
import app.flowstead.core.model.ChoiceKey
import app.flowstead.core.model.DisplayValue
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.LineCriteria
import app.flowstead.core.model.LineService
import app.flowstead.core.model.LineSizing
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.PipeSizes
import app.flowstead.core.model.ResultView
import app.flowstead.core.model.SimObject
import app.flowstead.core.model.SizingCriteria
import app.flowstead.core.model.SpecKey
import app.flowstead.core.units.Quantity
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.UnitSet
import app.flowstead.core.units.Units
import app.flowstead.ui.components.ValueText
import app.flowstead.ui.theme.NumberStyles
import app.flowstead.ui.theme.valueColors

private const val CUSTOM = "__custom"
private const val AUTO = "__auto"

/**
 * One pipe section, HYSYS-style: nominal size and schedule from ASME B36.10M/B36.19M (or a custom inside
 * diameter), length and elevation, the two-phase flow correlation, the line's service and heat transfer.
 */
@Composable
internal fun PipeEditor(fs: Flowsheet, obj: SimObject, unitSet: UnitSet, ctx: UnitContext, actions: EditorActions) {
    SpecField(obj, SpecKey.LENGTH, unitSet, ctx, actions)

    SectionLabel("Pipe size")
    PipeSizeChoice(obj, unitSet, ctx, actions)

    SpecField(obj, SpecKey.ELEVATION_CHANGE, unitSet, ctx, actions, optionalHint = "0 (horizontal) if empty")
    SpecField(obj, SpecKey.ROUGHNESS, unitSet, ctx, actions, optionalHint = "0.0457 mm (commercial steel) if empty")
    SpecField(obj, SpecKey.INCREMENTS, unitSet, ctx, actions, optionalHint = "10 if empty; 20–50 for long gas lines")

    SectionLabel("Flow model")
    Calculated(
        "All gas or all liquid: Darcy–Weisbach with the Chen friction factor (Churchill in the laminar–turbulent " +
            "transition), applied automatically in every increment. Gas density is re-evaluated along the line, so " +
            "compressibility is followed increment by increment. When gas and liquid flow together, the correlation below applies.",
    )
    MethodChoice(obj, "Two-phase correlation", ChoiceKey.PIPE_CORRELATION, ChoiceKey.PIPE_CORRELATIONS, actions)
    Calculated(
        when (obj.refs[ChoiceKey.PIPE_CORRELATION] ?: "beggs_brill") {
            "petalas_aziz" -> "Petalas & Aziz: mechanistic flow-pattern model, any inclination. A good cross-check on Beggs & Brill."
            "lockhart_martinelli" -> "Lockhart & Martinelli: horizontal flow only; use it for flat lines and as a comparison."
            else -> "Beggs & Brill (recommended): flow-pattern map with holdup corrected for inclination; " +
                "valid uphill, downhill and horizontal."
        },
    )
    Choice(
        label = "Service (sizing criteria)",
        selected = LineSizing.SERVICE_OPTIONS.firstOrNull { it.first == obj.refs[LineSizing.SERVICE] }?.second,
        options = LineSizing.SERVICE_OPTIONS.map { (it.first ?: AUTO) to it.second },
        testTag = "pipe_service",
    ) { picked -> actions.setRef(obj.id, LineSizing.SERVICE, picked.takeIf { it != AUTO }) }

    SectionLabel("Heat transfer")
    SpecField(obj, SpecKey.HEAT_TRANSFER_COEFFICIENT, unitSet, ctx, actions, optionalHint = "adiabatic if empty")
    SpecField(obj, SpecKey.AMBIENT_TEMPERATURE, unitSet, ctx, actions, optionalHint = "needed with U")
}

/**
 * Nominal size and schedule from ASME B36.10M/B36.19M, which set the inside diameter (a pipe segment's, or the line an
 * orifice plate sits in), with the standard's dimensions shown; or "Custom inside diameter" to type it.
 */
@Composable
internal fun PipeSizeChoice(obj: SimObject, unitSet: UnitSet, ctx: UnitContext, actions: EditorActions) {
    val size = obj.refs[LineSizing.NPS]?.let { PipeSizes.size(it) }
    val schedule = size?.let { s -> obj.refs[LineSizing.SCHEDULE]?.let { s.schedule(it) } }
    val pipe = obj.type == ObjectType.PipeSegment
    Choice(
        label = "Nominal size",
        selected = size?.label ?: "Custom inside diameter",
        options = listOf(CUSTOM to "Custom inside diameter") + PipeSizes.ALL.map { it.nps to it.label },
        testTag = "pipe_nps",
    ) { picked -> actions.setPipeSize(obj.id, picked.takeIf { it != CUSTOM }, schedule?.label) }
    if (size != null && schedule != null) {
        Choice(
            label = "Schedule",
            selected = schedule.label,
            options = size.schedules.map { it.label to "${it.label}  ·  wall ${wall(it.wallM, unitSet, ctx)}" },
            testTag = "pipe_schedule",
        ) { picked -> actions.setPipeSize(obj.id, size.nps, picked) }
        val small = smallLength(unitSet)
        DimensionRow("Outside diameter", size.outsideDiameterM, small, ctx)
        DimensionRow("Wall thickness", schedule.wallM, small, ctx)
        DimensionRow(if (pipe) "Inside diameter" else "Pipe inside diameter", size.insideDiameterM(schedule), small, ctx)
        Calculated("Dimensions from ASME B36.10M (carbon steel) and B36.19M (stainless, “S” schedules).")
    } else {
        SpecField(obj, PipeSizes.insideDiameterKey(obj.type), unitSet, ctx, actions)
        if (pipe) SpecField(obj, SpecKey.OUTER_DIAMETER, unitSet, ctx, actions, optionalHint = "1.15 × inside if empty (heat transfer only)")
    }
}

private fun smallLength(unitSet: UnitSet) = if (unitSet.unitFor(Quantity.Length) == Units.ft) Units.inch else Units.mm

private fun wall(m: Double, unitSet: UnitSet, ctx: UnitContext) = ValueFormat.si(m, smallLength(unitSet), ctx)

/** A dimension taken from the pipe standard: an input, shown in the specified colour. */
@Composable
private fun DimensionRow(label: String, si: Double, unit: app.flowstead.core.units.EngUnit, ctx: UnitContext) {
    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        ValueText(DisplayValue.Specified(si), unit, ctx = ctx)
    }
}

/**
 * The line checked against the user's sizing criteria for its service. Values come from the engine's
 * result; a criterion that is not set, or has no engine value to compare, is not shown.
 */
@Composable
internal fun PipeSizingSection(fs: Flowsheet, obj: SimObject, results: ResultView, unitSet: UnitSet, ctx: UnitContext, actions: EditorActions) {
    val service = LineSizing.service(fs, obj, results)
    HorizontalDivider()
    Text(
        "Line sizing" + (service?.let { " — ${it.label.lowercase()} service" } ?: ""),
        style = MaterialTheme.typography.titleSmall,
    )
    results.text(obj.id, "flowRegime")?.let { regime ->
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Flow regime", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(regime, style = NumberStyles.value, color = MaterialTheme.valueColors.calculated)
        }
    }
    if (service == null) {
        Calculated("Solve the flowsheet to check this line against your sizing criteria.")
    } else {
        val criteria = (fs.sizingCriteria ?: SizingCriteria()).forService(service)
        val checks = LineSizing.checks(criteria, obj.id, results)
        if (criteria.isEmpty) Calculated("No sizing criteria set for ${service.label.lowercase()} lines.")
        else if (checks.isEmpty()) Calculated("The engine returned none of the values your criteria need.")
        for (c in checks) {
            val unit = unitSet.unitFor(c.quantity)
            val colors = MaterialTheme.valueColors
            val verdict = if (c.passes) "✓" else "✗"
            Row(
                Modifier.fillMaxWidth().heightIn(min = 40.dp).semantics(mergeDescendants = true) {
                    contentDescription = "${c.label}: ${ValueFormat.si(c.value, unit, ctx)}, limit ${ValueFormat.si(c.limit, unit, ctx)}, " +
                        if (c.passes) "within the limit" else "outside the limit"
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(c.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        (if (c.isMaximum) "≤ " else "≥ ") + ValueFormat.si(c.limit, unit, ctx) + (c.note?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ValueText(DisplayValue.Calculated(c.value, results.stale), unit, ctx = ctx)
                Text(verdict, style = MaterialTheme.typography.titleMedium, color = if (c.passes) colors.convergedText else colors.error)
            }
        }
    }
    TextButton(onClick = actions::editSizingCriteria, modifier = Modifier.testTag("edit_sizing_criteria")) { Text("Sizing criteria…") }
}

/** The flowsheet's line-sizing limits, one tab per service. Empty fields are not checked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SizingCriteriaSheet(
    criteria: SizingCriteria,
    unitSet: UnitSet,
    ctx: UnitContext,
    onChange: (SizingCriteria) -> Unit,
    onDismiss: () -> Unit,
) {
    var service by remember { mutableStateOf(LineService.Gas) }
    // Fields commit when focus leaves them; a tap on a tab or button doesn't take focus, so commit first.
    val focus = LocalFocusManager.current
    val close = { focus.clearFocus(); onDismiss() }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Line sizing criteria", style = MaterialTheme.typography.titleLarge)
            Calculated("Your company's limits. Each pipe is checked against the set for its service; leave a field empty to skip it.")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                LineService.entries.forEachIndexed { i, s ->
                    SegmentedButton(
                        selected = service == s,
                        onClick = { focus.clearFocus(); service = s },
                        shape = SegmentedButtonDefaults.itemShape(i, LineService.entries.size),
                        modifier = Modifier.testTag("criteria_${s.name}"),
                    ) { Text(s.label) }
                }
            }
            val c = criteria.forService(service)
            fun set(update: (LineCriteria) -> LineCriteria) = onChange(criteria.with(service, update(c)))
            val key = service.name
            QuantityField("Maximum velocity", c.maxVelocity, Quantity.Velocity, unitSet.unitFor(Quantity.Velocity), ctx, "$key.vmax", testTag = "crit_vmax") { v ->
                set { it.copy(maxVelocity = v) }
            }
            QuantityField("Minimum velocity", c.minVelocity, Quantity.Velocity, unitSet.unitFor(Quantity.Velocity), ctx, "$key.vmin", testTag = "crit_vmin") { v ->
                set { it.copy(minVelocity = v) }
            }
            QuantityField("Maximum ρv²", c.maxMomentumFlux, Quantity.MomentumFlux, unitSet.unitFor(Quantity.MomentumFlux), ctx, "$key.rv2") { v ->
                set { it.copy(maxMomentumFlux = v) }
            }
            QuantityField(
                "Maximum ΔP per length (friction)", c.maxFrictionGradient, Quantity.PressureGradient,
                unitSet.unitFor(Quantity.PressureGradient), ctx, "$key.dpdl", testTag = "crit_dpdl",
            ) { v -> set { it.copy(maxFrictionGradient = v) } }
            if (service != LineService.Liquid) {
                QuantityField("Maximum Mach number", c.maxMach, Quantity.Ratio, Units.ratio, ctx, "$key.mach") { v ->
                    set { it.copy(maxMach = v) }
                }
            }
            QuantityField(
                "Erosional velocity constant C (API RP 14E)", c.erosionalC, Quantity.Ratio, Units.ratio, ctx, "$key.c",
                hint = "e.g. 100 continuous, 125 intermittent",
            ) { v -> set { it.copy(erosionalC = v) } }
            Calculated(
                "API RP 14E: v_e = C / √ρ_m (ft/s, ρ_m in lb/ft³), with the no-slip mixture density. " +
                    "Flowstead checks the highest v·√ρ_m along the line, i.e. the highest ρv².",
            )
            TextButton(onClick = close, modifier = Modifier.align(Alignment.End)) { Text("Done") }
        }
    }
}
