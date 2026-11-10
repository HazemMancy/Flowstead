/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EquipmentSizingTest {
    @Test
    fun lmtdAndTheOneTwoShellF() {
        assertEquals(20.0, EquipmentSizing.lmtd(20.0, 20.0)!!, 1e-12)
        assertEquals((40.0 - 20.0) / kotlin.math.ln(2.0), EquipmentSizing.lmtd(40.0, 20.0)!!, 1e-12)
        // Kern's classic case: hot 200 → 100, cold 50 → 100 (R 2, P 1/3): F ≈ 0.80 in one shell.
        val f1 = EquipmentSizing.f(200.0, 100.0, 50.0, 100.0, 1)!!
        assertEquals(0.80, f1, 0.01)
        // Two shells in series do better.
        assertTrue(EquipmentSizing.f(200.0, 100.0, 50.0, 100.0, 2)!! > f1)
        // A temperature cross in one shell needs more shells.
        assertNull(EquipmentSizing.f(150.0, 60.0, 40.0, 120.0, 1))
        val (n, f) = EquipmentSizing.shells(150.0, 60.0, 40.0, 120.0)!!
        assertTrue(n > 1 && f >= 0.75)
    }

    @Test
    fun anAirCoolerTakesTheCrossflowF() {
        // Process 120 → 60 °C, 2 MW, against air 35 → 50 °C (the library's 15 K rise), U 500 W/m²·K, one tube pass.
        val o = SimObject("e", "AC-100", ObjectType.AirCooler, equipmentSizing = EquipmentSizingInput(u = 500.0))
        val fs = Flowsheet(compounds = listOf("Water"), objects = listOf(SimObject("a", "IN", ObjectType.MaterialStream), o, SimObject("b", "OUT", ObjectType.MaterialStream)),
            connections = listOf(Connection("a", 0, "e", 0), Connection("e", 0, "b", 0)))
        fun st(t: Double) = app.flowstead.core.api.ObjectResult(app.flowstead.core.api.ObjectStatus.Solved, properties = mapOf("temperature" to t))
        val solve = app.flowstead.core.api.SolveResult(app.flowstead.core.api.SolveStatus.Converged, objects = mapOf(
            "a" to st(393.15), "b" to st(333.15),
            "e" to app.flowstead.core.api.ObjectResult(app.flowstead.core.api.ObjectStatus.Solved, properties = mapOf("duty" to -2e6))))
        val view = ResultView(StoredSolve(solve, fs.modelHash(), 0), fs.modelHash())
        // Hand: P = 60/85, R = 15/60 on the process side. Invert Nusselt's one-pass series for the NTU (bisection, here),
        // F = counter-current NTU / that NTU, A = Q/(U·F·LMTD) with LMTD over (120 − 50) and (60 − 35).
        val p = 60.0 / 85
        val r = 0.25
        var lo = 0.0
        var hi = 20.0
        repeat(200) { val m = (lo + hi) / 2; if (CrossflowF.onePassSeries(m, r) < p) lo = m else hi = m }
        val f = kotlin.math.ln((1 - r * p) / (1 - p)) / (1 - r) / lo
        val lm = (70.0 - 25.0) / kotlin.math.ln(70.0 / 25.0)
        val res = EquipmentSizing.size(fs, view, o, o.equipmentSizing!!)!!
        assertEquals(f, res.lines.first { it.label.startsWith("F (crossflow, 1 tube pass") }.si!!, 1e-5)
        assertEquals(2e6 / (500 * f * lm), res.lines.first { it.label == "Bare-tube area" }.si!!, 1e-3)
        // More passes: a higher F, a smaller bundle.
        val two = EquipmentSizing.size(fs, view, o, EquipmentSizingInput(u = 500.0, tubePasses = 2))!!
        assertTrue(two.lines.first { it.label.startsWith("F (crossflow, 2 tube passes") }.si!! > f)
    }

    @Test
    fun driversTakeTheApiMarginAndTheNextIecMotor() {
        assertEquals(1.25, DataLibrary.pumpMargin(10e3))
        assertEquals(1.15, DataLibrary.pumpMargin(30e3))
        assertEquals(1.10, DataLibrary.pumpMargin(100e3))
        assertEquals(15e3, DataLibrary.motor(12.5e3))
        assertEquals(110e3, DataLibrary.motor(110e3))
        assertNull(DataLibrary.motor(30e6))
    }

    @Test
    fun everyLibraryServiceIsARangeWithAUniqueId() {
        assertEquals(DataLibrary.U_SERVICES.size, DataLibrary.U_SERVICES.map { it.id }.toSet().size)
        assertTrue(DataLibrary.U_SERVICES.all { it.low > 0 && it.high > it.low })
        EquipmentSizing.TYPES.mapNotNull { EquipmentSizing.defaultService(it) }.forEach { assertTrue(DataLibrary.service(it) != null) }
    }
}
