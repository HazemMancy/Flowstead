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

    fun size(type: ObjectType): Size = when (type) {
        ObjectType.MaterialStream, ObjectType.EnergyStream -> Size(STREAM_W, STREAM_H)
        ObjectType.Separator -> Size(UNIT_SIZE * 0.7f, UNIT_SIZE * 1.2f)
        ObjectType.ThreePhaseSeparator -> Size(UNIT_SIZE * 1.4f, UNIT_SIZE * 0.8f)
        else -> Size(UNIT_SIZE, UNIT_SIZE)
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

    /** Orthogonal elbow route, the way process flow diagrams are drawn. */
    fun elbow(a: Offset, b: Offset): List<Offset> {
        val midX = (a.x + b.x) / 2
        return listOf(a, Offset(midX, a.y), Offset(midX, b.y), b)
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
