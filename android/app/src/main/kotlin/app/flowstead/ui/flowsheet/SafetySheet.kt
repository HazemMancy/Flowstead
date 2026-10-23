/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.flowsheet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.flowstead.core.api.LineCheck
import app.flowstead.core.api.ReliefLine
import app.flowstead.core.api.ReliefResult
import app.flowstead.core.api.ReliefScenario
import app.flowstead.core.api.ReliefScenarioKinds
import app.flowstead.core.api.ValveDesigns
import app.flowstead.core.api.VesselGeometry
import app.flowstead.core.format.ValueFormat
import app.flowstead.core.model.DepressuringCase
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.PipeSizes
import app.flowstead.core.model.ReliefDeviceDef
import app.flowstead.core.model.ResultView
import app.flowstead.core.model.Safety
import app.flowstead.core.model.SimObject
import app.flowstead.core.model.Spec
import app.flowstead.core.model.SpecKey
import app.flowstead.core.model.displayName
import app.flowstead.core.model.modelHash
import app.flowstead.core.units.EngUnit
import app.flowstead.core.units.Quantity
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.UnitSet
import app.flowstead.ui.components.PlotsSection
import app.flowstead.ui.theme.valueColors
import java.util.UUID

/** What the safety sheet can ask the view model to do. */
interface SafetyActions {
    fun saveDevice(d: ReliefDeviceDef)
    fun deleteDevice(id: String)
    fun runDevice(id: String)
    fun saveCase(c: DepressuringCase)
    fun deleteCase(id: String)
    fun runCase(id: String)
}

