/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.units.UnitSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class StreamCopyTest {
    private val feed = SimObject(
        "f", "FEED", ObjectType.MaterialStream,
        specs = mapOf(SpecKey.TEMPERATURE to Spec(300.0, "K"), SpecKey.PRESSURE to Spec(5e6, "Pa"), SpecKey.MOLAR_FLOW to Spec(10.0, "mol/s")),
        composition = mapOf("Methane" to 0.9, "Ethane" to 0.1),
    )
    private val fs = Flowsheet(propertyPackage = "Peng-Robinson (PR)", compounds = listOf("Methane", "Ethane"), objects = listOf(feed))
    private val noResults = ResultView(null, fs.modelHash())

    @Test
    fun aDuplicatedFeedCarriesEverythingAndIsFullySpecified() {
        val (next, copy) = StreamCopy.duplicate(fs, "f", noResults, UnitSet.SI)
        assertEquals(feed.specs, copy.specs)
        assertEquals(feed.composition, copy.composition)
        assertEquals("1", copy.tag)
        assertEquals(2, next.objects.size)
        assertIs<SpecStatus.Complete>(Specification.status(next, copy))
    }

    @Test
    fun chosenValuesReplaceTheTargetsAndLeaveTheRest() {
        val target = SimObject(
            "t", "T", ObjectType.MaterialStream,
            specs = mapOf(SpecKey.VAPOUR_FRACTION to Spec(1.0, "ratio"), SpecKey.PRESSURE to Spec(1e5, "Pa"), SpecKey.MASS_FLOW to Spec(2.0, "kg/s")),
            composition = mapOf("Ethane" to 1.0),
        )
        val two = fs.copy(objects = fs.objects + target)
        val items = StreamCopy.available(two, feed, noResults, UnitSet.SI).filter { it.key in setOf(SpecKey.TEMPERATURE, SpecKey.PRESSURE, StreamCopy.COMPOSITION) }
        val t = StreamCopy.apply(two, "t", items).obj("t")!!
        // Conditions replaced as a set (no VF left beside T and P); the flow and anything not chosen stays.
        assertEquals(setOf(SpecKey.TEMPERATURE, SpecKey.PRESSURE, SpecKey.MASS_FLOW), t.specs.keys)
        assertEquals(feed.composition, t.composition)
    }

    @Test
    fun aCalculatedStreamNeedsCurrentResults() {
        val valve = SimObject("v", "VLV-100", ObjectType.Valve)
        val product = SimObject("p", "2", ObjectType.MaterialStream)
        val withProduct = fs.copy(objects = fs.objects + valve + product, connections = listOf(Connection("f", 0, "v", 0), Connection("v", 0, "p", 0)))
        assertFailsWith<EditRejected> { StreamCopy.duplicate(withProduct, "p", ResultView(null, withProduct.modelHash()), UnitSet.SI) }
        assertFailsWith<EditRejected> { StreamCopy.apply(withProduct, "p", StreamCopy.available(withProduct, feed, noResults, UnitSet.SI)) }
    }
}
