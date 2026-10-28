/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import kotlin.math.abs

/**
 * Lines a flowsheet up for drawing: a stream arrow a little off the level of the side nozzle it joins is drawn exactly
 * level with it, and a unit fed by a single stream that already runs level from upstream is drawn with its inlet on
 * that stream's level. Many nozzles sit off their symbol's centre (heater and cooler 3.4 units up, valve 7.1 down,
 * pump outlet 13 up), so units and arrows placed on the grid are a few units off, which draws as a break in the line.
 *
 * Only what is drawn moves: the saved positions (part of the model hash) are left alone, so no result goes stale.
 * Offsets beyond [TOLERANCE] are the user's layout and stay.
 */
object PortAlignment {
    const val TOLERANCE = 14.0

    fun align(fs: Flowsheet): Flowsheet {
        var f = fs
        repeat(6) {
            val objs = f.objects.associateBy { it.id }.toMutableMap()
            var changed = false
            for (c in f.connections) {
                val from = objs[c.fromObject] ?: continue
                val to = objs[c.toObject] ?: continue
                if (from.type.isStream == to.type.isStream) continue
                if (from.type.isStream) {
                    // A stream into a unit's side nozzle.
                    val port = Ports.find(to.type, PortSide.Inlet, c.toPort)?.takeIf { it.facing.second == 0 } ?: continue
                    val d = Symbols.portPosition(to.type, port, to.x, to.y).second - from.y
                    if (abs(d) < 1e-6 || abs(d) > TOLERANCE) continue
                    val hasSource = f.connections.any { it.toObject == from.id }
                    val materialFeeds = f.connections.count { it.toObject == to.id && objs[it.fromObject]?.type == ObjectType.MaterialStream }
                    when {
                        !hasSource -> objs[from.id] = from.copy(y = from.y + d) // a feed: the arrow moves to the nozzle
                        materialFeeds == 1 -> objs[to.id] = to.copy(y = to.y - d) // fed from upstream: the unit moves to the line
                        else -> continue
                    }
                    changed = true
                } else {
                    // A product leaving a unit's side nozzle: the arrow moves to the nozzle.
                    val port = Ports.find(from.type, PortSide.Outlet, c.fromPort)?.takeIf { it.facing.second == 0 } ?: continue
                    val d = Symbols.portPosition(from.type, port, from.x, from.y).second - to.y
                    if (abs(d) < 1e-6 || abs(d) > TOLERANCE) continue
                    objs[to.id] = to.copy(y = to.y + d)
                    changed = true
                }
            }
            f = f.copy(objects = f.objects.map { objs.getValue(it.id) })
            if (!changed) return f
        }
        return f
    }
}
