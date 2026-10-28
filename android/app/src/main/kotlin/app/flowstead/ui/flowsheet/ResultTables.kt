/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.flowsheet

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.flowstead.core.api.Phases
import app.flowstead.core.model.DisplayValue
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.ResultView
import app.flowstead.core.model.StreamLayout
import app.flowstead.core.model.UnitStream
import app.flowstead.core.units.EngUnit
import app.flowstead.core.units.Quantity
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.UnitSet
import app.flowstead.ui.components.ValueText

private val LABEL_WIDTH = 150.dp
private val CELL_WIDTH = 112.dp

/**
 * A stream's results on the stream itself, as a HYSYS workbook: Overall, then Vapour, Liquid and Aqueous blocks
 * for the phases present (the same rows as the Results screen), then the composition of each phase.
 */
@Composable
internal fun StreamResults(fs: Flowsheet, streamId: String, results: ResultView, unitSet: UnitSet, ctx: UnitContext) {
    Column(Modifier.testTag("stream_results")) {
        for (section in StreamLayout.sections(results, listOf(streamId))) {
            SubHeader(section.title)
            for (row in section.rows) {
                val unit = StreamLayout.unit(row, unitSet)
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(row.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    ValueText(StreamLayout.value(results, streamId, row), unit, ctx = ctx)
                }
            }
        }
        val phases = results.presentPhases(streamId)
        if (fs.compounds.isNotEmpty() && phases.isNotEmpty()) {
            SubHeader("Composition (mole fraction)")
            val scroll = rememberScrollState()
            val fraction = unitSet.unitFor(Quantity.Fraction)
            TableRow(scroll, label = "", header = true) { phases.forEach { HeaderCell(Phases.label(it)) } }
            for (c in fs.compounds) {
                TableRow(scroll, label = c) {
                    phases.forEach { p -> Cell(results.composition(streamId, p, c), fraction, ctx) }
                }
            }
        }
    }
}

/**
 * A unit's connected streams side by side, as the HYSYS worksheet tab: inlets then outlets, with the same
 * Overall / Vapour / Liquid / Aqueous rows as a stream, and the overall composition.
 */
@Composable
internal fun UnitWorksheet(fs: Flowsheet, streams: List<UnitStream>, results: ResultView, unitSet: UnitSet, ctx: UnitContext) {
    val ids = streams.map { it.stream.id }
    val scroll = rememberScrollState()
    Column(Modifier.testTag("unit_worksheet")) {
        SubHeader("Worksheet")
        TableRow(scroll, label = "", header = true) { streams.forEach { HeaderCell(it.header) } }
        for (section in StreamLayout.sections(results, ids)) {
            TableRow(scroll, label = section.title, header = true) {}
            for (row in section.rows) {
                val unit = StreamLayout.unit(row, unitSet)
                TableRow(scroll, label = row.label) {
                    ids.forEach { Cell(StreamLayout.value(results, it, row), unit, ctx) }
                }
            }
        }
        if (fs.compounds.isNotEmpty()) {
            val fraction = unitSet.unitFor(Quantity.Fraction)
            TableRow(scroll, label = "Composition (overall mole fraction)", header = true) {}
            for (c in fs.compounds) {
                TableRow(scroll, label = c) { ids.forEach { Cell(results.composition(it, Phases.OVERALL, c), fraction, ctx) } }
            }
        }
    }
}

@Composable
private fun SubHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

/** A fixed label column and cells that scroll sideways together (one [scroll] per table). */
@Composable
private fun TableRow(scroll: ScrollState, label: String, header: Boolean = false, cells: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = if (header) 32.dp else 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(LABEL_WIDTH).padding(end = 8.dp)) {
            Text(
                label,
                style = if (header) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodySmall,
                color = if (header) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        Row(Modifier.horizontalScroll(scroll)) { cells() }
    }
    if (!header) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun HeaderCell(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 2, modifier = Modifier.width(CELL_WIDTH).padding(end = 8.dp))
}

@Composable
private fun Cell(value: DisplayValue, unit: EngUnit, ctx: UnitContext) {
    Box(Modifier.width(CELL_WIDTH).padding(end = 8.dp), contentAlignment = Alignment.CenterEnd) {
        ValueText(value, unit, ctx = ctx)
    }
}
