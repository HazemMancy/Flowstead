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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.flowstead.core.format.ValueFormat
import app.flowstead.core.model.CaseOutput
import app.flowstead.core.model.CaseStudies
import app.flowstead.core.model.CaseStudy
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.ResultKeys
import app.flowstead.core.model.Spec
import app.flowstead.core.model.SpecKey
import app.flowstead.core.model.displayName
import app.flowstead.core.model.modelHash
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.UnitSet
import app.flowstead.ui.components.PlotsSection
import app.flowstead.ui.theme.valueColors
import java.util.UUID

/** A case study running now: [done] of [total] points solved. */
data class CaseRunUi(val studyId: String, val done: Int, val total: Int)

/**
 * Case studies (D51), as HYSYS's: vary one specification over a range, solve at each point, plot the results.
 * Every number recorded is the engine's, from a full solve at that point.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaseStudySheet(
    fs: Flowsheet,
    run: CaseRunUi?,
    unitSet: UnitSet,
    ctx: UnitContext,
    onSave: (CaseStudy) -> Unit,
    onDelete: (String) -> Unit,
    onRun: (String) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    var openId by rememberSaveable { mutableStateOf(fs.caseStudies.firstOrNull()?.id) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
                .testTag("case_studies"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Case studies", style = MaterialTheme.typography.titleLarge)
            Calculated("Vary one specification over a range; the whole flowsheet is solved at each point and the results you choose are recorded.")
            for (cs in fs.caseStudies) {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { openId = if (openId == cs.id) null else cs.id }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(cs.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
                    }
                    if (cs.results?.let { it.modelHash != fs.modelHash() } == true) {
                        Text("⚠ out of date", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.valueColors.underSpecifiedText)
                    }
                }
                if (openId == cs.id) StudyEditor(fs, cs, run?.takeIf { it.studyId == cs.id }, run != null, unitSet, ctx, onSave, onDelete, onRun, onCancel)
            }
            HorizontalDivider()
            TextButton(
                onClick = {
                    val cs = CaseStudy(UUID.randomUUID().toString(), "Case study ${fs.caseStudies.size + 1}")
                    onSave(cs)
                    openId = cs.id
                },
                modifier = Modifier.heightIn(min = 48.dp).testTag("case_new"),
            ) { Text("New case study") }
        }
    }
}

@Composable
private fun StudyEditor(
    fs: Flowsheet,
    cs: CaseStudy,
    run: CaseRunUi?,
    busy: Boolean,
    unitSet: UnitSet,
    ctx: UnitContext,
    onSave: (CaseStudy) -> Unit,
    onDelete: (String) -> Unit,
    onRun: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val variable = fs.objects.firstOrNull { it.id == cs.objectId }
    val candidates = fs.objects.filter { it.specs.isNotEmpty() && it.type != ObjectType.Adjust && it.type != ObjectType.Set }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NameField(cs) { onSave(cs.copy(name = it)) }

        SectionLabel("Vary")
        Choice("Object", variable?.tag, candidates.map { it.id to "${it.tag} · ${it.type.displayName()}" }, testTag = "case_object") {
            onSave(cs.copy(objectId = it, specKey = null, from = null, to = null))
        }
        if (variable != null) {
            Choice("Specification", cs.specKey?.let { SpecKey.label(it) }, variable.specs.keys.map { it to SpecKey.label(it) }, testTag = "case_spec") { key ->
                // Start around the current value: ±20 %.
                val now = variable.specs.getValue(key).si
                onSave(cs.copy(specKey = key, from = now * 0.8, to = now * 1.2))
            }
        }
        val key = cs.specKey
        if (variable != null && key != null && key in variable.specs) {
            val q = SpecKey.quantity(key)
            val unit = variable.specs.getValue(key).unit
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    QuantityField("From", cs.from?.let { Spec(it, unit) }, q, defaultUnit(key, q, unitSet), ctx, "case_from_${cs.id}") { s -> onSave(cs.copy(from = s?.si)) }
                }
                Column(Modifier.weight(1f)) {
                    QuantityField("To", cs.to?.let { Spec(it, unit) }, q, defaultUnit(key, q, unitSet), ctx, "case_to_${cs.id}") { s -> onSave(cs.copy(to = s?.si)) }
                }
            }
            PlainNumber("Steps", cs.steps.toDouble(), "case_steps", hint = "10") { v ->
                onSave(cs.copy(steps = (v ?: 10.0).toInt().coerceIn(1, CaseStudies.MAX_STEPS)))
            }
        }

        SectionLabel("Record")
        cs.outputs.forEachIndexed { i, o ->
            val obj = fs.objects.firstOrNull { it.id == o.objectId }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${obj?.tag ?: "?"} · ${ResultKeys.label(o.key)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { onSave(cs.copy(outputs = cs.outputs.filterIndexed { j, _ -> j != i })) }) {
                    Icon(Icons.Filled.Close, contentDescription = "Stop recording ${ResultKeys.label(o.key)}")
                }
            }
        }
        AddOutput(fs) { onSave(cs.copy(outputs = cs.outputs + it)) }

        val problem = CaseStudies.problem(fs, cs)
        problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.valueColors.underSpecifiedText) }
        if (run != null) {
            LinearProgressIndicator(progress = { run.done.toFloat() / run.total }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Solving point ${run.done + 1} of ${run.total}…".takeIf { run.done < run.total } ?: "Saving…", modifier = Modifier.weight(1f))
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Stop") }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onRun(cs.id) }, enabled = problem == null && !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("case_run")) {
                    Text(if (cs.results == null) "Run" else "Run again")
                }
                TextButton(onClick = { onDelete(cs.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete", color = MaterialTheme.valueColors.error) }
            }
        }

        cs.results?.let { results ->
            val stale = results.modelHash != fs.modelHash()
            PlotsSection(CaseStudies.plots(fs, cs), unitSet, ctx, stale, "case_${cs.id}")
            ResultsTable(fs, cs, unitSet, ctx)
        }
    }
}

@Composable
private fun NameField(cs: CaseStudy, onCommit: (String) -> Unit) {
    var text by remember(cs.id, cs.name) { mutableStateOf(cs.name) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text("Name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused && text.isNotBlank() && text != cs.name) onCommit(text.trim()) },
    )
}

/** Pick an object, then one of its results, to record. */
@Composable
private fun AddOutput(fs: Flowsheet, onAdd: (CaseOutput) -> Unit) {
    var objectId by remember { mutableStateOf<String?>(null) }
    val obj = fs.objects.firstOrNull { it.id == objectId }
    val objects = fs.objects.filter { it.type != ObjectType.EnergyStream && it.type != ObjectType.Set }
    Choice("Add a result: object", obj?.tag, objects.map { it.id to "${it.tag} · ${it.type.displayName()}" }, testTag = "case_out_object") { objectId = it }
    if (obj != null) {
        val keys = if (obj.type == ObjectType.MaterialStream) ResultKeys.STREAM else ResultKeys.forType(obj.type)
        Choice("Result", null, keys.map { it to ResultKeys.label(it) }, testTag = "case_out_key") {
            onAdd(CaseOutput(obj.id, it))
            objectId = null
        }
    }
}

