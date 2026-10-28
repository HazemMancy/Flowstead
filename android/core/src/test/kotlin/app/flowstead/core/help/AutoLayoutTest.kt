/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.help

import app.flowstead.core.model.PortSide
import app.flowstead.core.model.Ports
import app.flowstead.core.model.SimObject
import app.flowstead.core.model.Symbols
import app.flowstead.core.model.facing
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class AutoLayoutTest {
    private fun box(o: SimObject, margin: Double = 0.0): DoubleArray {
        val (w, h) = Symbols.size(o.type)
        return doubleArrayOf(o.x - w / 2 - margin, o.y - h / 2 - margin, o.x + w / 2 + margin, o.y + h / 2 + margin)
    }

    private fun overlap(a: DoubleArray, b: DoubleArray) = a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]

    @Test
    fun everyExampleIsLaidOutWithoutOverlapsAndWithStreamsBesideTheirPorts() {
        for (example in Help.examples) {
            val fs = Help.example(example.id) ?: continue
            val objects = fs.objects
            for (i in objects.indices) for (j in i + 1 until objects.size) {
                assertTrue(!overlap(box(objects[i]), box(objects[j])), "${example.id}: ${objects[i].tag} overlaps ${objects[j].tag}")
            }
            for (c in fs.connections) {
                val from = objects.first { it.id == c.fromObject }
                val to = objects.first { it.id == c.toObject }
                val (unit, stream, port) = if (from.type.isStream) Triple(to, from, Ports.find(to.type, PortSide.Inlet, c.toPort)!!)
                else Triple(from, to, Ports.find(from.type, PortSide.Outlet, c.fromPort)!!)
                val (px, py) = Symbols.portPosition(unit.type, port, unit.x, unit.y)
                // A product sits right of its port and a feed left of it (a stream between two units, which may run
                // back to an earlier one, belongs with its source), level with the port when the port is on a side.
                val joinsTwo = fs.connections.count { it.fromObject == stream.id || it.toObject == stream.id } == 2
                if (!from.type.isStream) assertTrue(stream.x > px, "${example.id}: product ${stream.tag} is not right of ${unit.tag}")
                else if (!joinsTwo) assertTrue(stream.x < px, "${example.id}: feed ${stream.tag} is not left of ${unit.tag}")
                if (!joinsTwo && port.facing.second == 0 && port.facing.first == (if (from.type.isStream) -1 else 1)) {
                    assertTrue(abs(stream.y - py) < 1e-6, "${example.id}: ${stream.tag} is not level with ${unit.tag} ${port.label}")
                }
            }
        }
    }
}
