/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.core.model

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
