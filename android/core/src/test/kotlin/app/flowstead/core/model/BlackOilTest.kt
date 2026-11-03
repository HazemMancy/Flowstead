/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlackOilTest {
    private val oil = BlackOil("Well-1", BlackOil.sgFromApi(35.0), 0.75, 100.0)

    @Test
    fun aBlackOilIsAComponentUntilRemoved() {
        val fs = FlowsheetEditing.upsertBlackOil(Flowsheet(propertyPackage = BlackOil.PACKAGE), oil)
        assertEquals(listOf("Well-1"), fs.compounds)
        assertEquals(35.0, fs.blackOil("Well-1")!!.api, 1e-9)
        val gone = FlowsheetEditing.setCompounds(fs, emptyList())
        assertTrue(gone.blackOils.isEmpty())
    }

    @Test
    fun badInputsAreRefused() {
        assertNotNull(oil.copy(gasOilRatio = -1.0).problem())
        assertNotNull(oil.copy(waterCut = 1.0).problem())
        assertNull(oil.problem())
        assertFailsWith<EditRejected> { FlowsheetEditing.upsertBlackOil(Flowsheet(compounds = listOf("Well-1")), oil) }
    }
}
