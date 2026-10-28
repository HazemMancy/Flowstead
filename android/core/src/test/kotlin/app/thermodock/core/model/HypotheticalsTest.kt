/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HypotheticalsTest {
    private val c10 = Hypothetical("C10*", normalBoilingPoint = 447.3, molecularWeight = 142.28, specificGravity = 0.734)

    @Test
    fun addingAHypotheticalPutsItInTheComponentList() {
        val fs = FlowsheetEditing.upsertHypothetical(Flowsheet(compounds = listOf("Methane")), c10)
        assertEquals(listOf("Methane", "C10*"), fs.compounds)
        assertEquals(c10, fs.hypothetical("C10*"))
        // Editing it keeps one entry.
        val edited = FlowsheetEditing.upsertHypothetical(fs, c10.copy(specificGravity = 0.74))
        assertEquals(1, edited.hypotheticals.size)
        assertEquals(listOf("Methane", "C10*"), edited.compounds)
    }

    @Test
    fun removingItFromTheListDeletesItAndItsFractions() {
        val s = SimObject("s", "1", ObjectType.MaterialStream, composition = mapOf("Methane" to 0.8, "C10*" to 0.2))
        val fs = FlowsheetEditing.upsertHypothetical(Flowsheet(compounds = listOf("Methane"), objects = listOf(s)), c10)
        val after = FlowsheetEditing.setCompounds(fs, listOf("Methane"))
        assertTrue(after.hypotheticals.isEmpty())
        assertEquals(mapOf("Methane" to 0.8), after.obj("s")!!.composition)
        assertEquals(after, FlowsheetEditing.deleteHypothetical(fs, "C10*"))
    }

    @Test
    fun inputsAreCheckedLikeTheEngine() {
        assertNull(c10.problem())
        assertEquals("Give the normal boiling point or the molecular weight", Hypothetical("X", specificGravity = 0.8).problem())
        assertEquals("Specific gravity must be between 0.3 and 1.6", c10.copy(specificGravity = 2.0).problem())
        assertEquals("The critical temperature must be above the normal boiling point", c10.copy(criticalTemperature = 400.0).problem())
        // A database compound already has the name.
        assertFailsWith<EditRejected> {
            FlowsheetEditing.upsertHypothetical(Flowsheet(compounds = listOf("C10*")), Hypothetical("C10*", molecularWeight = 142.0))
        }
    }

    @Test
    fun flowsheetsWithoutHypotheticalsKeepTheirJson() {
        val fs = Flowsheet(compounds = listOf("Methane"))
        assertFalse("hypotheticals" in FlowsheetJson.encodeToString(Flowsheet.serializer(), fs))
        val with = FlowsheetEditing.upsertHypothetical(fs, c10)
        assertEquals(with, FlowsheetJson.decodeFromString(Flowsheet.serializer(), FlowsheetJson.encodeToString(Flowsheet.serializer(), with)))
    }
}
