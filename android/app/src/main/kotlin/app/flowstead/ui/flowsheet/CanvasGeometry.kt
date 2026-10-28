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
import app.flowstead.core.model.Symbols
import app.flowstead.core.model.facing
import app.flowstead.core.model.obj
import kotlin.math.abs

/** World units are dp at zoom 1. Sizes keep every touch target at least 48 dp at the default zoom. */
object CanvasGeometry {
    // The same sizes as Symbols (core), as floats for drawing.
    const val UNIT_SIZE = 56f
    const val STREAM_W = 48f
    const val STREAM_H = 24f
    const val PORT_RADIUS = 6f
    /** Finger-sized hit radius around a port (48 dp target at zoom 1). */
    const val PORT_HIT = 24f
    const val MIN_ZOOM = 0.35f
    const val MAX_ZOOM = 3f

    /** Symbol box of each type (see Icons.kt); ports are placed as fractions of it. */
    fun size(type: ObjectType): Size = Symbols.size(type).let { (w, h) -> Size(w.toFloat(), h.toFloat()) }

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

    /**
     * A connection's route, as HYSYS draws it: horizontal and vertical runs only. The line leaves an outlet port and
     * meets an inlet port square to the side of the symbol the port is on, and leaves or enters a stream's arrow
     * from its tail or head, so lines never cut across a symbol at an angle.
     */
    fun connectionRoute(fs: Flowsheet, c: Connection, positionOf: (SimObject) -> Offset): List<Offset>? {
        val from = fs.obj(c.fromObject) ?: return null
        val to = fs.obj(c.toObject) ?: return null
        val right = Offset(1f, 0f)
        // A line going back runs underneath, below every symbol (and its label) in its way.
        val boxes = fs.objects.map { bounds(it, positionOf(it)) }
        val clear = { x0: Float, x1: Float, y: Float ->
            var under = y
            var passes = 0
            while (passes++ < 16) {
                val hit = boxes.filter { it.right > x0 && it.left < x1 && under > it.top - 4f && under < it.bottom + LABEL }.maxOfOrNull { it.bottom } ?: break
                under = hit + LABEL + 4f
            }
            under
        }
        // An arrow a hair off its port's level (within the pipe's half-thickness) takes the line straight in, rather
        // than through a tiny step that reads as a break.
        fun level(streamEnd: Offset, port: Port, at: Offset) =
            if (port.facing.second == 0 && abs(streamEnd.y - at.y) <= STREAM_H * 0.15f) streamEnd.copy(y = at.y) else streamEnd
        return if (from.type.isStream) {
            val port = Ports.find(to.type, PortSide.Inlet, c.toPort) ?: return null
            val at = portPosition(to, port, positionOf(to))
            route(level(streamOutlet(from, positionOf(from)), port, at), right, at, -port.facingOffset(), clear)
        } else {
            val port = Ports.find(from.type, PortSide.Outlet, c.fromPort) ?: return null
            val at = portPosition(from, port, positionOf(from))
            route(at, port.facingOffset(), level(streamInlet(to, positionOf(to)), port, at), right, clear)
        }
    }

    /** Room under a symbol for its tag. */
    private const val LABEL = 20f

    private fun Port.facingOffset() = facing.let { (x, y) -> Offset(x.toFloat(), y.toFloat()) }

    private const val STUB = 12f
    private const val DETOUR = 36f

    /**
     * An orthogonal route from [a], leaving in direction [da], to [b], arriving in direction [db] (unit vectors along
     * an axis). Short stubs keep the first and last runs square to the symbols; one or two bends join them. A line
     * going back runs underneath, at the height [clear] gives for the span it crosses.
     */
    fun route(
        a: Offset, da: Offset, b: Offset, db: Offset,
        clear: (x0: Float, x1: Float, y: Float) -> Float = { _, _, y -> y },
    ): List<Offset> {
        val a1 = a + da * STUB
        val b1 = b - db * STUB
        val aSide = da.y == 0f
        val bSide = db.y == 0f
        val bends: List<Offset> = when {
            aSide && bSide -> when {
                same(da, db) && (b1.x - a1.x) * da.x >= 0 ->
                    if (abs(a1.y - b1.y) < 0.5f) emptyList() else ((a1.x + b1.x) / 2).let { listOf(Offset(it, a1.y), Offset(it, b1.y)) }
                // Going back (a recycle, or an arrow placed behind its port): round underneath, as recycles are drawn.
                else -> clear(minOf(a1.x, b1.x), maxOf(a1.x, b1.x), maxOf(a1.y, b1.y) + DETOUR).let { listOf(Offset(a1.x, it), Offset(b1.x, it)) }
            }
            !aSide && !bSide -> when {
                same(da, db) && (b1.y - a1.y) * da.y >= 0 ->
                    if (abs(a1.x - b1.x) < 0.5f) emptyList() else ((a1.y + b1.y) / 2).let { listOf(Offset(a1.x, it), Offset(b1.x, it)) }
                else -> (if (abs(a1.x - b1.x) < 0.5f) a1.x + DETOUR else (a1.x + b1.x) / 2).let { listOf(Offset(it, a1.y), Offset(it, b1.y)) }
            }
            aSide -> Offset(b1.x, a1.y).let { c ->
                if ((c.x - a1.x) * da.x >= 0 && (b1.y - c.y) * db.y >= 0) listOf(c) else listOf(Offset(a1.x, b1.y))
            }
            else -> Offset(a1.x, b1.y).let { c ->
                if ((c.y - a1.y) * da.y >= 0 && (b1.x - c.x) * db.x >= 0) listOf(c) else listOf(Offset(b1.x, a1.y))
            }
        }
        return simplify(listOf(a, a1) + bends + listOf(b1, b))
    }

    /** Equal directions; compares the numbers, since Offset's own equality tells −0 from 0. */
    private fun same(a: Offset, b: Offset) = a.x == b.x && a.y == b.y

    /** Drops repeated points and points in the middle of a straight run. */
    private fun simplify(points: List<Offset>): List<Offset> {
        val out = mutableListOf<Offset>()
        for (p in points) {
            if (out.isNotEmpty() && (out.last() - p).getDistance() < 0.01f) continue
            if (out.size >= 2) {
                val q = out[out.size - 2]
                val m = out.last()
                val straight = (abs(q.x - m.x) < 0.01f && abs(m.x - p.x) < 0.01f) || (abs(q.y - m.y) < 0.01f && abs(m.y - p.y) < 0.01f)
                // Keep a point where the run turns back on itself, so the line is not drawn over twice.
                val forward = (m - q).x * (p - m).x + (m - q).y * (p - m).y > 0
                if (straight && forward) out.removeAt(out.size - 1)
            }
            out += p
        }
        return out
    }

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
        pairs.mapNotNull { (mine, other) -> (other.y - mine.y).takeIf { abs(it) <= ALIGN } }.minByOrNull { abs(it) }
            ?.let { p = p.copy(y = p.y + it) }
        pairs.mapNotNull { (mine, other) -> (other.x - mine.x).takeIf { abs(it) <= ALIGN && abs(other.y - mine.y) > ALIGN } }
            .minByOrNull { abs(it) }?.let { p = p.copy(x = p.x + it) }
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
