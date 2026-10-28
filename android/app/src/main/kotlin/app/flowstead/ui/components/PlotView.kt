/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import app.flowstead.core.format.ValueFormat
import app.flowstead.core.model.Plot
import app.flowstead.core.model.Plots
import app.flowstead.core.units.EngUnit
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.UnitSet
import app.flowstead.ui.theme.valueColors

/** Several plots of one object behind chips (pipe: pressure, temperature, holdup, velocity…). */
@Composable
fun PlotsSection(plots: List<Plot>, unitSet: UnitSet, ctx: UnitContext, stale: Boolean, stateKey: String) {
    if (plots.isEmpty()) return
    var selected by rememberSaveable(stateKey) { mutableIntStateOf(0) }
    val plot = plots[selected.coerceIn(plots.indices)]
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (plots.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                plots.forEachIndexed { i, p -> FilterChip(selected = i == selected, onClick = { selected = i }, label = { Text(p.title) }) }
            }
        }
        PlotView(plot, unitSet, ctx, stale)
    }
}

/**
 * A line plot in the user's units with round-numbered axes. Touch or drag on it to read the nearest point of each
 * line; the values shown are the engine's points, never interpolated.
 */
@Composable
fun PlotView(plot: Plot, unitSet: UnitSet, ctx: UnitContext, stale: Boolean = false, modifier: Modifier = Modifier) {
    val xu = unitSet.unitFor(plot.xQuantity)
    val yu = unitSet.unitFor(plot.yQuantity)
    val pu = plot.parameterQuantity?.let { unitSet.unitFor(it) }
    // A family of curves is told apart by its parameter ("FEED pressure 40 bar"), else by the series' own names.
    fun name(s: app.flowstead.core.model.PlotSeries): String {
        val value = s.parameter ?: return s.label
        val unit = pu ?: return s.label
        return "${plot.parameterLabel ?: ""} ${ValueFormat.number(unit.fromSi(value, ctx), unit.decimals)} ${unit.symbol}".trim()
    }
    fun xs(v: Double) = xu.fromSi(v, ctx)
    fun ys(v: Double) = yu.fromSi(v, ctx)
    val allX = plot.series.flatMap { it.x }.map(::xs) + plot.markers.map { xs(it.x) }
    val allY = plot.series.flatMap { it.y }.map(::ys) + plot.markers.map { ys(it.y) }
    val xt = Plots.ticks(allX.min(), allX.max())
    val yt = Plots.ticks(allY.min(), allY.max())
    if (xt.size < 2 || yt.size < 2) return

    // Theme colours first, then fixed hues that read on paper and ink alike (envelopes carry up to eight lines).
    val colours = listOf(
        MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.secondary,
        Color(0xFF2E9E5B), Color(0xFF8E5BD6), Color(0xFF1F9BB0), Color(0xFFD08A1E), Color(0xFFC2457A),
    )
    val markerColour = MaterialTheme.colorScheme.error
    val axis = MaterialTheme.colorScheme.outline
    val grid = MaterialTheme.colorScheme.outlineVariant
    val label = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    var touch by remember(plot) { mutableStateOf<Offset?>(null) }
    var canvas by remember { mutableStateOf(IntSize.Zero) }
    val frame = with(density) { Frame(canvas.width.toFloat(), canvas.height.toFloat(), 52.dp.toPx(), 22.dp.toPx(), 8.dp.toPx(), xt, yt) }
    // The engine point of each line nearest the finger (by x), and where it is drawn.
    val picks = touch?.let { t ->
        plot.series.mapIndexedNotNull { i, s ->
            s.x.indices.minByOrNull { k -> abs(frame.px(xs(s.x[k])) - t.x) }?.let { k -> Triple(i, s, k) }
        }
    }.orEmpty()
    val readout = picks.takeIf { it.isNotEmpty() }?.joinToString(" · ") { (_, s, k) ->
        "${name(s)}: ${ValueFormat.number(ys(s.y[k]), yu.decimals)} ${yu.symbol} at ${ValueFormat.number(xs(s.x[k]), xu.decimals)} ${xu.symbol}".trimEnd()
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "${plot.yLabel}, ${yu.symbol} against ${plot.xLabel.replaceFirstChar(Char::lowercase)}" + if (xu.symbol.isNotBlank() && xu.symbol != "count") ", ${xu.symbol}" else "",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .onSizeChanged { canvas = it }
                .semantics { contentDescription = describe(plot, xu, yu, ctx) }
                .pointerInput(plot) {
                    detectTapGestures { touch = it }
                }
                .pointerInput(plot) {
                    detectDragGestures(onDragEnd = {}) { change, _ -> touch = change.position }
                }
                .testTag("plot"),
        ) {
            val (left, top, w, h) = listOf(frame.left, frame.top, frame.w, frame.h)
            fun px(v: Double) = frame.px(v)
            fun py(v: Double) = frame.py(v)

            for (t in yt) {
                drawLine(grid, Offset(left, py(t)), Offset(left + w, py(t)), 1f)
                val text = measurer.measure(ValueFormat.number(t, decimalsFor(yt)), label)
                drawText(text, topLeft = Offset(left - text.size.width - 4.dp.toPx(), py(t) - text.size.height / 2))
            }
            for (t in xt) {
                drawLine(grid, Offset(px(t), top), Offset(px(t), top + h), 1f)
                val text = measurer.measure(ValueFormat.number(t, decimalsFor(xt)), label)
                drawText(text, topLeft = Offset(px(t) - text.size.width / 2, top + h + 4.dp.toPx()))
            }
            drawLine(axis, Offset(left, top + h), Offset(left + w, top + h), 2f)
            drawLine(axis, Offset(left, top), Offset(left, top + h), 2f)

            plot.series.forEachIndexed { i, s ->
                val path = Path()
                s.x.indices.forEach { k ->
                    val o = Offset(px(xs(s.x[k])), py(ys(s.y[k])))
                    if (k == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                }
                drawPath(
                    path, colours[i % colours.size],
                    style = Stroke(width = 4f, pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(14f, 8f)) else null),
                )
            }
            for (m in plot.markers) {
                val o = Offset(px(xs(m.x)), py(ys(m.y)))
                drawCircle(markerColour, 7f, o)
                val text = measurer.measure(m.label, label)
                val x = (o.x + 8f).coerceAtMost(size.width - text.size.width)
                drawText(text, topLeft = Offset(x, o.y - text.size.height - 2f))
            }

            for ((i, s, k) in picks) drawCircle(colours[i % colours.size], 9f, Offset(px(xs(s.x[k])), py(ys(s.y[k]))))
            touch?.let { t -> drawLine(axis, Offset(t.x.coerceIn(left, left + w), top), Offset(t.x.coerceIn(left, left + w), top + h), 1.5f) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            plot.series.forEachIndexed { i, s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 14.dp, height = 3.dp).background(colours[i % colours.size]))
                    Spacer(Modifier.width(4.dp))
                    Text(name(s) + if (s.dashed) " (dashed)" else "", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (plot.markers.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(markerColour, CircleShape))
                    Spacer(Modifier.width(4.dp))
                    Text("points", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Text(
            readout ?: if (stale) "Out of date: solve again to redraw." else "Touch the plot to read values.",
            style = MaterialTheme.typography.bodySmall,
            color = if (stale && readout == null) MaterialTheme.valueColors.underSpecifiedText else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.heightIn(min = 20.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

private fun abs(v: Float) = kotlin.math.abs(v)

/** Where the plot area sits in the canvas, and value → pixel. */
private class Frame(width: Float, height: Float, val left: Float, bottom: Float, val top: Float, private val xt: List<Double>, private val yt: List<Double>) {
    val w = (width - left - top).coerceAtLeast(1f)
    val h = (height - bottom - top).coerceAtLeast(1f)
    fun px(v: Double) = (left + (v - xt.first()) / (xt.last() - xt.first()) * w).toFloat()
    fun py(v: Double) = (top + h - (v - yt.first()) / (yt.last() - yt.first()) * h).toFloat()
}

/** Enough decimals to tell neighbouring ticks apart. */
private fun decimalsFor(ticks: List<Double>): Int {
    val step = kotlin.math.abs(ticks[1] - ticks[0])
    return if (step >= 1) 0 else kotlin.math.ceil(-kotlin.math.log10(step)).toInt().coerceIn(0, 6)
}

private fun describe(plot: Plot, xu: EngUnit, yu: EngUnit, ctx: UnitContext): String = buildString {
    append("${plot.title}: ")
    append(plot.series.joinToString("; ") { s ->
        val y = s.y.map { yu.fromSi(it, ctx) }
        "${s.label} from ${ValueFormat.number(y.first(), yu.decimals)} to ${ValueFormat.number(y.last(), yu.decimals)} ${yu.symbol}"
    })
    plot.markers.forEach { m ->
        append("; ${m.label} at ${ValueFormat.number(xu.fromSi(m.x, ctx), xu.decimals)} ${xu.symbol}, ${ValueFormat.number(yu.fromSi(m.y, ctx), yu.decimals)} ${yu.symbol}")
    }
}
