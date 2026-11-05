/*
 * Flowstead web (D110): Compose's DrawScope on a browser 2D canvas, so the app's symbol code draws the same symbols here.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.web

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradient
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RadialGradient
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.min

/** [ctx] is a CanvasRenderingContext2D; [size] is the drawing area in the units the caller draws in. */
class CanvasDrawScope(private val ctx: dynamic, override val size: Size) : DrawScope {

    private fun paint(color: Color, alpha: Float): String = if (alpha >= 1f) color.css() else color.copy(alpha = color.alpha * alpha).css()

    private fun paint(brush: Brush, alpha: Float): dynamic = when (brush) {
        is SolidColor -> paint(brush.value, alpha)
        is LinearGradient -> {
            // Compose's "infinite" end means the far edge of the drawing area.
            fun fin(v: Float, edge: Float) = if (v.isFinite()) v else edge
            val g = ctx.createLinearGradient(brush.start.x, brush.start.y, fin(brush.end.x, size.width), fin(brush.end.y, size.height))
            brush.stops.forEach { (at, c) -> g.addColorStop(at.coerceIn(0f, 1f), paint(c, alpha)) }
            g
        }
        is RadialGradient -> {
            val c = if (brush.center.x.isNaN()) size.center else brush.center
            val r = if (brush.radius.isFinite()) brush.radius else size.minDimension / 2
            val g = ctx.createRadialGradient(c.x, c.y, 0, c.x, c.y, r)
            brush.stops.forEach { (at, col) -> g.addColorStop(at.coerceIn(0f, 1f), paint(col, alpha)) }
            g
        }
    }

    private fun cap(c: StrokeCap) = when (c) { StrokeCap.Butt -> "butt"; StrokeCap.Round -> "round"; StrokeCap.Square -> "square" }
    private fun join(j: StrokeJoin) = when (j) { StrokeJoin.Miter -> "miter"; StrokeJoin.Round -> "round"; StrokeJoin.Bevel -> "bevel" }

    private fun dash(effect: PathEffect?) {
        ctx.setLineDash(effect?.intervals?.map { it.toDouble() }?.toTypedArray() ?: emptyArray<Double>())
        ctx.lineDashOffset = effect?.phase ?: 0f
    }

    /** Fills or strokes the path just traced with [style] in [style]'s paint. */
    private fun finish(style: DrawStyle, paint: dynamic) {
        when (style) {
            is Fill -> { ctx.fillStyle = paint; ctx.fill() }
            is Stroke -> {
                ctx.strokeStyle = paint
                // Compose's hairline (0) is one device pixel.
                ctx.lineWidth = if (style.width > 0f) style.width else 1.0 / currentScale()
                ctx.lineCap = cap(style.cap)
                ctx.lineJoin = join(style.join)
                ctx.miterLimit = style.miter
                dash(style.pathEffect)
                ctx.stroke()
                dash(null)
            }
        }
    }

    private fun currentScale(): Double {
        val m = ctx.getTransform()
        return (m.a as Double).let { if (it == 0.0) 1.0 else kotlin.math.abs(it) }
    }

    private fun trace(path: Path) {
        ctx.beginPath()
        for (op in path.ops) when (op) {
            is Path.MoveTo -> ctx.moveTo(op.x, op.y)
            is Path.LineTo -> ctx.lineTo(op.x, op.y)
            is Path.QuadTo -> ctx.quadraticCurveTo(op.x1, op.y1, op.x2, op.y2)
            is Path.Oval -> {
                val r = op.rect
                ctx.moveTo(r.right, r.center.y)
                ctx.ellipse(r.center.x, r.center.y, r.width / 2, r.height / 2, 0, 0, 2 * PI)
            }
            Path.Close -> ctx.closePath()
        }
    }

    private fun traceRoundRect(topLeft: Offset, size: Size, r: CornerRadius) {
        ctx.beginPath()
        val rad = min(min(r.x, size.width / 2), size.height / 2).coerceAtLeast(0f)
        val l = topLeft.x; val t = topLeft.y; val rt = l + size.width; val b = t + size.height
        ctx.moveTo(l + rad, t)
        ctx.lineTo(rt - rad, t); ctx.arcTo(rt, t, rt, t + rad, rad)
        ctx.lineTo(rt, b - rad); ctx.arcTo(rt, b, rt - rad, b, rad)
        ctx.lineTo(l + rad, b); ctx.arcTo(l, b, l, b - rad, rad)
        ctx.lineTo(l, t + rad); ctx.arcTo(l, t, l + rad, t, rad)
        ctx.closePath()
    }

    override fun drawLine(color: Color, start: Offset, end: Offset, strokeWidth: Float, cap: StrokeCap, pathEffect: PathEffect?, alpha: Float) {
        ctx.beginPath(); ctx.moveTo(start.x, start.y); ctx.lineTo(end.x, end.y)
        finish(Stroke(strokeWidth, cap = cap, pathEffect = pathEffect), paint(color, alpha))
    }

    override fun drawPath(path: Path, color: Color, alpha: Float, style: DrawStyle) { trace(path); finish(style, paint(color, alpha)) }
    override fun drawPath(path: Path, brush: Brush, alpha: Float, style: DrawStyle) { trace(path); finish(style, paint(brush, alpha)) }

    override fun drawRoundRect(color: Color, topLeft: Offset, size: Size, cornerRadius: CornerRadius, style: DrawStyle, alpha: Float) {
        traceRoundRect(topLeft, size, cornerRadius); finish(style, paint(color, alpha))
    }
    override fun drawRoundRect(brush: Brush, topLeft: Offset, size: Size, cornerRadius: CornerRadius, alpha: Float, style: DrawStyle) {
        traceRoundRect(topLeft, size, cornerRadius); finish(style, paint(brush, alpha))
    }
    override fun drawRect(color: Color, topLeft: Offset, size: Size, alpha: Float, style: DrawStyle) {
        ctx.beginPath(); ctx.rect(topLeft.x, topLeft.y, size.width, size.height); finish(style, paint(color, alpha))
    }

    private fun traceCircle(center: Offset, radius: Float) { ctx.beginPath(); ctx.arc(center.x, center.y, radius, 0, 2 * PI) }
    override fun drawCircle(color: Color, radius: Float, center: Offset, alpha: Float, style: DrawStyle) { traceCircle(center, radius); finish(style, paint(color, alpha)) }
    override fun drawCircle(brush: Brush, radius: Float, center: Offset, alpha: Float, style: DrawStyle) { traceCircle(center, radius); finish(style, paint(brush, alpha)) }

    override fun drawArc(color: Color, startAngle: Float, sweepAngle: Float, useCenter: Boolean, topLeft: Offset, size: Size, alpha: Float, style: DrawStyle) {
        val cx = topLeft.x + size.width / 2; val cy = topLeft.y + size.height / 2
        val a0 = startAngle * PI / 180; val a1 = (startAngle + sweepAngle) * PI / 180
        ctx.beginPath()
        if (useCenter) ctx.moveTo(cx, cy)
        ctx.ellipse(cx, cy, size.width / 2, size.height / 2, 0, a0, a1, sweepAngle < 0)
        if (useCenter) ctx.closePath()
        finish(style, paint(color, alpha))
    }

    override fun withTransform(left: Float, top: Float, sx: Float, sy: Float, pivot: Offset, block: DrawScope.() -> Unit) {
        ctx.save()
        ctx.translate(left + pivot.x, top + pivot.y)
        ctx.scale(sx, sy)
        ctx.translate(-pivot.x, -pivot.y)
        try { block() } finally { ctx.restore() }
    }
}
