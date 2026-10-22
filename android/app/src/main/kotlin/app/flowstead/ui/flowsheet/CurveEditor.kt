/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.flowsheet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.flowstead.core.format.ValueFormat
import app.flowstead.core.model.CurvePoint
import app.flowstead.core.model.DisplayValue
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.PerformanceCurve
import app.flowstead.core.model.ResultView
import app.flowstead.core.model.SimObject
import app.flowstead.core.model.Specification
import app.flowstead.core.units.EngUnit
import app.flowstead.core.units.Quantity
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.UnitSet
import app.flowstead.ui.theme.valueColors

/**
 * A pump or compressor running on its vendor curve (D48): a switch, the points (flow, head, efficiency and, for a
 * pump, NPSH required) in the user's units, and the curve drawn with the solved operating point on it.
 * With the curve on, the outlet pressure is the engine's answer, so the pressure specs are not shown.
 */
@Composable
internal fun CurveSection(obj: SimObject, results: ResultView, unitSet: UnitSet, ctx: UnitContext, actions: EditorActions) {
    val on = obj.curve != null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = on, role = Role.Switch) { actions.setCurve(obj.id, if (it) PerformanceCurve() else null) }
            .testTag("curve_toggle"),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Run on a performance curve", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (obj.type == ObjectType.Pump) "Head, efficiency and NPSH required against actual inlet flow; the outlet pressure follows."
                else "Adiabatic head and efficiency against actual inlet flow; the lowest flow is the surge limit.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = on, onCheckedChange = null)
    }
    val curve = obj.curve ?: return
    val flowUnit = unitSet.unitFor(Quantity.VolumeFlow)
    val headUnit = unitSet.unitFor(Quantity.Length)
    val pump = obj.type == ObjectType.Pump
    fun replace(i: Int, p: CurvePoint) = actions.setCurve(obj.id, curve.copy(points = curve.points.toMutableList().also { it[i] = p }))

    Specification.curveProblem(obj)?.let {
        Text(it, color = MaterialTheme.valueColors.underSpecifiedText, style = MaterialTheme.typography.bodySmall)
    }
    curve.points.forEachIndexed { i, p ->
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            CurveCell("Flow, ${flowUnit.symbol}", p.flow, flowUnit, ctx, "curve_flow_$i", Modifier.weight(1f)) { replace(i, p.copy(flow = it ?: 0.0)) }
            CurveCell("Head, ${headUnit.symbol}", p.head, headUnit, ctx, "curve_head_$i", Modifier.weight(1f)) { replace(i, p.copy(head = it ?: 0.0)) }
            CurveCell("Eff, %", p.efficiency?.times(100), null, ctx, "curve_eff_$i", Modifier.weight(0.8f)) { replace(i, p.copy(efficiency = it?.div(100))) }
            if (pump) CurveCell("NPSHr, ${headUnit.symbol}", p.npshr, headUnit, ctx, "curve_npshr_$i", Modifier.weight(0.9f)) { replace(i, p.copy(npshr = it)) }
            IconButton(onClick = { actions.setCurve(obj.id, curve.copy(points = curve.points.filterIndexed { j, _ -> j != i })) }) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove point ${i + 1}")
            }
        }
    }
    TextButton(
        onClick = {
            // A new point after the last one, so the list stays in flow order; the user types over it.
            val last = curve.points.maxByOrNull { it.flow }
            val next = last?.let { CurvePoint(it.flow * 1.2, it.head * 0.9, it.efficiency, it.npshr?.times(1.2)) } ?: CurvePoint(0.01, 50.0)
            actions.setCurve(obj.id, curve.copy(points = curve.points + next))
        },
        modifier = Modifier.heightIn(min = 48.dp).testTag("curve_add_point"),
    ) { Text("Add point") }

    if (curve.points.size >= 2 && Specification.curveProblem(obj) == null) {
        val q = (results.value(obj.id, "inletVolumeFlow") as? DisplayValue.Calculated)?.si
        val h = (results.value(obj.id, if (pump) "head" else "adiabaticHead") as? DisplayValue.Calculated)?.si
        CurveChart(curve, q, h, flowUnit, headUnit, ctx)
    }
}

