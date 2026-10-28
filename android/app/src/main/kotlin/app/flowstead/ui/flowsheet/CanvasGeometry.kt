/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.flowsheet

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import app.flowstead.core.model.Connection
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.Port
import app.flowstead.core.model.PortSide
import app.flowstead.core.model.Ports
import app.flowstead.core.model.SimObject
import app.flowstead.core.model.obj

/** World units are dp at zoom 1. Sizes keep every touch target at least 48 dp at the default zoom. */
object CanvasGeometry {
    const val UNIT_SIZE = 56f
    const val STREAM_W = 48f
    const val STREAM_H = 24f
    const val PORT_RADIUS = 6f
    /** Finger-sized hit radius around a port (48 dp target at zoom 1). */
    const val PORT_HIT = 24f
    const val MIN_ZOOM = 0.35f
    const val MAX_ZOOM = 3f

    /** Symbol box of each type (see Icons.kt); ports are placed as fractions of it. */
    fun size(type: ObjectType): Size {
        val u = UNIT_SIZE
        return when (type) {
            ObjectType.MaterialStream, ObjectType.EnergyStream -> Size(STREAM_W, STREAM_H)
            ObjectType.Separator, ObjectType.SolidsSeparator -> Size(u * 0.7f, u * 1.2f)
            ObjectType.ThreePhaseSeparator -> Size(u * 1.4f, u * 0.8f)
            ObjectType.HeatExchanger -> Size(u * 1.3f, u * 0.8f)
            ObjectType.AirCooler -> Size(u * 1.3f, u)
            ObjectType.Compressor, ObjectType.Expander -> Size(u * 1.1f, u)
            ObjectType.Valve -> Size(u, u * 0.85f)
            ObjectType.PipeSegment -> Size(u * 1.5f, u * 0.5f)
            ObjectType.OrificePlate -> Size(u, u * 0.6f)
            ObjectType.ReliefValve -> Size(u * 0.85f, u)
            ObjectType.Mixer, ObjectType.Splitter -> Size(u * 0.85f, u)
            ObjectType.Tank -> Size(u * 0.95f, u * 1.1f)
            ObjectType.ShortcutColumn, ObjectType.DistillationColumn, ObjectType.Absorber,
            ObjectType.RefluxedAbsorber, ObjectType.ReboiledAbsorber -> Size(u * 1.1f, u * 2f)
            ObjectType.ConversionReactor, ObjectType.EquilibriumReactor, ObjectType.GibbsReactor -> Size(u * 0.9f, u * 1.15f)
            ObjectType.Cstr -> Size(u, u * 1.15f)
            ObjectType.Pfr -> Size(u * 1.6f, u * 0.6f)
            else -> Size(u, u)
        }
    }

    fun center(o: SimObject) = Offset(o.x.toFloat(), o.y.toFloat())

    fun bounds(o: SimObject, at: Offset = center(o)): Rect {
        val s = size(o.type)
        return Rect(at - Offset(s.width / 2, s.height / 2), s)
    }

    fun portPosition(o: SimObject, port: Port, at: Offset = center(o)): Offset {
        val s = size(o.type)
        return at + Offset(port.dx * s.width, port.dy * s.height)
    }

    fun streamInlet(o: SimObject, at: Offset = center(o)) = at - Offset(STREAM_W / 2, 0f)
    fun streamOutlet(o: SimObject, at: Offset = center(o)) = at + Offset(STREAM_W / 2, 0f)

    /** Start and end of a connection line (stream end ↔ unit-op port). */
    fun endpoints(fs: Flowsheet, c: Connection, positionOf: (SimObject) -> Offset): Pair<Offset, Offset>? {
        val from = fs.obj(c.fromObject) ?: return null
        val to = fs.obj(c.toObject) ?: return null
        return if (from.type.isStream) {
            val port = Ports.find(to.type, PortSide.Inlet, c.toPort) ?: return null
            streamOutlet(from, positionOf(from)) to portPosition(to, port, positionOf(to))
        } else {
            val port = Ports.find(from.type, PortSide.Outlet, c.fromPort) ?: return null
            portPosition(from, port, positionOf(from)) to streamInlet(to, positionOf(to))
        }
    }

    /** A connection's route: one straight line from end to end (the user lines objects up; [snap] helps). */
    fun route(a: Offset, b: Offset): List<Offset> = listOf(a, b)

    const val GRID = 12f
    private const val ALIGN = 20f

    /**
     * Where a dropped object settles: on the 12-unit grid, then, if one of its connections ends within 20 units of
     * level (or plumb) with the other end, moved so that line is exactly horizontal (or vertical).
     */
    fun snap(fs: Flowsheet, o: SimObject, at: Offset): Offset {
        var p = Offset(Math.round(at.x / GRID) * GRID, Math.round(at.y / GRID) * GRID)
        val pos = { x: SimObject -> if (x.id == o.id) p else center(x) }
        val pairs = fs.connections.filter { it.fromObject == o.id || it.toObject == o.id }.mapNotNull { c ->
            val (a, b) = endpoints(fs, c, pos) ?: return@mapNotNull null
            if (c.fromObject == o.id) a to b else b to a // (my end, the other end)
        }
        pairs.mapNotNull { (mine, other) -> (other.y - mine.y).takeIf { kotlin.math.abs(it) <= ALIGN } }.minByOrNull { kotlin.math.abs(it) }
            ?.let { p = p.copy(y = p.y + it) }
        pairs.mapNotNull { (mine, other) -> (other.x - mine.x).takeIf { kotlin.math.abs(it) <= ALIGN && kotlin.math.abs(other.y - mine.y) > ALIGN } }
            .minByOrNull { kotlin.math.abs(it) }?.let { p = p.copy(x = p.x + it) }
        return p
    }
}

/** Pan and zoom of the canvas. screen = world × zoom × density + offset. */
data class Viewport(val offset: Offset = Offset.Zero, val zoom: Float = 1f) {
    fun toScreen(world: Offset, density: Float) = world * (zoom * density) + offset
    fun toWorld(screen: Offset, density: Float) = (screen - offset) / (zoom * density)

    /** Zoom by [factor] keeping the world point under [focus] (screen px) fixed. */
    fun zoomAround(focus: Offset, factor: Float): Viewport {
        val z = (zoom * factor).coerceIn(CanvasGeometry.MIN_ZOOM, CanvasGeometry.MAX_ZOOM)
        val actual = z / zoom
        return copy(zoom = z, offset = focus - (focus - offset) * actual)
    }
}

sealed interface Hit {
    data class OnPort(val objectId: String, val port: Port) : Hit
    data class OnObject(val objectId: String) : Hit
    data object Empty : Hit
}

/** Ports win over bodies so a port on top of an icon is always reachable. */
fun hitTest(fs: Flowsheet, world: Offset, zoom: Float): Hit {
    val portHit = CanvasGeometry.PORT_HIT / zoom.coerceAtMost(1f).coerceAtLeast(0.5f)
    var best: Hit.OnPort? = null
    var bestDist = Float.MAX_VALUE
    for (o in fs.objects) {
        if (o.type.isStream) continue
        for (p in Ports.of(o.type)) {
            val d = (CanvasGeometry.portPosition(o, p) - world).getDistance()
            if (d < portHit && d < bestDist) {
                bestDist = d
                best = Hit.OnPort(o.id, p)
            }
        }
    }
    if (best != null) return best
    // Topmost (last drawn) object first.
    for (o in fs.objects.asReversed()) {
        if (CanvasGeometry.bounds(o).inflate(8f).contains(world)) return Hit.OnObject(o.id)
    }
    return Hit.Empty
}
