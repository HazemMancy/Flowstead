/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.flowsheet

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import app.flowstead.core.model.ObjectType

/** Colours an icon needs; resolved from the theme by the caller. */
data class IconPalette(
    val fill: Color,
    val stroke: Color,
    val accent: Color,
    val vapour: Color,
    val liquid: Color,
    val energy: Color,
)

/**
 * Process-flow-diagram symbols drawn in a box of [size] centred on [c] (world units).
 * Kept deliberately simple so they stay legible at phone sizes and low zoom.
 */
fun DrawScope.drawUnitIcon(type: ObjectType, c: Offset, size: Size, p: IconPalette, strokeWidth: Float, streamColor: Color = p.stroke) {
    val w = size.width
    val h = size.height
    val left = c.x - w / 2
    val top = c.y - h / 2
    val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)

    fun shape(path: Path) {
        drawPath(path, p.fill)
        drawPath(path, p.stroke, style = stroke)
    }

    when (type) {
        ObjectType.MaterialStream, ObjectType.EnergyStream -> {
            val energy = type == ObjectType.EnergyStream
            val color = if (energy) p.energy else streamColor
            val effect = if (energy) PathEffect.dashPathEffect(floatArrayOf(5f, 4f)) else null
            drawLine(color, Offset(left, c.y), Offset(left + w - h * 0.5f, c.y), strokeWidth * 1.6f, StrokeCap.Round, effect)
            val head = Path().apply {
                moveTo(left + w, c.y)
                lineTo(left + w - h * 0.6f, c.y - h * 0.4f)
                lineTo(left + w - h * 0.6f, c.y + h * 0.4f)
                close()
            }
            drawPath(head, color)
        }
        ObjectType.Valve -> {
            val bow = Path().apply {
                moveTo(left, c.y - h * 0.3f); lineTo(left, c.y + h * 0.3f); lineTo(c.x, c.y); close()
                moveTo(left + w, c.y - h * 0.3f); lineTo(left + w, c.y + h * 0.3f); lineTo(c.x, c.y); close()
            }
            shape(bow)
            drawLine(p.stroke, c, Offset(c.x, top + h * 0.12f), strokeWidth)
            drawRoundRect(p.stroke, Offset(c.x - w * 0.18f, top), Size(w * 0.36f, h * 0.12f), CornerRadius(2f), style = stroke)
        }
        ObjectType.Heater, ObjectType.Cooler, ObjectType.HeatExchanger -> {
            val r = w * 0.42f
            drawCircle(p.fill, r, c)
            drawCircle(p.stroke, r, c, style = stroke)
            val color = when (type) {
                ObjectType.Heater -> p.energy
                ObjectType.Cooler -> p.liquid
                else -> p.stroke
            }
            val zig = Path().apply {
                moveTo(c.x - r * 0.75f, c.y + r * 0.45f)
                lineTo(c.x - r * 0.25f, c.y - r * 0.35f)
                lineTo(c.x + r * 0.25f, c.y + r * 0.35f)
                lineTo(c.x + r * 0.75f, c.y - r * 0.45f)
            }
            drawPath(zig, color, style = Stroke(strokeWidth * 1.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        ObjectType.Separator -> {
            drawRoundRect(p.fill, Offset(left, top), size, CornerRadius(w / 2))
            drawRoundRect(p.stroke, Offset(left, top), size, CornerRadius(w / 2), style = stroke)
            drawLine(p.liquid, Offset(left + w * 0.12f, top + h * 0.68f), Offset(left + w * 0.88f, top + h * 0.68f), strokeWidth)
        }
        ObjectType.ThreePhaseSeparator -> {
            val body = Size(w, h * 0.75f)
            drawRoundRect(p.fill, Offset(left, top), body, CornerRadius(body.height / 2))
            drawRoundRect(p.stroke, Offset(left, top), body, CornerRadius(body.height / 2), style = stroke)
            // Water boot
            drawRoundRect(p.stroke, Offset(left + w * 0.62f, top + body.height - 2f), Size(w * 0.18f, h * 0.27f), CornerRadius(3f), style = stroke)
            drawLine(p.liquid, Offset(left + w * 0.1f, top + body.height * 0.62f), Offset(left + w * 0.9f, top + body.height * 0.62f), strokeWidth)
            drawLine(p.stroke, Offset(left + w * 0.55f, top + body.height * 0.35f), Offset(left + w * 0.55f, top + body.height), strokeWidth * 0.8f)
        }
        ObjectType.Mixer, ObjectType.Splitter -> {
            val tri = Path().apply {
                if (type == ObjectType.Mixer) {
                    moveTo(left, top + h * 0.15f); lineTo(left + w, c.y); lineTo(left, top + h * 0.85f)
                } else {
                    moveTo(left + w, top + h * 0.15f); lineTo(left, c.y); lineTo(left + w, top + h * 0.85f)
                }
                close()
            }
            shape(tri)
        }
        ObjectType.Pump -> {
            val r = w * 0.4f
            drawCircle(p.fill, r, c)
            drawCircle(p.stroke, r, c, style = stroke)
            val tri = Path().apply {
                moveTo(c.x - r * 0.5f, c.y - r * 0.6f); lineTo(c.x + r * 0.7f, c.y); lineTo(c.x - r * 0.5f, c.y + r * 0.6f); close()
            }
            drawPath(tri, p.stroke, style = stroke)
        }
        ObjectType.Compressor, ObjectType.Expander -> {
            val wide = h * 0.45f
            val narrow = h * 0.18f
            val (l, r) = if (type == ObjectType.Compressor) wide to narrow else narrow to wide
            shape(Path().apply {
                moveTo(left, c.y - l); lineTo(left + w, c.y - r); lineTo(left + w, c.y + r); lineTo(left, c.y + l); close()
            })
        }
        ObjectType.Recycle, ObjectType.Adjust -> {
            val path = Path().apply {
                if (type == ObjectType.Adjust) {
                    moveTo(c.x, top); lineTo(left + w, c.y); lineTo(c.x, top + h); lineTo(left, c.y); close()
                } else {
                    addOval(androidx.compose.ui.geometry.Rect(c, w * 0.42f))
                }
            }
            shape(path)
            drawCircle(p.accent, w * 0.08f, c)
        }
        ObjectType.DistillationColumn, ObjectType.Reactor, ObjectType.PipeSegment -> {
            drawRoundRect(p.fill, Offset(left, top), size, CornerRadius(8f))
            drawRoundRect(p.stroke, Offset(left, top), size, CornerRadius(8f), style = stroke)
        }
    }
}