/**
 * The safety analysis (D54–D57), as HYSYS's: relief valves sized for their scenarios with their inlet and outlet lines
 * checked, and vessel depressuring. The fluids and their states come from the solved flowsheet; the engine does every
 * flash; the standards' equations are listed with each result.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SafetySheet(
    fs: Flowsheet,
    results: ResultView,
    running: Set<String>,
    unitSet: UnitSet,
    ctx: UnitContext,
    actions: SafetyActions,
    onDismiss: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val study = fs.safety
    val vessels = fs.objects.filter { it.type in Safety.PROTECTABLE }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
                .testTag("safety"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Safety analysis", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Relief valves") })
                FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Depressuring") })
            }
            if (vessels.isEmpty()) {
                Calculated("Add a separator or tank to the flowsheet: relief valves and depressuring protect vessels.")
                return@Column
            }
            if (tab == 0) {
                Calculated("API 521 relief loads and API 520 sizing, with the inlet-loss and back-pressure checks of API 520 Part II.")
                for (d in study?.devices.orEmpty()) {
                    HorizontalDivider()
                    Header(d.tag, d.result?.let { "${it.valves} × ${it.orifice}" }, stale = d.result != null && d.resultHash != fs.modelHash()) {
                        openId = if (openId == d.id) null else d.id
                    }
                    if (openId == d.id) ReliefEditor(fs, results, d, vessels, d.id in running, unitSet, ctx, actions)
                }
                HorizontalDivider()
                TextButton(
                    onClick = {
                        val n = (study?.devices?.size ?: 0) + 100
                        val d = ReliefDeviceDef(
                            UUID.randomUUID().toString(), "PSV-$n", protectedId = vessels.first().id,
                            // HYSYS-like: start from the scenarios the flowsheet suggests for the vessel.
                            scenarios = Safety.suggest(fs, results, vessels.first().id).map { it.scenario }
                                .ifEmpty { listOf(ReliefScenario(ReliefScenarioKinds.FIRE, "Fire"), ReliefScenario(ReliefScenarioKinds.BLOCKED_OUTLET, "Blocked outlet")) },
                        )
                        actions.saveDevice(d)
                        openId = d.id
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("add_psv"),
                ) { Text("Add a relief valve") }
            } else {
                Calculated("Blowdown through a restriction orifice to the flare or atmosphere (API 521): pressure, fluid and wall temperatures, and flow against time.")
                for (c in study?.depressuring.orEmpty()) {
                    HorizontalDivider()
                    Header(c.name, c.result?.timeToTarget?.let { "${ValueFormat.number(it / 60, 1)} min" }, stale = c.result != null && c.resultHash != fs.modelHash()) {
                        openId = if (openId == c.id) null else c.id
                    }
                    if (openId == c.id) DepressuringEditor(fs, results, c, vessels, c.id in running, unitSet, ctx, actions)
                }
                HorizontalDivider()
                TextButton(
                    onClick = {
                        val v = vessels.first()
                        // Start from the vessel dimensions a relief valve on it already has.
                        val geometry = study?.devices?.firstOrNull { it.protectedId == v.id }?.vessel
                        val c = DepressuringCase(UUID.randomUUID().toString(), "Depressuring ${(study?.depressuring?.size ?: 0) + 1}", v.id, geometry)
                        actions.saveCase(c)
                        openId = c.id
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("add_blowdown"),
                ) { Text("Add a depressuring case") }
            }
        }
    }
}

@Composable
private fun Header(title: String, summary: String?, stale: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        summary?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary) }
        if (stale) Text("  ⚠ out of date", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.valueColors.underSpecifiedText)
    }
}

// ------------------------------------------------------------------ relief valves

@Composable
private fun ReliefEditor(
    fs: Flowsheet,
    results: ResultView,
    d: ReliefDeviceDef,
    vessels: List<SimObject>,
    busy: Boolean,
    unitSet: UnitSet,
    ctx: UnitContext,
    actions: SafetyActions,
) {
    fun save(x: ReliefDeviceDef) = actions.saveDevice(x)
    val pu = unitSet.unitFor(Quantity.Pressure)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextLine("Tag", d.tag, "psv_tag_${d.id}") { save(d.copy(tag = it)) }
        Choice("Protects", vessels.firstOrNull { it.id == d.protectedId }?.tag, vessels.map { it.id to "${it.tag} · ${it.type.displayName()}" }) {
            save(d.copy(protectedId = it))
        }
        QuantityField("Set pressure", d.setPressure?.let { Spec(it, unitSet.unitFor(Quantity.Pressure).id) }, Quantity.Pressure, pu, ctx, "psv_set_${d.id}", testTag = "psv_set") {
            save(d.copy(setPressure = it?.si))
        }
        QuantityField("Back pressure (superimposed)", Spec(d.backPressure, pu.id), Quantity.Pressure, pu, ctx, "psv_back_${d.id}", hint = "atmospheric") {
            save(d.copy(backPressure = it?.si ?: 101325.0))
        }
        Choice("Valve", ValveDesigns.ALL.firstOrNull { it.first == d.design }?.second, ValveDesigns.ALL) { save(d.copy(design = it)) }
        if (d.design == app.flowstead.core.api.ValveDesigns.BALANCED_BELLOWS) {
            PlainNumber("Kb / Kw (from the valve maker)", d.kb, "psv_kb_${d.id}", hint = "1") { save(d.copy(kb = it ?: 1.0)) }
        }
        CheckLine("Rupture disk upstream (Kc 0.9)", d.ruptureDisk) { save(d.copy(ruptureDisk = it)) }

        SectionLabel("Vessel")
        VesselFields(d.vessel, "psv_${d.id}", unitSet, ctx) { save(d.copy(vessel = it)) }

        SectionLabel("Relief scenarios")
        d.scenarios.forEachIndexed { i, s ->
            ScenarioFields(s, "psv_${d.id}_$i", unitSet, ctx,
                onChange = { n -> save(d.copy(scenarios = d.scenarios.toMutableList().also { l -> l[i] = n })) },
                onRemove = { save(d.copy(scenarios = d.scenarios.filterIndexed { j, _ -> j != i })) })
        }
        val suggested = Safety.newSuggestions(fs, results, d)
        if (suggested.isNotEmpty()) {
            Text("Suggested from the flowsheet", style = MaterialTheme.typography.labelLarge)
            suggested.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.scenario.name ?: ReliefScenarioKinds.label(s.scenario.kind), style = MaterialTheme.typography.bodyMedium)
                        Text(s.reason, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { save(d.copy(scenarios = d.scenarios + s.scenario)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add") }
                }
            }
        }
        Choice("Add a scenario", null, ReliefScenarioKinds.ALL.map { it to ReliefScenarioKinds.label(it) }, placeholder = "Choose…") {
            save(d.copy(scenarios = d.scenarios + ReliefScenario(it, ReliefScenarioKinds.label(it))))
        }

        SectionLabel("Inlet line (vessel to valve)")
        LineFields(d.inletLine, "psv_in_${d.id}", unitSet, ctx) { save(d.copy(inletLine = it)) }
        SectionLabel("Outlet line (valve to discharge)")
        LineFields(d.outletLine, "psv_out_${d.id}", unitSet, ctx) { save(d.copy(outletLine = it)) }

        val problem = Safety.problem(fs, results, d)
        problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.valueColors.underSpecifiedText) }
        RunRow(busy, problem == null, if (d.result == null) "Size" else "Size again", "Sizing…", { actions.runDevice(d.id) }, { actions.deleteDevice(d.id) })
        d.result?.let { ReliefResults(it, d, unitSet, ctx) }
    }
}

@Composable
private fun ReliefResults(r: ReliefResult, d: ReliefDeviceDef, unitSet: UnitSet, ctx: UnitContext) {
    val pu = unitSet.unitFor(Quantity.Pressure)
    val wu = unitSet.unitFor(Quantity.MassFlow)
    val tu = unitSet.unitFor(Quantity.Temperature)
    fun p(v: Double) = "${ValueFormat.number(pu.fromSi(v, ctx), pu.decimals)} ${pu.symbol}"
    fun w(v: Double) = "${ValueFormat.number(wu.fromSi(v, ctx), wu.decimals)} ${wu.symbol}"
    fun area(v: Double) = "${ValueFormat.number(v * 1e6, 1)} mm² (${ValueFormat.number(v / 6.4516e-4, 3)} in²)"
    SectionLabel("Result")
    val orifice = r.orifice
    val orificeArea = r.orificeArea
    if (orifice != null && orificeArea != null) {
        Text(
            "${r.valves} × $orifice orifice (${area(orificeArea)} each)",
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("psv_orifice"),
        )
        r.requiredArea?.let { Text("Governing: ${r.governing} needs ${area(it)}", style = MaterialTheme.typography.bodyMedium) }
        r.ratedFlow?.let { Text("Rated flow ${w(it)} (used for the line checks)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    for (s in r.scenarios) {
        Column(Modifier.padding(top = 4.dp)) {
            Text(s.name, style = MaterialTheme.typography.labelLarge)
            val error = s.error
            if (error != null) {
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.valueColors.error)
            } else {
                val phase = when (s.phase) { "vapour" -> "vapour"; "liquid" -> "liquid"; "twoPhase" -> "two-phase"; else -> s.phase ?: "" }
                Text(
                    listOfNotNull(
                        s.massFlow?.let { w(it) }, phase, "relieving at ${p(s.relievingPressure)}",
                        s.relievingTemperature?.let { "${ValueFormat.number(tu.fromSi(it, ctx), tu.decimals)} ${tu.symbol}" },
                        s.requiredArea?.let { "needs ${area(it)}" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
                s.method?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                s.details["heatInput"]?.let { q -> Text("Heat input ${ValueFormat.number(q / 1000, 0)} kW" + (s.details["wettedArea"]?.let { " on ${ValueFormat.number(it, 2)} m² wetted" } ?: "") +
                    (s.details["latentHeat"]?.let { ", latent heat ${ValueFormat.number(it / 1000, 1)} kJ/kg" } ?: ""), style = MaterialTheme.typography.bodySmall) }
            }
            s.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.valueColors.underSpecifiedText) }
        }
    }
    r.inlet?.let { Check("Inlet loss", it, d, unitSet, ctx) }
    r.outlet?.let { Check("Built-up back pressure", it, d, unitSet, ctx) }
    r.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.valueColors.underSpecifiedText) }
}

@Composable
private fun Check(label: String, c: LineCheck, d: ReliefDeviceDef, unitSet: UnitSet, ctx: UnitContext) {
    val du = unitSet.unitFor(Quantity.PressureDifference)
    val colour = if (c.ok) MaterialTheme.valueColors.convergedText else MaterialTheme.valueColors.error
    val value = c.pressureDrop?.let { "${ValueFormat.number(du.fromSi(it, ctx), du.decimals)} ${du.symbol} (${ValueFormat.number((c.fractionOfSet ?: 0.0) * 100, 1)} % of set)" } ?: "—"
    Column(Modifier.padding(top = 4.dp)) {
        Text("${if (c.ok) "✓" else "✗"} $label: $value", style = MaterialTheme.typography.bodyMedium, color = colour)
        Text(c.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One scenario's inputs, by kind; blank fields fall back to what the flowsheet gives. */
