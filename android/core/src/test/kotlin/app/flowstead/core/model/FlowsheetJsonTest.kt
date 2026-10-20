/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlowsheetJsonTest {
    @Test
    fun roundTripsAndUsesSnakeCaseTypes() {
        val fs = Flowsheet(
            propertyPackage = "Peng-Robinson (PR)",
            compounds = listOf("Methane", "Ethane", "Propane"),
            objects = listOf(
                SimObject(
                    "o1", "FEED", ObjectType.MaterialStream, 10.0, 20.0,
                    specs = mapOf("temperature" to Spec(233.15, "C")),
                    composition = mapOf("Methane" to 0.8, "Ethane" to 0.15, "Propane" to 0.05),
                ),
                SimObject("o2", "V-100", ObjectType.ThreePhaseSeparator),
            ),
            connections = listOf(Connection("o1", 0, "o2", 0)),
        )
        val json = FlowsheetJson.encodeToString(Flowsheet.serializer(), fs)
        assertTrue("\"three_phase_separator\"" in json)
        assertEquals(fs, FlowsheetJson.decodeFromString(Flowsheet.serializer(), json))
    }

    @Test
    fun ignoresUnknownKeysFromNewerVersions() {
        val json = """{"schemaVersion":1,"futureField":true,"objects":[]}"""
        assertEquals(Flowsheet.EMPTY, FlowsheetJson.decodeFromString(Flowsheet.serializer(), json))
    }
}
