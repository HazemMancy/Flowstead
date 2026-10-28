/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.flowsheet

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import app.flowstead.core.model.ObjectType

/**
 * Colours an icon needs; resolved from the theme by the caller. [shaded] draws the bodies in 3D (lit steel with a
 * soft shadow); off, they are flat, as on the printed PFD.
 */
data class IconPalette(
    val fill: Color,
    val stroke: Color,
    val accent: Color,
    val vapour: Color,
    val liquid: Color,
    val energy: Color,
    val shaded: Boolean = true,
)

/** How a body is lit: a vertical or horizontal cylinder, a round casing, or a flat plate. */
private enum class Shade { Upright, Lying, Round, Plate }

/**
 * Process-flow-diagram symbols in the style engineers know from HYSYS and ISO 10628: vessels with
 * heads and internals, columns with trays, condenser and reboiler, rotating machines with their
 * casings, valves with actuators. Drawn in a box of [size] centred on [c] (world units), the same
 * box [CanvasGeometry.size] gives the hit area and ports, so canvas and palette always agree.
 */
fun DrawScope.drawUnitIcon(type: ObjectType, c: Offset, size: Size, p: IconPalette, strokeWidth: Float, streamColor: Color = p.stroke) {
    UnitIconPainter(this, c, size, p, strokeWidth).draw(type, streamColor)
}

