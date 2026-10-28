/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ValveLibraryTest {
    private fun valve(fl: Double?, xt: Double?) = SimObject("v", "FV-100", ObjectType.Valve, specs = buildMap {
        fl?.let { put(SpecKey.VALVE_FL, Spec(it, "ratio")) }
        xt?.let { put(SpecKey.VALVE_XT, Spec(it, "ratio")) }
    })

    @Test
    fun theValveTypeFollowsItsFactors() {
        assertEquals("globe", ValveLibrary.of(valve(null, null))?.id)
        assertEquals("ballStandard", ValveLibrary.of(valve(0.55, 0.15))?.id)
        // A vendor's own figures are a custom valve.
        assertNull(ValveLibrary.of(valve(0.58, 0.15)))
        // The factors are IEC 60534's ranges.
        ValveLibrary.TYPES.forEach { assert(it.fl in 0.5..1.0 && it.xt in 0.1..1.0) }
    }
}
