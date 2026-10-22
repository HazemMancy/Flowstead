/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.api.PhaseEnvelope
import app.flowstead.core.api.ProfileKeys
import app.flowstead.core.units.Quantity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlotsTest {
    @Test
    fun ticksAreRoundAndCoverTheRange() {
        assertEquals(listOf(0.0, 20.0, 40.0, 60.0, 80.0, 100.0), Plots.ticks(3.0, 97.0))
        assertEquals(listOf(0.2, 0.25, 0.3, 0.35), Plots.ticks(0.21, 0.34, 3).map { Math.round(it * 100) / 100.0 })
        val flat = Plots.ticks(5.0, 5.0)
        assertTrue(flat.first() < 5.0 && flat.last() > 5.0)
        assertTrue(Plots.ticks(Double.NaN, 1.0).isEmpty())
    }

    @Test
    fun exchangerGivesATqDiagramAndColumnsTheirStages() {
        val hx = Plots.forObject(
            ObjectType.HeatExchanger,
            mapOf(ProfileKeys.HEAT_FLOW to listOf(0.0, 1e5), ProfileKeys.HOT_TEMPERATURE to listOf(400.0, 350.0), ProfileKeys.COLD_TEMPERATURE to listOf(380.0, 300.0)),
        ).single()
        assertEquals(Quantity.Power, hx.xQuantity)
        assertEquals(listOf("Hot side", "Cold side"), hx.series.map { it.label })

        val column = Plots.forObject(
            ObjectType.DistillationColumn,
            mapOf(ProfileKeys.STAGE to listOf(1.0, 2.0, 3.0), ProfileKeys.TEMPERATURE to listOf(300.0, 320.0, 340.0), ProfileKeys.PRESSURE to listOf(1e6, 1e6, 1.02e6)),
        )
        // No flows from the engine: no flows plot.
        assertEquals(listOf("Temperature", "Pressure"), column.map { it.title })
        // A series whose lengths disagree is dropped, not stretched.
        assertTrue(Plots.forObject(ObjectType.PipeSegment, mapOf(ProfileKeys.LENGTH to listOf(0.0, 1.0), ProfileKeys.PRESSURE to listOf(1e5))).isEmpty())
        assertTrue(Plots.forObject(ObjectType.Valve, mapOf(ProfileKeys.LENGTH to listOf(0.0, 1.0))).isEmpty())
    }

    @Test
    fun envelopeMarksOnlyThePointsTheEngineFound() {
        val env = PhaseEnvelope(
            bubbleTemperature = listOf(200.0, 250.0), bubblePressure = listOf(1e6, 5e6),
            dewTemperature = listOf(260.0, 300.0), dewPressure = listOf(1e5, 3e6),
            cricondenthermTemperature = 300.0, cricondenthermPressure = 3e6,
        )
        val plot = Plots.envelope(env, 280.0, 2e6)!!
        assertEquals(listOf("Cricondentherm", "This stream"), plot.markers.map { it.label })
        assertNull(Plots.envelope(PhaseEnvelope()))
    }
}
