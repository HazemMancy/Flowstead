/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.api.AssayCut
import app.flowstead.core.api.AssayResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** An assay's pseudo-components put in the fluid (D99). */
class AssayTest {
    private val result = AssayResult(
        hypotheticals = listOf(Hypothetical("NBP[A]120*", 393.15, 110.0, 0.75), Hypothetical("NBP[A]250*", 523.15, 200.0, 0.84)),
        composition = mapOf("Propane" to 0.1, "NBP[A]120*" to 0.5, "NBP[A]250*" to 0.4),
        cuts = listOf(AssayCut("NBP[A]120*", 300.0, 450.0, 393.15, 0.75, 110.0, 0.5, 0.5)),
    )

    @Test
    fun theAssayBecomesHypotheticalsAndAStream() {
        val (fs, stream) = FlowsheetEditing.addAssay(Flowsheet(compounds = listOf("Methane")), result, 100.0, 100.0)
        assertEquals(listOf("Methane", "NBP[A]120*", "NBP[A]250*", "Propane"), fs.compounds)
        assertEquals(2, fs.hypotheticals.size)
        assertEquals(ObjectType.MaterialStream, stream.type)
        assertEquals(result.composition, stream.composition)
        // Done again (a re-characterised assay), the hypotheticals are replaced, not doubled.
        val (again, _) = FlowsheetEditing.addAssay(fs, result.copy(hypotheticals = result.hypotheticals.map { it.copy(specificGravity = 0.8) }), 200.0, 100.0)
        assertEquals(2, again.hypotheticals.size)
        assertEquals(0.8, again.hypothetical("NBP[A]120*")!!.specificGravity)
    }

    @Test
    fun aDatabaseCompoundOfTheSameNameIsNotOverwritten() {
        assertFailsWith<EditRejected> { FlowsheetEditing.addAssay(Flowsheet(compounds = listOf("NBP[A]120*")), result, 0.0, 0.0) }
    }

    @Test
    fun aBulkCutHasNoTemperatures() {
        val json = """{"name":"X","normalBoilingPoint":400.0,"specificGravity":0.8,"molecularWeight":120.0,"fraction":1.0,"moleFraction":1.0}"""
        val cut = FlowsheetJson.decodeFromString(AssayCut.serializer(), json)
        assertNull(cut.startTemperature)
    }
}
