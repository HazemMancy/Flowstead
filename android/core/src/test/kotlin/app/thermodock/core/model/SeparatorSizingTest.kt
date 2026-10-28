/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.core.model

import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeparatorSizingTest {
    // 70 barg gas–condensate: 1 kg/s of gas at 50 kg/m³, 2 kg/s of liquid at 600 kg/m³.
    private val loads = SeparatorLoads(
        pressure = 71.0e5 + 101325.0, gasMassFlow = 1.0, gasDensity = 50.0,
        oilVolumeFlow = 2.0 / 600, oilDensity = 600.0, oilViscosity = 3e-4,
        waterVolumeFlow = 0.0, waterDensity = null, waterViscosity = null,
        feedMassFlow = 3.0, feedDensity = 3.0 / (0.02 + 2.0 / 600),
    )

    @Test
    fun kFallsWithPressureAsGpsa() {
        assertEquals(1.0, SeparatorSizing.pressureFactor(101325.0))
        assertEquals(0.9, SeparatorSizing.pressureFactor(300 * 6894.757 + 101325.0), 1e-9)
        assertEquals(0.75, SeparatorSizing.pressureFactor(300e5))
        val k = SeparatorSizing.k(SeparatorSizingInput(), loads.pressure)
        assertEquals(0.107 * SeparatorSizing.pressureFactor(loads.pressure), k, 1e-12)
        assertEquals(1.25 * k, SeparatorSizing.k(SeparatorSizingInput(horizontal = true), loads.pressure), 1e-12)
    }

    @Test
    fun segmentAreaAndItsInverse() {
        assertEquals(PI / 8, SeparatorSizing.segment(0.5, 1.0), 1e-12)
        assertEquals(PI / 4, SeparatorSizing.segment(1.0, 1.0), 1e-12)
        assertEquals(0.3, SeparatorSizing.height(SeparatorSizing.segment(0.3, 1.0), 1.0), 1e-9)
    }

    @Test
    fun verticalVesselCarriesItsGasBelowTheSoudersBrownVelocityAndHoldsItsLiquid() {
        val r = SeparatorSizing.size(loads, SeparatorSizingInput(), threePhase = false)
        val k = SeparatorSizing.k(SeparatorSizingInput(), loads.pressure)
        assertEquals(k * sqrt((600.0 - 50) / 50), r.maxGasVelocity, 1e-12)
        assertTrue(r.gasVelocity <= r.maxGasVelocity)
        assertEquals(0.0, (r.diameter / 0.1524) % 1.0, 1e-6) // 6 in steps
        val a = PI * r.diameter * r.diameter / 4
        val nll = r.levels.first { it.name.startsWith("Normal") }.height
        val lll = r.levels.first { it.name.startsWith("Low") }.height
        assertEquals(loads.liquidVolumeFlow * 300, (nll - lll) * a, 1e-9) // 5 min hold-up
        // Svrcek & Monnery: at least 1.5; above 6 the result says a horizontal vessel will be shorter.
        assertTrue(r.slenderness >= 1.5)
        assertEquals(r.slenderness > 6, r.warnings.any { "horizontal" in it })
        // Inlet: half-open pipe, ρv² at most 2100 Pa in the chosen bore.
        val inlet = r.nozzles.first()
        val v = loads.feedMassFlow / loads.feedDensity / (PI * inlet.insideDiameter!! * inlet.insideDiameter!! / 4)
        assertTrue(loads.feedDensity * v * v <= 2100.0)
    }

    @Test
    fun horizontalVesselMeetsGasLiquidAndSlenderness() {
        val input = SeparatorSizingInput(horizontal = true)
        val r = SeparatorSizing.size(loads, input, threePhase = false)
        assertTrue(r.gasVelocity <= r.maxGasVelocity)
        assertEquals(5.0, r.length / r.diameter, 0.1 / r.diameter + 1e-9) // above 34.5 barg: L/D 5
        val levels = r.levels.map { it.height }
        assertEquals(levels.sorted(), levels)
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun threePhaseVesselSettlesWaterOutOfTheOil() {
        val wet = loads.copy(waterVolumeFlow = 1.0 / 1000, waterDensity = 1000.0, waterViscosity = 1e-3, feedMassFlow = 4.0)
        val input = SeparatorSizingInput(horizontal = true)
        val r = SeparatorSizing.size(wet, input, threePhase = true)
        assertTrue(r.threePhase)
        val iface = r.levels.first { it.name.startsWith("Oil–water") }.height
        val nll = r.levels.first { it.name.startsWith("Normal") }.height
        val vw = SeparatorSizing.stokes(500e-6, 400.0, 3e-4)
        assertTrue(nll - iface <= vw * input.oilRetention + 1e-6)
        assertTrue(r.nozzles.any { it.name == "Water outlet" })
    }
}
