/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReactionsTest {
    private val combustion = Reaction(
        "comb", "Methane combustion", ReactionType.Conversion,
        mapOf("Methane" to -1.0, "Oxygen" to -2.0, "Carbon dioxide" to 1.0, "Water" to 2.0), "Methane", conversion = 0.9,
    )

    @Test
    fun flowsheetsWithoutReactionsKeepTheirJsonAndHash() {
        val fs = Flowsheet(compounds = listOf("Methane"))
        val json = FlowsheetJson.encodeToString(Flowsheet.serializer(), fs)
        assertFalse("reactions" in json)
        val withOne = fs.copy(reactions = listOf(combustion))
        val back = FlowsheetJson.decodeFromString(Flowsheet.serializer(), FlowsheetJson.encodeToString(Flowsheet.serializer(), withOne))
        assertEquals(withOne, back)
        assertTrue("\"type\":\"conversion\"" in FlowsheetJson.encodeToString(Flowsheet.serializer(), withOne))
    }

    @Test
    fun equationReadsLikeAChemist() {
        assertEquals("Methane + 2 Oxygen → Carbon dioxide + 2 Water", combustion.equation())
    }

    @Test
    fun deletingAReactionTakesItOutOfItsReactors() {
        val r = SimObject("r", "CRV-100", ObjectType.ConversionReactor, refs = mapOf(ReactorKeys.REACTIONS to "comb,other"))
        val fs = FlowsheetEditing.deleteReaction(Flowsheet(objects = listOf(r), reactions = listOf(combustion)), "comb")
        assertEquals(emptyList(), fs.reactions)
        assertEquals("other", fs.obj("r")!!.refs[ReactorKeys.REACTIONS])
    }

    @Test
    fun reactorNeedsReactionsOfItsKind() {
        val comps = listOf("Methane", "Oxygen", "Carbon dioxide", "Water")
        fun st(type: ObjectType, refs: Map<String, String>, specs: Map<String, Spec> = emptyMap()): SpecStatus {
            val r = SimObject("r", "R-100", type, refs = refs, specs = specs)
            return Specification.status(Flowsheet(compounds = comps, objects = listOf(r), reactions = listOf(combustion)), r)
        }
        assertTrue("conversion reactions" in assertIs<SpecStatus.UnderSpecified>(st(ObjectType.ConversionReactor, emptyMap())).needs)
        assertIs<SpecStatus.Invalid>(st(ObjectType.Cstr, mapOf(ReactorKeys.REACTIONS to "comb"), mapOf(SpecKey.VOLUME to Spec(1.0, "m3"))))
        assertTrue("outlet temperature" in assertIs<SpecStatus.UnderSpecified>(
            st(ObjectType.ConversionReactor, mapOf(ReactorKeys.REACTIONS to "comb", ReactorKeys.MODE to "outlet_temperature")),
        ).needs)
        assertTrue("volume" in assertIs<SpecStatus.UnderSpecified>(st(ObjectType.Cstr, emptyMap())).needs)
    }
}