/** The recorded numbers: the varied value, then each result; a failed point shows why. */
@Composable
private fun ResultsTable(fs: Flowsheet, cs: CaseStudy, unitSet: UnitSet, ctx: UnitContext) {
    val r = cs.results ?: return
    val key = cs.specKey ?: return
    val xu = defaultUnit(key, SpecKey.quantity(key), unitSet)
    val units = cs.outputs.map { o -> fs.objects.firstOrNull { it.id == o.objectId }?.let { unitSet.unitFor(ResultKeys.quantityFor(it, o.key)) } }
    val scroll = rememberScrollState()
    Column(Modifier.horizontalScroll(scroll)) {
        Row {
            Cell("${SpecKey.label(key)}, ${xu.symbol}", header = true)
            cs.outputs.forEachIndexed { i, o ->
                val tag = fs.objects.firstOrNull { it.id == o.objectId }?.tag ?: "?"
                Cell("$tag ${ResultKeys.label(o.key)}, ${units[i]?.symbol ?: ""}", header = true)
            }
        }
        r.x.forEachIndexed { k, x ->
            Row {
                Cell(ValueFormat.number(xu.fromSi(x, ctx), xu.decimals))
                cs.outputs.indices.forEach { i ->
                    val v = r.values.getOrNull(i)?.getOrNull(k)
                    val u = units[i]
                    Cell(if (v == null || u == null) "—" else ValueFormat.number(u.fromSi(v, ctx), u.decimals))
                }
            }
        }
    }
    r.errors.withIndex().filter { it.value != null }.forEach { (k, e) ->
        Text(
            "At ${ValueFormat.number(xu.fromSi(r.x[k], ctx), xu.decimals)} ${xu.symbol}: $e",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.valueColors.error,
        )
    }
}

@Composable
private fun Cell(text: String, header: Boolean = false) {
    Text(
        text,
        style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
        maxLines = 2,
        modifier = Modifier.width(112.dp).padding(end = 8.dp, top = 4.dp, bottom = 4.dp),
    )
}
