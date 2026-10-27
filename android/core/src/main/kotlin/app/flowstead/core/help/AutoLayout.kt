/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.help

import app.flowstead.core.model.Flowsheet

/**
 * Places a flowsheet that came without positions (some examples): each object one column right of the furthest
 * object feeding it, objects in a column stacked, unconnected objects (Sets, Adjusts) in a last column.
 */
object AutoLayout {
    const val COLUMN = 144.0
    const val ROW = 120.0

    /** Lays [fs] out only when two or more objects share a position. */
    fun ifNeeded(fs: Flowsheet): Flowsheet =
        if (fs.objects.map { it.x to it.y }.distinct().size == fs.objects.size) fs else layout(fs)

    fun layout(fs: Flowsheet): Flowsheet {
        val upstream = fs.connections.groupBy({ it.toObject }, { it.fromObject })
        val depth = mutableMapOf<String, Int>()
        fun depthOf(id: String, seen: Set<String>): Int = depth[id] ?: run {
            // A recycle loop is cut where it closes.
            val d = upstream[id].orEmpty().filter { it !in seen }.maxOfOrNull { depthOf(it, seen + id) + 1 } ?: 0
            depth[id] = d
            d
        }
        val connected = fs.connections.flatMap { listOf(it.fromObject, it.toObject) }.toSet()
        fs.objects.filter { it.id in connected }.forEach { depthOf(it.id, emptySet()) }
        val last = (depth.values.maxOrNull() ?: -1) + 1
        val column = fs.objects.associate { it.id to (depth[it.id] ?: last) }
        val rowInColumn = mutableMapOf<Int, Int>()
        val placed = fs.objects.map { o ->
            val c = column.getValue(o.id)
            val r = rowInColumn.merge(c, 1, Int::plus)!! - 1
            o.copy(x = c * COLUMN, y = r * ROW)
        }
        return fs.copy(objects = placed)
    }
}
