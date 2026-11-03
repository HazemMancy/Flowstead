/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.api.BinaryDiagramKinds
import app.flowstead.core.api.BinaryDiagramResult
import app.flowstead.core.api.ObjectResult
import app.flowstead.core.api.ObjectStatus
import app.flowstead.core.api.PhaseResult
import app.flowstead.core.api.PropertySweepResult
import app.flowstead.core.api.SweepVariables
import app.flowstead.core.api.TernaryPoint
import app.flowstead.core.api.TernaryResult
import app.flowstead.core.api.XY
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThermoPlotsTest {
    @Test
    fun aBinaryDiagramHasItsCurvesAndNothingElse() {
        val r = BinaryDiagramResult(BinaryDiagramKinds.TXY, bubble = listOf(XY(1.0, 342.0), XY(0.0, 372.0)), dew = listOf(XY(0.0, 372.0), XY(1.0, 342.0)))
        val p = ThermoPlots.binary(r, "N-hexane")!!
        assertEquals(2, p.series.size)
        assertEquals(listOf(0.0, 1.0), p.series[0].x) // sorted by composition
        assertNull(ThermoPlots.binary(BinaryDiagramResult(BinaryDiagramKinds.TXY), "A"))
    }

    @Test
    fun aSweepLeavesOutPointsWithoutTheProperty() {
        fun o(rho: Double?) = ObjectResult(ObjectStatus.Solved, phases = rho?.let { mapOf("vapour" to PhaseResult(mapOf("density" to it))) })
        val r = PropertySweepResult(listOf(1e5, 2e5, 3e5), listOf(o(1.0), null, o(3.0)))
        val prop = ThermoPlots.SWEEP_PROPERTIES.first { it.label == "Vapour density" }
        val p = ThermoPlots.sweep(r, SweepVariables.PRESSURE, prop)!!
        assertEquals(listOf(1e5, 3e5), p.series.single().x)
    }

    @Test
    fun theTriangleHasTheFirstCompoundOnTop() {
        assertEquals(0.5 to Math.sqrt(3.0) / 2, ThermoPlots.triangle(listOf(1.0, 0.0, 0.0)))
        assertEquals(0.0 to 0.0, ThermoPlots.triangle(listOf(0.0, 1.0, 0.0)))
        assertEquals(1.0 to 0.0, ThermoPlots.triangle(listOf(0.0, 0.0, 1.0)))
        val r = TernaryResult(points = listOf(
            TernaryPoint(listOf(0.5, 0.5, 0.0), liquid1 = listOf(0.99, 0.01, 0.0), liquid2 = listOf(0.01, 0.99, 0.0), liquid2Fraction = 0.5),
            TernaryPoint(listOf(0.4, 0.6, 0.0), liquid1 = listOf(0.99, 0.01, 0.0), liquid2 = listOf(0.01, 0.99, 0.0), liquid2Fraction = 0.6),
            TernaryPoint(listOf(0.0, 0.0, 1.0), liquid1 = listOf(0.0, 0.0, 1.0)),
        ))
        assertEquals(1, ThermoPlots.tieLines(r).size)
    }
}