@Composable
private fun ScenarioFields(s: ReliefScenario, key: String, unitSet: UnitSet, ctx: UnitContext, onChange: (ReliefScenario) -> Unit, onRemove: () -> Unit) {
    val pu = unitSet.unitFor(Quantity.Pressure)
    val tu = unitSet.unitFor(Quantity.Temperature)
    val wu = unitSet.unitFor(Quantity.MassFlow)
    Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.name ?: ReliefScenarioKinds.label(s.kind), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "Remove ${s.name ?: s.kind}") }
        }
        when (s.kind) {
            ReliefScenarioKinds.FIRE -> {
                PlainNumber("Environment factor F", s.environmentFactor, "${key}_f", hint = "1 (bare vessel)") { onChange(s.copy(environmentFactor = it ?: 1.0)) }
                CheckLine("Adequate drainage and fire-fighting", s.adequateDrainage) { onChange(s.copy(adequateDrainage = it)) }
                PlainNumber("Wetted area", s.area, "${key}_a", unit = "m²", hint = "from the vessel") { onChange(s.copy(area = it)) }
            }
            ReliefScenarioKinds.BLOCKED_OUTLET, ReliefScenarioKinds.CUSTOM -> {
                QuantityField("Relief flow", s.massFlow?.let { Spec(it, unitSet.unitFor(Quantity.MassFlow).id) }, Quantity.MassFlow, wu, ctx, "${key}_w",
                    hint = if (s.kind == ReliefScenarioKinds.BLOCKED_OUTLET) "the vessel's feed" else null) { onChange(s.copy(massFlow = it?.si)) }
                QuantityField("Fluid temperature", s.temperature?.let { Spec(it, unitSet.unitFor(Quantity.Temperature).id) }, Quantity.Temperature, tu, ctx, "${key}_t",
                    hint = if (s.kind == ReliefScenarioKinds.BLOCKED_OUTLET) "the feed's" else null) { onChange(s.copy(temperature = it?.si)) }
            }
            ReliefScenarioKinds.CONTROL_VALVE_FAILURE -> {
                QuantityField("Upstream pressure", s.upstreamPressure?.let { Spec(it, unitSet.unitFor(Quantity.Pressure).id) }, Quantity.Pressure, pu, ctx, "${key}_pu") { onChange(s.copy(upstreamPressure = it?.si)) }
                QuantityField("Upstream temperature", s.temperature?.let { Spec(it, unitSet.unitFor(Quantity.Temperature).id) }, Quantity.Temperature, tu, ctx, "${key}_t") { onChange(s.copy(temperature = it?.si)) }
                PlainNumber("Rated Cv of the valve (fully open)", s.ratedCv, "${key}_cv") { onChange(s.copy(ratedCv = it)) }
                Calculated("The vessel's own fluid unless the upstream one differs; no credit is taken for normal outflow.")
            }
            ReliefScenarioKinds.THERMAL_EXPANSION -> {
                QuantityField("Heat into the blocked-in liquid", s.heatInput?.let { Spec(it, unitSet.unitFor(Quantity.Power).id) }, Quantity.Power, unitSet.unitFor(Quantity.Power), ctx, "${key}_q") { onChange(s.copy(heatInput = it?.si)) }
                QuantityField("Liquid temperature", s.temperature?.let { Spec(it, unitSet.unitFor(Quantity.Temperature).id) }, Quantity.Temperature, tu, ctx, "${key}_t", hint = "operating") { onChange(s.copy(temperature = it?.si)) }
            }
        }
    }
}

