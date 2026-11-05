/*
 * Flowstead web (D110): Compose's DrawScope as the app's canvas code calls it. The browser implementation is
 * app.flowstead.web.CanvasDrawScope, which draws on a 2D canvas.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package androidx.compose.ui.graphics.drawscope

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin

sealed class DrawStyle
data object Fill : DrawStyle()
class Stroke(
    val width: Float = 0f,
    val miter: Float = 4f,
    val cap: StrokeCap = StrokeCap.Butt,
    val join: StrokeJoin = StrokeJoin.Miter,
    val pathEffect: PathEffect? = null,
) : DrawStyle() {
    companion object {
        const val HairlineWidth = 0f
        val DefaultCap = StrokeCap.Butt
    }
}

interface DrawScope {
    val size: Size

    fun drawLine(color: Color, start: Offset, end: Offset, strokeWidth: Float = Stroke.HairlineWidth, cap: StrokeCap = Stroke.DefaultCap, pathEffect: PathEffect? = null, alpha: Float = 1f)
    fun drawPath(path: Path, color: Color, alpha: Float = 1f, style: DrawStyle = Fill)
    fun drawPath(path: Path, brush: Brush, alpha: Float = 1f, style: DrawStyle = Fill)
    fun drawRoundRect(color: Color, topLeft: Offset = Offset.Zero, size: Size = this.size, cornerRadius: CornerRadius = CornerRadius.Zero, style: DrawStyle = Fill, alpha: Float = 1f)
    fun drawRoundRect(brush: Brush, topLeft: Offset = Offset.Zero, size: Size = this.size, cornerRadius: CornerRadius = CornerRadius.Zero, alpha: Float = 1f, style: DrawStyle = Fill)
    fun drawRect(color: Color, topLeft: Offset = Offset.Zero, size: Size = this.size, alpha: Float = 1f, style: DrawStyle = Fill)
    fun drawCircle(color: Color, radius: Float = size.minDimension / 2f, center: Offset = size.center, alpha: Float = 1f, style: DrawStyle = Fill)
    fun drawCircle(brush: Brush, radius: Float = size.minDimension / 2f, center: Offset = size.center, alpha: Float = 1f, style: DrawStyle = Fill)
    fun drawArc(color: Color, startAngle: Float, sweepAngle: Float, useCenter: Boolean, topLeft: Offset = Offset.Zero, size: Size = this.size, alpha: Float = 1f, style: DrawStyle = Fill)

    /** Runs [block] with the origin moved by ([left], [top]) and, below, scaled by ([sx], [sy]) about [pivot]. */
    fun withTransform(left: Float, top: Float, sx: Float, sy: Float, pivot: Offset, block: DrawScope.() -> Unit)
}

inline fun DrawScope.translate(left: Float = 0f, top: Float = 0f, noinline block: DrawScope.() -> Unit) =
    withTransform(left, top, 1f, 1f, Offset.Zero, block)

inline fun DrawScope.scale(scaleX: Float, scaleY: Float, pivot: Offset = size.center, noinline block: DrawScope.() -> Unit) =
    withTransform(0f, 0f, scaleX, scaleY, pivot, block)

inline fun DrawScope.scale(scale: Float, pivot: Offset = size.center, noinline block: DrawScope.() -> Unit) =
    withTransform(0f, 0f, scale, scale, pivot, block)
