/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PropertyTablesTest {
    private val fs = Flowsheet(
        objects = listOf(SimObject("s1", "FEED", ObjectType.MaterialStream), SimObject("h", "E-100", ObjectType.Heater)),
    )

    @Test
    fun aTableKeepsOnlyPropertiesTheObjectHasAndMovesWithIt() {
        var f = FlowsheetEditing.setTable(fs, "s1", listOf("temperature", "duty", "pressure", "temperature"))
        assertEquals(listOf("temperature", "pressure"), f.tables.single().keys)
        f = FlowsheetEditing.moveTable(f, "s1", 10.0, -20.0)
        f = FlowsheetEditing.setTable(f, "s1", listOf("massFlow"))
        assertEquals(PropertyTable("s1", listOf("massFlow"), 10.0, -20.0), f.tables.single())
    }

    @Test
    fun noPropertiesOrDeletingTheObjectRemovesTheTable() {
        val f = FlowsheetEditing.setTable(fs, "h", listOf("duty"))
        assertTrue(FlowsheetEditing.setTable(f, "h", emptyList()).tables.isEmpty())
        assertTrue(FlowsheetEditing.delete(f, "h").tables.isEmpty())
    }

    @Test
    fun tablesDoNotChangeTheModelHash() {
        assertEquals(fs.modelHash(), FlowsheetEditing.setTable(fs, "s1", listOf("temperature")).modelHash())
    }

    @Test
    fun rowsShowADashForWhatTheEngineDidNotReturn() {
        val f = FlowsheetEditing.setTable(fs, "s1", listOf("temperature"))
        val rows = PropertyTables.rows(f, f.tables.single(), ResultView(null, f.modelHash()), app.flowstead.core.units.UnitSet.SI)
        assertEquals("—", rows.single().value)
        assertEquals("Temperature", rows.single().label)
    }
}