/** Vessel dimensions: diameter, length, orientation, liquid level, height above grade. */
@Composable
private fun VesselFields(v: VesselGeometry?, key: String, unitSet: UnitSet, ctx: UnitContext, onChange: (VesselGeometry?) -> Unit) {
    val lu = unitSet.unitFor(Quantity.Length)
    val g = v ?: VesselGeometry(0.0, 0.0)
    fun set(x: VesselGeometry) = onChange(x.takeIf { it.diameter > 0 && it.length > 0 } ?: x)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            QuantityField("Diameter", v?.diameter?.takeIf { it > 0 }?.let { Spec(it, lu.id) }, Quantity.Length, lu, ctx, "${key}_d") { set(g.copy(diameter = it?.si ?: 0.0)) }
        }
        Column(Modifier.weight(1f)) {
            QuantityField("Length T/T", v?.length?.takeIf { it > 0 }?.let { Spec(it, lu.id) }, Quantity.Length, lu, ctx, "${key}_l") { set(g.copy(length = it?.si ?: 0.0)) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !g.horizontal, onClick = { set(g.copy(horizontal = false)) }, label = { Text("Vertical") })
        FilterChip(selected = g.horizontal, onClick = { set(g.copy(horizontal = true)) }, label = { Text("Horizontal") })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            QuantityField("Liquid level", Spec(g.liquidLevel, lu.id), Quantity.Length, lu, ctx, "${key}_h", hint = "0 (gas-filled)") { set(g.copy(liquidLevel = it?.si ?: 0.0)) }
        }
        Column(Modifier.weight(1f)) {
            QuantityField("Bottom above grade", Spec(g.elevation, lu.id), Quantity.Length, lu, ctx, "${key}_e", hint = "0") { set(g.copy(elevation = it?.si ?: 0.0)) }
        }
    }
}

