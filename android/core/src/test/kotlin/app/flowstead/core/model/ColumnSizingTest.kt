/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ColumnSizingTest {
    @Test
    fun fairsCapacityMatchesHisChart() {
        // Fair's chart as Coulson & Richardson vol. 6 Fig. 11.27 draws it, 0.6 m spacing: about 0.11 m/s at F_LV 0.01, 0.09 at 0.1.
        assertEquals(0.11, ColumnSizing.csbFlood(0.01, 0.6), 0.01)
        assertEquals(0.09, ColumnSizing.csbFlood(0.1, 0.6), 0.01)
        // Wider spacing carries more vapour.
        assertTrue(ColumnSizing.csbFlood(0.1, 0.9144) > ColumnSizing.csbFlood(0.1, 0.4572))
    }

    @Test
    fun theDiameterCarriesTheWorstTrayAtTheDesignFloodFraction() {
        // Condenser, three trays, reboiler; the middle tray carries the most vapour.
        val profile = mapOf(
            "vapourFlow" to listOf(0.0, 100.0, 140.0, 120.0, 110.0),
            "liquidFlow" to listOf(80.0, 80.0, 90.0, 85.0, 30.0),
            "vapourDensity" to List(5) { 30.0 },
            "liquidDensity" to List(5) { 470.0 },
            "vapourMolarMass" to List(5) { 50.0 },
            "liquidMolarMass" to List(5) { 52.0 },
            "surfaceTension" to List(5) { 0.005 },
        )
        val r = assertNotNull(ColumnSizing.size(ObjectType.DistillationColumn, profile))
        assertEquals(3, r.trays.size)
        assertEquals(3, r.controllingStage)
        val t = r.trays.first { it.stage == 3 }
        val wv = 140.0 * 50 / 1000
        val flv = (90.0 * 52 / 1000) / wv * sqrt(30.0 / 470.0)
        assertEquals(flv, t.flowParameter, 1e-12)
        val uf = ColumnSizing.csbFlood(flv, 0.6096) * (5.0 / 20).pow(0.2) * sqrt(440.0 / 30.0)
        assertEquals(uf, t.floodVelocity, 1e-12)
        assertEquals(sqrt(4 * (wv / 30.0 / (0.8 * uf) / 0.88) / PI), r.requiredDiameter, 1e-12)
        assertTrue(r.diameter >= r.requiredDiameter && r.diameter - r.requiredDiameter < 0.05)
        assertEquals(5, r.actualTrays) // 3 / 0.7, rounded up
        assertEquals(4 * 0.6096 + 3.0, r.height, 1e-9)
    }
}
