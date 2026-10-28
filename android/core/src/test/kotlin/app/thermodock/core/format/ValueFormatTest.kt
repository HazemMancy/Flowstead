/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.core.format

import app.thermodock.core.units.Units
import kotlin.test.Test
import kotlin.test.assertEquals

class ValueFormatTest {
    @Test
    fun matchesBrandBoardExamples() {
        assertEquals("1,250.0", ValueFormat.number(1250.0, 1))
        assertEquals("45.00 °C", ValueFormat.si(318.15, Units.C))
        assertEquals("1,250.00 kgmol/h", ValueFormat.si(1250.0 / 3.6, Units.kgmolPerH))
        assertEquals("0.9624", ValueFormat.si(0.9624, Units.fraction))
        assertEquals("58.31 kg/m³", ValueFormat.si(58.31, Units.kgPerM3))
    }

    @Test
    fun negativesRoundingAndGrouping() {
        assertEquals("-222.614", ValueFormat.number(-222.6143, 3))
        assertEquals("-1,234,567", ValueFormat.number(-1_234_567.4, 0))
        assertEquals("0.12", ValueFormat.number(0.125, 2)) // half-even
        assertEquals("0.000", ValueFormat.number(-0.0001, 3))
    }

    @Test
    fun nonFiniteRendersAsMissingDash() {
        assertEquals(MISSING, ValueFormat.number(Double.NaN, 2))
        assertEquals(MISSING, ValueFormat.si(Double.POSITIVE_INFINITY, Units.C))
    }
}