/** A relief line: nominal size and schedule (inside diameter from ASME B36.10M/B36.19M), length and fittings. */
@Composable
private fun LineFields(line: ReliefLine?, key: String, unitSet: UnitSet, ctx: UnitContext, onChange: (ReliefLine?) -> Unit) {
    // Find the size and schedule whose inside diameter is the line's.
    val match = line?.let { l -> PipeSizes.ALL.firstNotNullOfOrNull { s -> s.schedules.firstOrNull { kotlin.math.abs(s.insideDiameterM(it) - l.insideDiameter) < 1e-6 }?.let { s to it } } }
    Choice("Nominal size", match?.first?.label ?: if (line == null) "Not checked" else "Custom",
        listOf("" to "Not checked") + PipeSizes.ALL.map { it.nps to it.label }) { nps ->
        val size = PipeSizes.size(nps)
        if (size == null) onChange(null)
        else {
            val sch = size.schedules.firstOrNull { "40" in it.label.split(" / ") } ?: size.schedules.first()
            onChange((line ?: ReliefLine(0.0, 3.0)).copy(insideDiameter = size.insideDiameterM(sch)))
        }
    }
    if (line == null) return
    match?.let { (size, sch) ->
        Choice("Schedule", sch.label, size.schedules.map { it.label to it.label }) { label ->
            size.schedule(label)?.let { onChange(line.copy(insideDiameter = size.insideDiameterM(it))) }
        }
    }
    val lu = unitSet.unitFor(Quantity.Length)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            QuantityField("Length", Spec(line.length, lu.id), Quantity.Length, lu, ctx, "${key}_len") { it?.let { s -> onChange(line.copy(length = s.si)) } }
        }
        Column(Modifier.weight(1f)) {
            QuantityField("Fittings (equivalent length)", Spec(line.fittingsLength, lu.id), Quantity.Length, lu, ctx, "${key}_fit", hint = "0") { onChange(line.copy(fittingsLength = it?.si ?: 0.0)) }
        }
    }
}

// ------------------------------------------------------------------ depressuring