private class UnitIconPainter(
    val d: DrawScope,
    val c: Offset,
    val size: Size,
    val p: IconPalette,
    val sw: Float,
) {
    val w = size.width
    val h = size.height
    val left = c.x - w / 2
    val top = c.y - h / 2
    val right = left + w
    val bottom = top + h
    val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val thin = Stroke(width = sw * 0.7f, cap = StrokeCap.Round, join = StrokeJoin.Round)

    /** Point at fractions of the box: (0,0) top-left, (1,1) bottom-right. */
    fun at(fx: Float, fy: Float) = Offset(left + fx * w, top + fy * h)

    // ---- 3D: equipment is drawn as lit steel, light from the upper left, with a soft shadow below right.

    /** Steel body colour, a little cooler than the surface it sits on. */
    val steel = lerp(p.fill, Color(0xFF9AA9BA), 0.32f)
    val edge = lerp(p.stroke, steel, 0.25f)
    val shadow = Color.Black.copy(alpha = 0.16f)
    val drop = Offset(sw * 0.9f, sw * 1.5f)

    /** The brush for a body in [r], lit for its [shade]; a plain colour when the palette is flat. */
    fun brush(r: Rect, shade: Shade, base: Color = steel): Brush {
        if (!p.shaded) return SolidColor(p.fill)
        val hi = lerp(base, Color.White, 0.75f)
        val lo = lerp(base, Color.Black, 0.3f)
        val bands = arrayOf(0f to lerp(base, lo, 0.6f), 0.22f to base, 0.38f to hi, 0.62f to base, 1f to lo)
        return when (shade) {
            Shade.Upright -> Brush.horizontalGradient(*bands, startX = r.left, endX = r.right)
            Shade.Lying -> Brush.verticalGradient(*bands, startY = r.top, endY = r.bottom)
            Shade.Round -> Brush.radialGradient(
                listOf(hi, base, lo), center = Offset(r.left + r.width * 0.36f, r.top + r.height * 0.32f), radius = maxOf(r.width, r.height) * 0.8f,
            )
            Shade.Plate -> Brush.linearGradient(listOf(hi, base, lo), start = r.topLeft, end = r.bottomRight)
        }
    }

    fun outline(): Color = if (p.shaded) edge else p.stroke

    /** A body: its shadow, the lit fill, the outline. A colour other than the surface is drawn flat (marks, dots). */
    fun shape(path: Path, fill: Color = p.fill, shade: Shade = Shade.Plate) {
        if (fill != p.fill) {
            d.drawPath(path, fill)
            d.drawPath(path, p.stroke, style = stroke)
            return
        }
        if (p.shaded) d.translate(drop.x, drop.y) { drawPath(path, shadow) }
        d.drawPath(path, brush(path.getBounds(), shade))
        d.drawPath(path, outline(), style = stroke)
    }

    fun line(a: Offset, b: Offset, color: Color = p.stroke, width: Float = sw, dashed: Boolean = false) =
        d.drawLine(color, a, b, width, StrokeCap.Round, if (dashed) PathEffect.dashPathEffect(floatArrayOf(sw * 2.5f, sw * 2f)) else null)

    fun roundRect(r: Rect, radius: Float, fill: Color = p.fill, shade: Shade = if (r.height > r.width) Shade.Upright else Shade.Lying) {
        if (fill != p.fill) {
            d.drawRoundRect(fill, r.topLeft, r.size, CornerRadius(radius))
            d.drawRoundRect(p.stroke, r.topLeft, r.size, CornerRadius(radius), style = stroke)
            return
        }
        if (p.shaded) d.drawRoundRect(shadow, r.topLeft + drop, r.size, CornerRadius(radius))
        d.drawRoundRect(brush(r, shade), r.topLeft, r.size, CornerRadius(radius))
        d.drawRoundRect(outline(), r.topLeft, r.size, CornerRadius(radius), style = stroke)
    }

    fun circle(center: Offset, r: Float, fill: Color = p.fill) {
        if (fill != p.fill) {
            d.drawCircle(fill, r, center)
            d.drawCircle(p.stroke, r, center, style = stroke)
            return
        }
        if (p.shaded) d.drawCircle(shadow, r, center + drop)
        d.drawCircle(brush(Rect(center, r), Shade.Round), r, center)
        d.drawCircle(outline(), r, center, style = stroke)
    }

    /** Liquid in a vessel below [level], shaded like the vessel. */
    fun liquidPool(r: Rect, radius: Float, shade: Shade) {
        if (!p.shaded) {
            d.drawRoundRect(p.liquid.copy(alpha = 0.22f), r.topLeft, r.size, CornerRadius(radius))
            return
        }
        val base = lerp(steel, p.liquid, 0.45f)
        val b = when (shade) {
            Shade.Lying -> Brush.verticalGradient(listOf(lerp(base, Color.White, 0.25f), base, lerp(base, Color.Black, 0.25f)), startY = r.top, endY = r.bottom)
            else -> Brush.horizontalGradient(listOf(lerp(base, Color.Black, 0.15f), lerp(base, Color.White, 0.35f), base, lerp(base, Color.Black, 0.28f)), startX = r.left, endX = r.right)
        }
        d.drawRoundRect(b, r.topLeft, r.size, CornerRadius(radius))
    }

    fun arrowHead(tip: Offset, dir: Offset, len: Float, color: Color, solid: Boolean = false) {
        val n = Offset(-dir.y, dir.x)
        val path = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(tip.x - dir.x * len + n.x * len * 0.55f, tip.y - dir.y * len + n.y * len * 0.55f)
            lineTo(tip.x - dir.x * len - n.x * len * 0.55f, tip.y - dir.y * len - n.y * len * 0.55f)
            close()
        }
        if (!solid || !p.shaded) {
            d.drawPath(path, color)
            return
        }
        // A stream's arrowhead: lit from above, with a shadow, like the pipe it ends.
        d.translate(drop.x * 0.6f, drop.y * 0.6f) { drawPath(path, shadow) }
        val r = path.getBounds()
        d.drawPath(path, Brush.verticalGradient(listOf(lerp(color, Color.White, 0.45f), color, lerp(color, Color.Black, 0.3f)), startY = r.top, endY = r.bottom))
    }

    fun draw(type: ObjectType, streamColor: Color) {
        when (type) {
            ObjectType.MaterialStream -> streamArrow(streamColor, dashed = false)
            ObjectType.EnergyStream -> streamArrow(p.energy, dashed = true)

            ObjectType.Heater -> heaterCooler(heat = true)
            ObjectType.Cooler -> heaterCooler(heat = false)
            ObjectType.HeatExchanger -> shellAndTube()
            ObjectType.AirCooler -> airCooler()

            ObjectType.Pump -> pump()
            ObjectType.Compressor -> machine(compress = true)
            ObjectType.Expander -> machine(compress = false)

            ObjectType.Valve -> controlValve()
            ObjectType.PipeSegment -> pipe()
            ObjectType.OrificePlate -> orifice()
            ObjectType.ReliefValve -> reliefValve()

            ObjectType.Mixer -> tee(mix = true)
            ObjectType.Splitter -> tee(mix = false)
            ObjectType.ComponentSplitter -> componentSplitter()

            ObjectType.Separator -> verticalSeparator()
            ObjectType.ThreePhaseSeparator -> horizontalThreePhase()
            ObjectType.Tank -> tank()
            ObjectType.Filter -> filter()
            ObjectType.SolidsSeparator -> cyclone()

            ObjectType.ShortcutColumn -> column(condenser = true, reboiler = true, trays = 4, shortcut = true)
            ObjectType.DistillationColumn -> column(condenser = true, reboiler = true, trays = 7)
            ObjectType.Absorber -> column(condenser = false, reboiler = false, trays = 7)
            ObjectType.RefluxedAbsorber -> column(condenser = true, reboiler = false, trays = 7)
            ObjectType.ReboiledAbsorber -> column(condenser = false, reboiler = true, trays = 7)

            ObjectType.ConversionReactor -> reactorVessel { conversionMark() }
            ObjectType.EquilibriumReactor -> reactorVessel { equilibriumMark() }
            ObjectType.GibbsReactor -> reactorVessel { gibbsMark() }
            ObjectType.Cstr -> cstr()
            ObjectType.Pfr -> pfr()

            ObjectType.Recycle -> logical(round = true) { recycleMark(p.accent) }
            ObjectType.EnergyRecycle -> logical(round = true, dashed = true) { recycleMark(p.energy) }
            ObjectType.Adjust -> logical(round = false) { adjustMark() }
            ObjectType.Set -> logical(round = false) { setMark() }
        }
    }

    // ---- streams

    /** A material stream is a short lit pipe ending in an arrowhead; an energy stream stays a dashed line. */
    fun streamArrow(color: Color, dashed: Boolean) {
        val end = right - h * 0.5f
        if (dashed || !p.shaded) {
            line(Offset(left, c.y), Offset(end, c.y), color, sw * 1.6f, dashed)
            arrowHead(Offset(right, c.y), Offset(1f, 0f), h * 0.6f, color)
            return
        }
        val t = h * 0.3f
        val pipe = Rect(Offset(left, c.y - t / 2), Size(end - left + sw, t))
        d.drawRoundRect(shadow, pipe.topLeft + drop * 0.6f, pipe.size, CornerRadius(t / 2))
        d.drawRoundRect(
            Brush.verticalGradient(listOf(lerp(color, Color.White, 0.5f), color, lerp(color, Color.Black, 0.3f)), startY = pipe.top, endY = pipe.bottom),
            pipe.topLeft, pipe.size, CornerRadius(t / 2),
        )
        arrowHead(Offset(right, c.y), Offset(1f, 0f), h * 0.6f, color, solid = true)
    }

    // ---- heat transfer

    /** Exchanger circle with a coil; an energy arrow enters (heater) or leaves (cooler) below. */
    fun heaterCooler(heat: Boolean) {
        val r = minOf(w, h) * 0.36f
        val o = Offset(c.x, c.y - h * 0.06f)
        circle(o, r)
        val coil = Path().apply {
            moveTo(o.x - r, o.y)
            lineTo(o.x - r * 0.55f, o.y)
            lineTo(o.x - r * 0.3f, o.y - r * 0.5f)
            lineTo(o.x, o.y + r * 0.5f)
            lineTo(o.x + r * 0.3f, o.y - r * 0.5f)
            lineTo(o.x + r * 0.55f, o.y)
            lineTo(o.x + r, o.y)
        }
        d.drawPath(coil, if (heat) p.energy else p.liquid, style = Stroke(sw * 1.3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        val y0 = o.y + r
        val y1 = bottom
        line(Offset(c.x, y0), Offset(c.x, y1), p.energy, sw, dashed = true)
        if (heat) arrowHead(Offset(c.x, y0), Offset(0f, -1f), h * 0.12f, p.energy)
        else arrowHead(Offset(c.x, y1), Offset(0f, 1f), h * 0.12f, p.energy)
    }

    /** Horizontal shell with dished ends, a U-tube bundle and four nozzles. */
    fun shellAndTube() {
        val shell = Rect(at(0.08f, 0.25f), at(0.92f, 0.75f))
        roundRect(shell, shell.height / 2)
        val tubes = Path().apply {
            moveTo(shell.left + w * 0.04f, c.y - h * 0.08f)
            lineTo(shell.right - w * 0.14f, c.y - h * 0.08f)
            quadraticTo(shell.right - w * 0.02f, c.y, shell.right - w * 0.14f, c.y + h * 0.08f)
            lineTo(shell.left + w * 0.04f, c.y + h * 0.08f)
        }
        d.drawPath(tubes, p.liquid, style = Stroke(sw * 1.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        line(Offset(shell.left + w * 0.26f, shell.top), Offset(shell.left + w * 0.26f, top + h * 0.1f))
        line(Offset(shell.right - w * 0.26f, shell.bottom), Offset(shell.right - w * 0.26f, bottom - h * 0.1f))
    }

    /** Tube bundle under a fan with its motor. */
    fun airCooler() {
        val bundle = Rect(at(0.06f, 0.55f), at(0.94f, 0.85f))
        roundRect(bundle, 3f)
        val zig = Path().apply {
            moveTo(bundle.left + 4f, bundle.center.y)
            var x = bundle.left + 4f
            var up = true
            val step = (bundle.width - 8f) / 8f
            while (x < bundle.right - 5f) {
                x += step
                lineTo(x, bundle.center.y + if (up) -bundle.height * 0.28f else bundle.height * 0.28f)
                up = !up
            }
        }
        d.drawPath(zig, p.liquid, style = thin)
        val fan = at(0.5f, 0.32f)
        shape(Path().apply { addOval(Rect(Offset(fan.x - w * 0.34f, fan.y - h * 0.1f), Size(w * 0.68f, h * 0.2f))) }, shade = Shade.Lying)
        line(Offset(fan.x - w * 0.28f, fan.y), Offset(fan.x + w * 0.28f, fan.y), p.accent)
        line(fan, Offset(fan.x, top + h * 0.06f))
        roundRect(Rect(Offset(fan.x - w * 0.07f, top), Size(w * 0.14f, h * 0.1f)), 2f)
        line(Offset(fan.x, fan.y + h * 0.1f), Offset(fan.x, bundle.top))
    }

    // ---- rotating

    /** Centrifugal pump: volute with a tangential discharge, on its base. */
    fun pump() {
        val r = minOf(w, h) * 0.32f
        val o = Offset(c.x - w * 0.04f, c.y)
        val nozzle = Path().apply {
            moveTo(o.x, o.y - r)
            lineTo(right, o.y - r)
            lineTo(right, o.y - r * 0.45f)
            lineTo(o.x + r * 0.8f, o.y - r * 0.45f)
            close()
        }
        shape(nozzle)
        circle(o, r)
        val base = Path().apply {
            moveTo(o.x - r * 0.75f, o.y + r * 0.65f)
            lineTo(o.x + r * 0.75f, o.y + r * 0.65f)
            lineTo(o.x + r * 1.05f, bottom)
            lineTo(o.x - r * 1.05f, bottom)
            close()
        }
        shape(base)
        circle(o, r * 0.28f, p.stroke)
    }

    /** Compressor: casing narrowing in the flow direction, with its drive shaft. Expander mirrors it. */
    fun machine(compress: Boolean) {
        val wide = h * 0.42f
        val narrow = h * 0.2f
        val (l, r) = if (compress) wide to narrow else narrow to wide
        val body = Path().apply {
            moveTo(left + w * 0.06f, c.y - l)
            lineTo(right - w * 0.06f, c.y - r)
            lineTo(right - w * 0.06f, c.y + r)
            lineTo(left + w * 0.06f, c.y + l)
            close()
        }
        shape(body, shade = Shade.Lying)
        line(Offset(c.x, c.y + minOf(l, r)), Offset(c.x, bottom), p.stroke, sw * 1.4f)
        circle(Offset(c.x, bottom - h * 0.02f), h * 0.05f, p.stroke)
    }

    // ---- piping

    /** Globe control valve: bow-tie body, stem and diaphragm actuator. */
    fun controlValve() {
        val y = c.y + h * 0.15f
        val bow = Path().apply {
            moveTo(left, y - h * 0.24f); lineTo(left, y + h * 0.24f); lineTo(c.x, y); close()
            moveTo(right, y - h * 0.24f); lineTo(right, y + h * 0.24f); lineTo(c.x, y); close()
        }
        shape(bow)
        line(Offset(c.x, y), Offset(c.x, top + h * 0.22f))
        val dome = Path().apply {
            moveTo(c.x - w * 0.24f, top + h * 0.22f)
            quadraticTo(c.x, top - h * 0.12f, c.x + w * 0.24f, top + h * 0.22f)
            close()
        }
        shape(dome)
    }

    fun pipe() {
        val body = Rect(at(0.06f, 0.32f), at(0.94f, 0.68f))
        roundRect(body, 2f)
        for (fx in listOf(0.06f, 0.94f)) line(at(fx, 0.18f), at(fx, 0.82f), p.stroke, sw * 1.6f)
        line(at(0.2f, 0.5f), at(0.8f, 0.5f), p.stroke, sw * 0.6f, dashed = true)
    }

    fun orifice() {
        roundRect(Rect(at(0f, 0.32f), at(0.42f, 0.68f)), 2f)
        roundRect(Rect(at(0.58f, 0.32f), at(1f, 0.68f)), 2f)
        line(at(0.5f, 0.1f), at(0.5f, 0.4f), p.stroke, sw * 2f)
        line(at(0.5f, 0.6f), at(0.5f, 0.9f), p.stroke, sw * 2f)
    }

    /** Angle-body relief valve with its spring bonnet: inlet from below, discharge to the side. */
    fun reliefValve() {
        val o = at(0.42f, 0.6f)
        val down = Path().apply { moveTo(o.x, o.y); lineTo(o.x - w * 0.22f, bottom); lineTo(o.x + w * 0.22f, bottom); close() }
        val side = Path().apply { moveTo(o.x, o.y); lineTo(right, o.y - h * 0.16f); lineTo(right, o.y + h * 0.16f); close() }
        shape(down)
        shape(side)
        val spring = Path().apply {
            moveTo(o.x, o.y)
            var y = o.y
            var dx = w * 0.12f
            while (y > top + h * 0.12f) {
                y -= h * 0.07f
                lineTo(o.x + dx, y)
                dx = -dx
            }
            lineTo(o.x, top)
        }
        d.drawPath(spring, p.stroke, style = thin)
        line(Offset(o.x - w * 0.14f, top), Offset(o.x + w * 0.14f, top), p.stroke, sw * 1.4f)
    }

    // ---- mixing

    /** Mixer: inlets converge to one outlet; tee: one inlet fans out. */
    fun tee(mix: Boolean) {
        val body = Path().apply {
            if (mix) {
                moveTo(left + w * 0.1f, top + h * 0.12f); lineTo(right - w * 0.1f, c.y); lineTo(left + w * 0.1f, bottom - h * 0.12f)
            } else {
                moveTo(right - w * 0.1f, top + h * 0.12f); lineTo(left + w * 0.1f, c.y); lineTo(right - w * 0.1f, bottom - h * 0.12f)
            }
            close()
        }
        shape(body)
        val n = if (mix) listOf(0.3f, 0.5f, 0.7f) else listOf(0.3f, 0.5f, 0.7f)
        for (fy in n) {
            if (mix) line(at(0f, fy), at(0.1f, fy), p.stroke, sw * 0.8f) else line(at(0.9f, fy), at(1f, fy), p.stroke, sw * 0.8f)
        }
    }

    /** Box split by a membrane: components leave by separate routes. */
    fun componentSplitter() {
        val box = Rect(at(0.1f, 0.12f), at(0.9f, 0.88f))
        roundRect(box, 4f)
        line(Offset(box.left, box.bottom), Offset(box.right, box.top), p.accent, sw, dashed = true)
        circle(at(0.32f, 0.36f), w * 0.06f, p.vapour)
        circle(at(0.68f, 0.66f), w * 0.06f, p.liquid)
    }

    // ---- separators

    /** Vertical separator: elliptical heads, mist eliminator at the top, liquid level below. */
    fun verticalSeparator() {
        val body = Rect(at(0.08f, 0f), at(0.92f, 1f))
        roundRect(body, body.width * 0.42f)
        val level = top + h * 0.66f
        liquidPool(Rect(Offset(body.left + sw, level), Size(body.width - 2 * sw, body.bottom - level - sw)), body.width * 0.4f, Shade.Upright)
        line(Offset(body.left + w * 0.06f, level), Offset(body.right - w * 0.06f, level), p.liquid, sw)
        val mesh = top + h * 0.2f
        line(Offset(body.left + w * 0.1f, mesh), Offset(body.right - w * 0.1f, mesh), p.stroke, sw * 0.7f, dashed = true)
        line(Offset(body.left + w * 0.1f, mesh + h * 0.05f), Offset(body.right - w * 0.1f, mesh + h * 0.05f), p.stroke, sw * 0.7f, dashed = true)
    }

    /** Horizontal three-phase separator: weir, oil and water levels, water boot. */
    fun horizontalThreePhase() {
        val body = Rect(at(0f, 0f), at(1f, 0.74f))
        val boot = Rect(at(0.6f, 0.66f), at(0.8f, 1f))
        roundRect(boot, 3f)
        roundRect(body, body.height / 2)
        val level = top + body.height * 0.58f
        liquidPool(Rect(Offset(body.left + sw, level), Size(body.width - 2 * sw, body.bottom - level - sw)), body.height * 0.4f, Shade.Lying)
        line(Offset(left + w * 0.08f, level), Offset(left + w * 0.62f, level), p.liquid)
        line(Offset(left + w * 0.66f, level + body.height * 0.08f), Offset(right - w * 0.08f, level + body.height * 0.08f), p.vapour, sw * 0.9f)
        line(Offset(left + w * 0.62f, top + body.height * 0.4f), Offset(left + w * 0.62f, body.bottom), p.stroke, sw * 0.9f)
        line(Offset(boot.left + 3f, boot.center.y + 2f), Offset(boot.right - 3f, boot.center.y + 2f), p.liquid, sw * 0.8f)
    }

    /** Atmospheric storage tank with a cone roof. */
    fun tank() {
        val wall = Rect(at(0.08f, 0.24f), at(0.92f, 1f))
        roundRect(wall, 2f)
        shape(Path().apply { moveTo(wall.left, wall.top); lineTo(c.x, top); lineTo(wall.right, wall.top); close() })
        val level = top + h * 0.6f
        liquidPool(Rect(Offset(wall.left + sw, level), Size(wall.width - 2 * sw, wall.bottom - level - sw)), 1f, Shade.Upright)
        line(Offset(wall.left + 3f, level), Offset(wall.right - 3f, level), p.liquid)
    }

    /** Filter housing with diagonal filter media. */
    fun filter() {
        val box = Rect(at(0.08f, 0.14f), at(0.92f, 0.86f))
        roundRect(box, 4f)
        line(Offset(box.left, box.bottom), Offset(box.right, box.top))
        var f = 0.15f
        while (f < 0.9f) {
            line(
                Offset(box.left + box.width * f, box.bottom - box.height * f),
                Offset(box.left + box.width * (f + 0.1f), box.bottom - box.height * f),
                p.stroke, sw * 0.6f,
            )
            f += 0.15f
        }
    }

    /** Cyclone: barrel, cone and the vortex finder on top. */
    fun cyclone() {
        val barrel = Rect(at(0.1f, 0.14f), at(0.9f, 0.48f))
        shape(Path().apply {
            moveTo(barrel.left, barrel.top); lineTo(barrel.right, barrel.top); lineTo(barrel.right, barrel.bottom)
            lineTo(c.x + w * 0.08f, bottom - h * 0.04f); lineTo(c.x - w * 0.08f, bottom - h * 0.04f); lineTo(barrel.left, barrel.bottom); close()
        })
        roundRect(Rect(Offset(c.x - w * 0.14f, top), Size(w * 0.28f, h * 0.3f)), 2f)
        val spiral = Path().apply {
            moveTo(c.x - w * 0.3f, barrel.bottom + h * 0.02f)
            quadraticTo(c.x, barrel.bottom + h * 0.1f, c.x + w * 0.2f, barrel.bottom + h * 0.08f)
            quadraticTo(c.x, barrel.bottom + h * 0.2f, c.x - w * 0.1f, barrel.bottom + h * 0.22f)
        }
        d.drawPath(spiral, p.accent, style = thin)
    }

    // ---- columns

    /**
     * Trayed column; a distillation column carries an overhead condenser and a kettle reboiler,
     * absorbers only what their name says. The shortcut column shows fewer, dashed trays.
     */
    fun column(condenser: Boolean, reboiler: Boolean, trays: Int, shortcut: Boolean = false) {
        val shellW = w * 0.46f
        val shell = Rect(Offset(left + w * 0.04f, top + h * 0.08f), Size(shellW, h * 0.84f))
        roundRect(shell, shellW / 2)
        val span = shell.height - shellW
        for (i in 1..trays) {
            val y = shell.top + shellW / 2 + span * i / (trays + 1)
            val fromLeft = i % 2 == 0
            val x0 = if (fromLeft) shell.left else shell.left + shellW * 0.3f
            val x1 = if (fromLeft) shell.right - shellW * 0.3f else shell.right
            line(Offset(x0, y), Offset(x1, y), p.stroke, sw * 0.8f, dashed = shortcut)
        }
        val r = w * 0.13f
        val x = right - r - sw
        if (condenser) {
            // Overhead vapour to the condenser, reflux back to the top tray.
            val o = Offset(x, top + h * 0.1f)
            line(Offset(shell.center.x, shell.top), Offset(shell.center.x, top + sw), p.vapour, sw * 0.9f)
            line(Offset(shell.center.x, top + sw), Offset(o.x - r * 0.7f, top + sw), p.vapour, sw * 0.9f)
            circle(o, r)
            line(Offset(o.x - r * 0.6f, o.y + r * 0.3f), Offset(o.x + r * 0.6f, o.y - r * 0.3f), p.liquid, sw * 0.9f)
            line(Offset(o.x, o.y + r), Offset(o.x, shell.top + shellW * 0.75f), p.liquid, sw * 0.9f)
            line(Offset(o.x, shell.top + shellW * 0.75f), Offset(shell.right, shell.top + shellW * 0.75f), p.liquid, sw * 0.9f)
        }
        if (reboiler) {
            // Bottoms liquid to the kettle, vapour back under the bottom tray.
            val o = Offset(x, bottom - h * 0.1f)
            line(Offset(shell.center.x, shell.bottom), Offset(shell.center.x, bottom - sw), p.liquid, sw * 0.9f)
            line(Offset(shell.center.x, bottom - sw), Offset(o.x - r * 0.7f, bottom - sw), p.liquid, sw * 0.9f)
            circle(o, r)
            line(Offset(o.x - r * 0.6f, o.y - r * 0.3f), Offset(o.x + r * 0.6f, o.y + r * 0.3f), p.energy, sw * 0.9f)
            line(Offset(o.x, o.y - r), Offset(o.x, shell.bottom - shellW * 0.75f), p.vapour, sw * 0.9f)
            line(Offset(o.x, shell.bottom - shellW * 0.75f), Offset(shell.right, shell.bottom - shellW * 0.75f), p.vapour, sw * 0.9f)
        }
    }

    // ---- reactors

    fun reactorVessel(mark: () -> Unit) {
        val body = Rect(at(0.14f, 0f), at(0.86f, 1f))
        roundRect(body, body.width * 0.36f)
        mark()
    }

    /** One-way arrow: specified conversion. */
    fun conversionMark() {
        line(at(0.32f, 0.5f), at(0.64f, 0.5f), p.accent, sw * 1.2f)
        arrowHead(at(0.7f, 0.5f), Offset(1f, 0f), w * 0.12f, p.accent)
    }

    /** Equilibrium harpoons. */
    fun equilibriumMark() {
        line(at(0.3f, 0.45f), at(0.7f, 0.45f), p.accent, sw)
        line(at(0.7f, 0.45f), at(0.6f, 0.38f), p.accent, sw)
        line(at(0.3f, 0.55f), at(0.7f, 0.55f), p.accent, sw)
        line(at(0.3f, 0.55f), at(0.4f, 0.62f), p.accent, sw)
    }

    /** A minimum: the Gibbs-energy curve with the equilibrium point. */
    fun gibbsMark() {
        val curve = Path().apply {
            moveTo(at(0.28f, 0.34f).x, at(0.28f, 0.34f).y)
            quadraticTo(at(0.5f, 0.86f).x, at(0.5f, 0.86f).y, at(0.72f, 0.34f).x, at(0.72f, 0.34f).y)
        }
        d.drawPath(curve, p.accent, style = Stroke(sw * 1.1f, cap = StrokeCap.Round))
        d.drawCircle(p.accent, w * 0.045f, at(0.5f, 0.6f))
    }

    /** Stirred tank: vessel, shaft, impeller and motor. */
    fun cstr() {
        val body = Rect(at(0.1f, 0.16f), at(0.9f, 1f))
        roundRect(body, body.width * 0.3f)
        val level = top + h * 0.42f
        line(Offset(body.left + w * 0.08f, level), Offset(body.right - w * 0.08f, level), p.liquid)
        line(Offset(c.x, top + h * 0.1f), Offset(c.x, bottom - h * 0.22f), p.stroke, sw * 1.2f)
        line(Offset(c.x - w * 0.2f, bottom - h * 0.22f), Offset(c.x + w * 0.2f, bottom - h * 0.22f), p.accent, sw * 1.6f)
        roundRect(Rect(Offset(c.x - w * 0.12f, top), Size(w * 0.24f, h * 0.12f)), 2f)
    }

    /** Plug-flow reactor: a long tube with catalyst packing. */
    fun pfr() {
        val tube = Rect(at(0.02f, 0.26f), at(0.98f, 0.74f))
        roundRect(tube, tube.height / 2)
        var f = 0.16f
        while (f < 0.86f) {
            d.drawCircle(p.accent, sw * 0.9f, at(f, 0.42f))
            d.drawCircle(p.accent, sw * 0.9f, at(f + 0.06f, 0.58f))
            f += 0.12f
        }
    }

    // ---- logical

    fun logical(round: Boolean, dashed: Boolean = false, mark: () -> Unit) {
        val path = Path().apply {
            if (round) addOval(Rect(c, minOf(w, h) * 0.44f))
            else { moveTo(c.x, top + h * 0.04f); lineTo(right - w * 0.04f, c.y); lineTo(c.x, bottom - h * 0.04f); lineTo(left + w * 0.04f, c.y); close() }
        }
        // Logic blocks are tinted with the accent, so they read as controls, not equipment.
        if (p.shaded) {
            d.translate(drop.x, drop.y) { drawPath(path, shadow) }
            d.drawPath(path, brush(path.getBounds(), if (round) Shade.Round else Shade.Plate, lerp(p.fill, p.accent, 0.18f)))
        } else d.drawPath(path, p.fill)
        d.drawPath(path, outline(), style = if (dashed) Stroke(sw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(sw * 2.5f, sw * 2f))) else stroke)
        mark()
    }

    /** Two chasing arrows. */
    fun recycleMark(color: Color) {
        val r = minOf(w, h) * 0.24f
        val bounds = Rect(c, r)
        d.drawArc(color, 200f, 140f, false, bounds.topLeft, bounds.size, style = Stroke(sw * 1.2f, cap = StrokeCap.Round))
        d.drawArc(color, 20f, 140f, false, bounds.topLeft, bounds.size, style = Stroke(sw * 1.2f, cap = StrokeCap.Round))
        arrowHead(Offset(c.x + r * 0.77f, c.y - r * 0.64f), Offset(0.6f, 0.8f), r * 0.5f, color)
        arrowHead(Offset(c.x - r * 0.77f, c.y + r * 0.64f), Offset(-0.6f, -0.8f), r * 0.5f, color)
    }

    /** Up and down arrows: vary until met. */
    fun adjustMark() {
        line(at(0.4f, 0.66f), at(0.4f, 0.36f), p.accent, sw * 1.1f)
        arrowHead(at(0.4f, 0.3f), Offset(0f, -1f), w * 0.1f, p.accent)
        line(at(0.6f, 0.34f), at(0.6f, 0.64f), p.accent, sw * 1.1f)
        arrowHead(at(0.6f, 0.7f), Offset(0f, 1f), w * 0.1f, p.accent)
    }

    /** Equals sign: one value set from another. */
    fun setMark() {
        line(at(0.34f, 0.44f), at(0.66f, 0.44f), p.accent, sw * 1.4f)
        line(at(0.34f, 0.56f), at(0.66f, 0.56f), p.accent, sw * 1.4f)
    }
}
