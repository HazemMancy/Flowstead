/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PortAlignmentTest {
    private fun obj(id: String, type: ObjectType, x: Double, y: Double = 0.0) = SimObject(id, id, type, x = x, y = y)

    /** Every side-nozzle connection of [fs]: (stream y, nozzle y). */
    private fun levels(fs: Flowsheet) = fs.connections.mapNotNull { c ->
        val from = fs.obj(c.fromObject)!!
        val to = fs.obj(c.toObject)!!
        val (unit, stream, port) = if (from.type.isStream) Triple(to, from, Ports.find(to.type, PortSide.Inlet, c.toPort)!!)
        else Triple(from, to, Ports.find(from.type, PortSide.Outlet, c.fromPort)!!)
        if (port.facing.second != 0) null else stream.y to Symbols.portPosition(unit.type, port, unit.x, unit.y).second
    }

    @Test
    fun aGridPlacedTrainIsDrawnLevel() {
        // Compressor, discharge, cooler (nozzles 3.4 up), cooled, valve (nozzles 7.1 down), letdown: all on y = 0.
        val fs = Flowsheet(
            objects = listOf(
                obj("feed", ObjectType.MaterialStream, 0.0), obj("k", ObjectType.Compressor, 120.0),
                obj("d", ObjectType.MaterialStream, 240.0), obj("e", ObjectType.Cooler, 360.0),
                obj("c", ObjectType.MaterialStream, 480.0), obj("v", ObjectType.Valve, 600.0), obj("out", ObjectType.MaterialStream, 720.0),
            ),
            connections = listOf(
                Connection("feed", 0, "k", 0), Connection("k", 0, "d", 0), Connection("d", 0, "e", 0),
                Connection("e", 0, "c", 0), Connection("c", 0, "v", 0), Connection("v", 0, "out", 0),
            ),
        )
        assertTrue(levels(fs).any { (s, p) -> abs(s - p) > 1 }, "the test train should start off level")
        val aligned = PortAlignment.align(fs)
        levels(aligned).forEach { (s, p) -> assertEquals(p, s, 1e-9) }
        assertEquals(fs.objects.map { it.x }, aligned.objects.map { it.x }) // only heights change
    }

    @Test
    fun aDeliberateOffsetStays() {
        val fs = Flowsheet(
            objects = listOf(obj("f", ObjectType.MaterialStream, 0.0, -60.0), obj("v", ObjectType.Valve, 120.0)),
            connections = listOf(Connection("f", 0, "v", 0)),
        )
        assertEquals(fs, PortAlignment.align(fs))
    }

    @Test
    fun everyExampleIsAlreadyLevel() {
        for (e in app.flowstead.core.help.Help.examples) {
            val fs = app.flowstead.core.help.Help.example(e.id) ?: continue
            assertEquals(fs.objects, PortAlignment.align(fs).objects, "${e.id} should need no alignment")
        }
    }
}