@Composable
private fun DepressuringEditor(
    fs: Flowsheet,
    results: ResultView,
    c: DepressuringCase,
    vessels: List<SimObject>,
    busy: Boolean,
    unitSet: UnitSet,
    ctx: UnitContext,
    actions: SafetyActions,
) {
    fun save(x: DepressuringCase) = actions.saveCase(x)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextLine("Name", c.name, "bd_name_${c.id}") { save(c.copy(name = it)) }
        Choice("Vessel", vessels.firstOrNull { it.id == c.protectedId }?.tag, vessels.map { it.id to "${it.tag} · ${it.type.displayName()}" }) { save(c.copy(protectedId = it)) }
        Calculated("Starts from the vessel's operating pressure, temperature and contents (its feed, from the last solve).")
        VesselFields(c.vessel, "bd_${c.id}", unitSet, ctx) { save(c.copy(vessel = it)) }
        QuantityField("Back pressure", Spec(c.backPressure, unitSet.unitFor(Quantity.Pressure).id), Quantity.Pressure, unitSet.unitFor(Quantity.Pressure), ctx, "bd_back_${c.id}", hint = "atmospheric") {
            save(c.copy(backPressure = it?.si ?: 101325.0))
        }
        QuantityField("Restriction orifice diameter", c.orificeDiameter?.let { Spec(it, defaultUnit(SpecKey.INNER_DIAMETER, Quantity.Length, unitSet).id) }, Quantity.Length, defaultUnit(SpecKey.INNER_DIAMETER, Quantity.Length, unitSet), ctx,
            "bd_ro_${c.id}", hint = "sized for 15 min if empty", testTag = "bd_orifice") { save(c.copy(orificeDiameter = it?.si)) }
        CheckLine("Fire during depressuring (API 521 heat input)", c.withFire) { save(c.copy(withFire = it)) }
        Calculated("Two zones (vapour and liquid, not in equilibrium) and a wall conducting through its thickness, wet and dry sections apart.")
        QuantityField("Inside heat transfer coefficient", c.heatTransferCoefficient?.let { Spec(it, unitSet.unitFor(Quantity.HeatTransferCoefficient).id) }, Quantity.HeatTransferCoefficient,
            unitSet.unitFor(Quantity.HeatTransferCoefficient), ctx, "bd_h_${c.id}", hint = "correlations if empty; 0 adiabatic") { save(c.copy(heatTransferCoefficient = it?.si)) }
        QuantityField("Ambient temperature", c.ambientTemperature?.let { Spec(it, unitSet.unitFor(Quantity.Temperature).id) }, Quantity.Temperature,
            unitSet.unitFor(Quantity.Temperature), ctx, "bd_amb_${c.id}", hint = "the starting temperature") { save(c.copy(ambientTemperature = it?.si)) }
        val problem = Safety.depressuringProblem(fs, results, c)
        problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.valueColors.underSpecifiedText) }
        RunRow(busy, problem == null, if (c.result == null) "Run" else "Run again", "Blowing down…", { actions.runCase(c.id) }, { actions.deleteCase(c.id) })
        c.result?.let { r ->
            val tu = unitSet.unitFor(Quantity.Temperature)
            val pu = unitSet.unitFor(Quantity.Pressure)
            val wu = unitSet.unitFor(Quantity.MassFlow)
            fun t(v: Double) = "${ValueFormat.number(tu.fromSi(v, ctx), tu.decimals)} ${tu.symbol}"
            SectionLabel("Result")
            Text(
                "Orifice ${ValueFormat.number(r.orificeDiameter * 1000, 1)} mm" + if (r.orificeSized) " (sized)" else "",
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("bd_result"),
            )
            Text(
                "Target ${ValueFormat.number(pu.fromSi(r.targetPressure, ctx), pu.decimals)} ${pu.symbol} " +
                    (r.timeToTarget?.let { "reached after ${ValueFormat.number(it / 60, 1)} min" } ?: "not reached"),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Lowest vapour temperature ${t(r.minimumTemperature)}" + (r.minimumLiquidTemperature?.let { "; liquid ${t(it)}" } ?: "") +
                    "; lowest inner wall ${t(r.minimumWallTemperature)}; wall ${ValueFormat.number(r.wallThickness * 1000, 1)} mm",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("Peak flow ${ValueFormat.number(wu.fromSi(r.peakFlow, ctx), wu.decimals)} ${wu.symbol}", style = MaterialTheme.typography.bodyMedium)
            r.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.valueColors.underSpecifiedText) }
            PlotsSection(Safety.depressuringPlots(r), unitSet, ctx, c.resultHash != fs.modelHash(), "bd_${c.id}")
        }
    }
}

// ------------------------------------------------------------------ small pieces

@Composable
private fun RunRow(busy: Boolean, ready: Boolean, label: String, working: String, onRun: () -> Unit, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onRun, enabled = ready && !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("safety_run")) { Text(label) }
        if (busy) {
            Spacer(Modifier.width(12.dp))
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(working, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
        TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete", color = MaterialTheme.valueColors.error) }
    }
}

@Composable
private fun CheckLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Checkbox) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TextLine(label: String, value: String, key: String, onCommit: (String) -> Unit) {
    var text by remember(key, value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused && text.isNotBlank() && text != value) onCommit(text.trim()) },
    )
}
