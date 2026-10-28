/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.help

import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.PortSide
import app.flowstead.core.model.Ports
import app.flowstead.core.model.SimObject
import app.flowstead.core.model.Symbols
import app.flowstead.core.model.facing
import kotlin.math.abs

/**
 * Places a flowsheet's objects as a PFD is drawn (the examples): unit operations in columns
 * left to right in flow order, each one column right of the furthest unit feeding it, with clear space between them,
 * and level with what feeds it where there is room; every stream beside the port it leaves (a feed, beside the port it
 * enters), level with it, or just above or below a port on the top or bottom, so its lines run straight. Unconnected objects (Sets, Adjusts) go in a last column.
 */
object AutoLayout {
    /** Clear space between unit columns, room for a product arrow and the next unit's feed arrow. */
    const val COLUMN_GAP = 240.0
    /** Clear space between units stacked in one column, room for streams above and below them and their labels. */
    const val ROW_GAP = 120.0
    /** How far a stream arrow's centre sits from its port. */
    private const val STREAM_OFFSET = 72.0
    private const val STREAM_RISE = 36.0

    fun layout(fs: Flowsheet): Flowsheet {
        val units = fs.objects.filter { !it.type.isStream }
        // Each stream's source and destination (unit, connection).
        val source = fs.connections.filter { c -> fs.objects.any { it.id == c.toObject && it.type.isStream } }.associateBy { it.toObject }
        val destination = fs.connections.filter { c -> fs.objects.any { it.id == c.fromObject && it.type.isStream } }.associateBy { it.fromObject }
        // Unit-to-unit edges through streams. A loop closes at its Recycle block, so what leaves a Recycle doesn't
        // push the unit it feeds further right.
        val recycles = units.filter { it.type == ObjectType.Recycle }.map { it.id }.toSet()
        val upstream = units.associate { u ->
            u.id to destination.values.filter { it.toObject == u.id }.mapNotNull { source[it.fromObject]?.fromObject }.filter { it != u.id && it !in recycles }
        }
        val depth = mutableMapOf<String, Int>()
        fun depthOf(id: String, seen: Set<String>): Int = depth[id] ?: run {
            // Any other loop is cut where the search meets it again.
            val d = upstream[id].orEmpty().filter { it !in seen }.maxOfOrNull { depthOf(it, seen + id) + 1 } ?: 0
            depth[id] = d
            d
        }
        val connected = fs.connections.flatMap { listOf(it.fromObject, it.toObject) }.toSet()
        units.filter { it.id in connected }.forEach { depthOf(it.id, emptySet()) }
        val loose = fs.objects.filter { it.id !in connected }
        val lastColumn = (depth.values.maxOrNull() ?: -1) + 1

        val unitById = units.associateBy { it.id }
        val pos = mutableMapOf<String, Pair<Double, Double>>()
        /** Where a stream's arrow sits beside the port it leaves, once its source unit is placed. */
        fun productAt(streamId: String): Pair<Double, Double>? {
            val c = source[streamId] ?: return null
            val u = unitById[c.fromObject] ?: return null
            val p = Ports.find(u.type, PortSide.Outlet, c.fromPort) ?: return null
            val (ux, uy) = pos[u.id] ?: return null
            return beside(Symbols.portPosition(u.type, p, ux, uy), p.facing, product = true)
        }
        fun streamY(streamId: String) = productAt(streamId)?.second

        // Units: columns left to right, each column ordered by where its feeds come from, then stacked.
        var x = 0.0
        var previousHalf = 0.0
        for (col in 0..lastColumn) {
            val members = units.filter { (depth[it.id] ?: -1) == col } + if (col == lastColumn) loose.filter { !it.type.isStream } else emptyList()
            if (members.isEmpty()) continue
            val half = members.maxOf { Symbols.size(it.type).first } / 2
            x += if (pos.isEmpty()) 0.0 else previousHalf + COLUMN_GAP + half
            previousHalf = half
            // Each unit with its feed ports level with the streams feeding it, where there is room.
            val wanted = members.associate { u ->
                u.id to destination.values.filter { it.toObject == u.id }.mapNotNull { c ->
                    val q = Ports.find(u.type, PortSide.Inlet, c.toPort) ?: return@mapNotNull null
                    streamY(c.fromObject)?.let { it - q.dy * Symbols.size(u.type).second }
                }.average().takeIf { !it.isNaN() }
            }
            val ordered = members.withIndex().sortedWith(compareBy({ wanted[it.value.id] ?: Double.NEGATIVE_INFINITY }, { it.index })).map { it.value }
            var previousBottom: Double? = null
            for (u in ordered) {
                val h = Symbols.size(u.type).second
                val lowest = previousBottom?.let { it + ROW_GAP + h / 2 } ?: Double.NEGATIVE_INFINITY
                val y = maxOf(wanted[u.id] ?: if (previousBottom == null) 0.0 else lowest, lowest)
                pos[u.id] = x to y
                previousBottom = y + h / 2
            }
        }

        // Streams: beside their port.
        val taken = mutableListOf<Pair<Double, Double>>()
        fun free(p: Pair<Double, Double>, step: Double): Pair<Double, Double> {
            var q = p
            repeat(6) {
                if (taken.none { abs(it.first - q.first) < Symbols.STREAM_W + 8 && abs(it.second - q.second) < Symbols.STREAM_H + 4 }) return q
                q = q.first + step to q.second
            }
            return q
        }
        val leftover = mutableListOf<SimObject>()
        for (s in fs.objects.filter { it.type.isStream }) {
            val to = destination[s.id]?.let { c -> unitById[c.toObject]?.let { u -> Ports.find(u.type, PortSide.Inlet, c.toPort)?.let { u to it } } }
            val toAt = to?.let { (u, p) -> pos[u.id]?.let { (ux, uy) -> Symbols.portPosition(u.type, p, ux, uy) } }
            // A product (also one running on to another unit) beside the port it leaves; a feed beside its port.
            val at = productAt(s.id) ?: toAt?.let { beside(it, to.second.facing, product = false) }
            if (at == null) { leftover += s; continue }
            val placed = free(at, if (source[s.id] != null) STREAM_OFFSET else -STREAM_OFFSET)
            taken += placed
            pos[s.id] = placed
        }
        // Streams joined to nothing: a row under everything else.
        val bottom = (pos.values.maxOfOrNull { it.second } ?: 0.0) + ROW_GAP
        leftover.forEachIndexed { i, s -> pos[s.id] = i * (Symbols.STREAM_W + COLUMN_GAP / 2) to bottom }

        return fs.copy(objects = fs.objects.map { o -> pos[o.id]?.let { (px, py) -> o.copy(x = px, y = py) } ?: o })
    }

    /**
     * Where a stream arrow goes for a port at [at] facing [facing]: a product to the right of the port, a feed to the
     * left (arrows point right); level with a side port, a little above or below a port on the top or bottom.
     */
    private fun beside(at: Pair<Double, Double>, facing: Pair<Int, Int>, product: Boolean): Pair<Double, Double> {
        val (fx, fy) = facing
        val x = at.first + if (product) STREAM_OFFSET else -STREAM_OFFSET
        val y = at.second + fy * STREAM_RISE
        return when {
            fx != 0 && product == (fx > 0) -> x to at.second
            fx != 0 -> x to at.second + STREAM_RISE // a product port on the left, or a feed port on the right: step round
            else -> x to y
        }
    }
}
