/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.ui.flowsheet

import androidx.compose.ui.geometry.Offset
import app.thermodock.core.model.Connection
import app.thermodock.core.model.Flowsheet
import app.thermodock.core.model.ObjectType
import app.thermodock.core.model.SimObject
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CanvasGeometryTest {
    private val valve = SimObject("v", "FV-100", ObjectType.Valve, x = 300.0, y = 200.0)
    private val feed = SimObject("f", "FEED", ObjectType.MaterialStream, x = 120.0, y = 180.0)
    private val fs = Flowsheet(objects = listOf(feed, valve), connections = listOf(Connection("f", 0, "v", 0)))

    @Test
    fun aStreamDroppedNearlyLevelWithItsPortLinesUpExactly() {
        // Dropped 9 units off level: it settles where its line to the valve's inlet is horizontal.
        val (_, port) = CanvasGeometry.endpoints(fs, fs.connections.single()) { CanvasGeometry.center(it) }!!
        val at = CanvasGeometry.snap(fs, feed, Offset(130f, port.y + 9f))
        val moved = fs.copy(objects = listOf(feed.copy(x = at.x.toDouble(), y = at.y.toDouble()), valve))
        val (a, b) = CanvasGeometry.endpoints(moved, moved.connections.single()) { CanvasGeometry.center(it) }!!
        assertEquals(a.y, b.y, 1e-3f)
        assertEquals(1, CanvasGeometry.route(a, b).size - 1) // one straight segment
    }

    @Test
    fun farOffLevelItOnlyLandsOnTheGrid() {
        val at = CanvasGeometry.snap(fs, feed, Offset(131f, 77f))
        assertEquals(132f, at.x)
        assertTrue(abs(at.y / CanvasGeometry.GRID - Math.round(at.y / CanvasGeometry.GRID)) < 1e-4)
    }
}
