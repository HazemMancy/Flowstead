/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.flowsheet

import androidx.compose.ui.geometry.Offset
import app.flowstead.core.model.Connection
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.SimObject
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
        assertEquals(1, CanvasGeometry.connectionRoute(moved, moved.connections.single()) { CanvasGeometry.center(it) }!!.size - 1) // one straight segment
    }

    @Test
    fun linesRunOnlyAcrossAndDownAndLeavePortsSquare() {
        // A feed well above the valve's inlet: out of the arrow's head, across, down, and into the inlet from the left.
        val high = fs.copy(objects = listOf(feed.copy(y = 80.0), valve))
        val pts = CanvasGeometry.connectionRoute(high, high.connections.single()) { CanvasGeometry.center(it) }!!
        pts.zipWithNext().forEach { (p, q) -> assertTrue(abs(p.x - q.x) < 1e-3f || abs(p.y - q.y) < 1e-3f, "diagonal run $p → $q") }
        val (start, port) = CanvasGeometry.endpoints(high, high.connections.single()) { CanvasGeometry.center(it) }!!
        assertEquals(start, pts.first())
        assertEquals(port, pts.last())
        assertEquals(port.y, pts[pts.size - 2].y, 1e-3f) // the last run comes in level, from the left
        assertTrue(pts[pts.size - 2].x < port.x)
    }

    @Test
    fun aLineGoingBackRunsUnderTheSymbolsInItsWay() {
        // A stream placed right of the valve, feeding it: the line goes round underneath, clear of the valve.
        val behind = fs.copy(objects = listOf(feed.copy(x = 420.0, y = 200.0), valve))
        val pts = CanvasGeometry.connectionRoute(behind, behind.connections.single()) { CanvasGeometry.center(it) }!!
        val bottom = CanvasGeometry.bounds(valve).bottom
        assertTrue(pts.maxOf { it.y } > bottom, "the return run should pass below the valve")
    }

    @Test
    fun farOffLevelItOnlyLandsOnTheGrid() {
        val at = CanvasGeometry.snap(fs, feed, Offset(131f, 77f))
        assertEquals(132f, at.x)
        assertTrue(abs(at.y / CanvasGeometry.GRID - Math.round(at.y / CanvasGeometry.GRID)) < 1e-4)
    }
}