/** One number in a curve row, in [unit] (null: shown as typed, e.g. a percentage); blank commits null. */
@Composable
private fun CurveCell(label: String, si: Double?, unit: EngUnit?, ctx: UnitContext, tag: String, modifier: Modifier, onCommit: (Double?) -> Unit) {
    val shown = si?.let { v -> unit?.let { ValueFormat.number(it.fromSi(v, ctx), it.decimals + 1).replace(",", "") } ?: ValueFormat.number(v, 1) } ?: ""
    var text by remember(tag, si, unit) { mutableStateOf(shown) }
    var error by remember(tag) { mutableStateOf(false) }
    fun commit() {
        val t = text.trim().replace(',', '.')
        val v = if (t.isEmpty()) null else t.toDoubleOrNull()?.takeIf { it.isFinite() }
        error = t.isNotEmpty() && v == null
        if (error || t == shown) return
        onCommit(v?.let { unit?.toSi(it, ctx) ?: it })
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label, maxLines = 1) },
        isError = error,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { commit() }),
        modifier = modifier.onFocusChanged { if (!it.isFocused) commit() }.testTag(tag),
    )
}

/** Head (and efficiency, dashed, on its own 0–100 % scale) against flow, with the solved operating point. */
@Composable
private fun CurveChart(curve: PerformanceCurve, flow: Double?, head: Double?, flowUnit: EngUnit, headUnit: EngUnit, ctx: UnitContext) {
    val pts = curve.points.sortedBy { it.flow }
    val maxQ = maxOf(pts.last().flow, flow ?: 0.0) * 1.08
    val maxH = maxOf(pts.maxOf { it.head }, head ?: 0.0) * 1.1
    val line = MaterialTheme.colorScheme.primary
    val eff = MaterialTheme.colorScheme.tertiary
    val axis = MaterialTheme.colorScheme.outline
    val point = MaterialTheme.valueColors.calculated
    val description = buildString {
        append("Curve from ${fmt(pts.first().flow, flowUnit, ctx)} to ${fmt(pts.last().flow, flowUnit, ctx)} ${flowUnit.symbol}")
        if (flow != null && head != null) append("; operating at ${fmt(flow, flowUnit, ctx)} ${flowUnit.symbol}, ${fmt(head, headUnit, ctx)} ${headUnit.symbol}")
    }
    Column {
        Canvas(Modifier.fillMaxWidth().height(180.dp).semantics { contentDescription = description }.testTag("curve_chart")) {
            fun x(q: Double) = (q / maxQ * size.width).toFloat()
            fun y(h: Double) = (size.height - h / maxH * size.height).toFloat()
            drawLine(axis, Offset(0f, size.height), Offset(size.width, size.height), 2f)
            drawLine(axis, Offset(0f, 0f), Offset(0f, size.height), 2f)
            val path = Path().apply { pts.forEachIndexed { i, p -> if (i == 0) moveTo(x(p.flow), y(p.head)) else lineTo(x(p.flow), y(p.head)) } }
            drawPath(path, line, style = Stroke(width = 5f))
            pts.forEach { drawCircle(line, 6f, Offset(x(it.flow), y(it.head))) }
            if (pts.all { it.efficiency != null }) {
                val e = Path().apply {
                    pts.forEachIndexed { i, p ->
                        val ey = (size.height - p.efficiency!! * size.height).toFloat()
                        if (i == 0) moveTo(x(p.flow), ey) else lineTo(x(p.flow), ey)
                    }
                }
                drawPath(e, eff, style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))))
            }
            if (flow != null && head != null) {
                drawCircle(point, 11f, Offset(x(flow), y(head)))
                drawLine(point, Offset(x(flow), size.height), Offset(x(flow), y(head)), 2f)
            }
        }
        Text(
            "Head (solid) and efficiency (dashed, 0–100 %) against flow, 0–${fmt(maxQ, flowUnit, ctx)} ${flowUnit.symbol}" +
                if (flow != null && head != null) "; the dot is where it runs." else "; solve to see where it runs.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun fmt(si: Double, unit: EngUnit, ctx: UnitContext) = ValueFormat.number(unit.fromSi(si, ctx), unit.decimals)
