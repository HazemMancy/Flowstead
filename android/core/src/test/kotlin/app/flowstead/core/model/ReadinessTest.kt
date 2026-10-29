/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadinessTest {
    private fun feed(id: String, tag: String = id) = SimObject(
        id, tag, ObjectType.MaterialStream,
        specs = mapOf(
            SpecKey.TEMPERATURE to Spec(300.0, "K"), SpecKey.PRESSURE to Spec(5e6, "Pa"), SpecKey.MOLAR_FLOW to Spec(10.0, "mol/s"),
        ),
        composition = mapOf("Methane" to 1.0),
    )

    private fun stream(id: String, tag: String = id) = SimObject(id, tag, ObjectType.MaterialStream)

    private fun fs(objects: List<SimObject>, connections: List<Connection> = emptyList()) =
        Flowsheet(propertyPackage = "Peng-Robinson (PR)", compounds = listOf("Methane"), objects = objects, connections = connections)

    @Test
    fun aLoneEmptyStreamIsNotReadyAndNothingIsSent() {
        val r = Readiness.of(fs(listOf(stream("s", "1"))))
        assertTrue(r.notReady.getValue("s").startsWith("Needs"))
        assertFalse(r.anyReady)
    }

    @Test
    fun anEmptyStreamDoesNotStopTheRest() {
        val r = Readiness.of(fs(listOf(feed("a", "1"), stream("b", "2"))))
        assertEquals(setOf("b"), r.notReady.keys)
        assertEquals(listOf("a"), r.solvable.objects.map { it.id })
    }

    @Test
    fun whatAUnitMissingASpecFeedsWaitsForIt() {
        val valve = SimObject("v", "VLV-100", ObjectType.Valve)
        val r = Readiness.of(
            fs(
                listOf(feed("a", "1"), valve, stream("b", "2"), SimObject("h", "E-100", ObjectType.Heater, specs = mapOf(SpecKey.OUTLET_TEMPERATURE to Spec(350.0, "K"))), stream("c", "3")),
                listOf(Connection("a", 0, "v", 0), Connection("v", 0, "b", 0), Connection("b", 0, "h", 0), Connection("h", 0, "c", 0)),
            ),
        )
        assertTrue(r.notReady.getValue("v").startsWith("Needs outlet pressure or pressure drop"))
        assertEquals("Waits for VLV-100", r.notReady["b"])
        assertEquals("Waits for 2", r.notReady["h"])
        assertEquals("Waits for E-100", r.notReady["c"])
        // The feed is solved on its own; its line to the valve is not sent.
        assertEquals(listOf("a"), r.solvable.objects.map { it.id })
        assertEquals(emptyList(), r.solvable.connections)
    }

    @Test
    fun aReadyChainIsSentWhole() {
        val valve = SimObject("v", "VLV-100", ObjectType.Valve, specs = mapOf(SpecKey.PRESSURE_DROP to Spec(1e5, "Pa")))
        val f = fs(listOf(feed("a"), valve, stream("b"), stream("x", "lonely")), listOf(Connection("a", 0, "v", 0), Connection("v", 0, "b", 0)))
        val r = Readiness.of(f)
        assertEquals(setOf("x"), r.notReady.keys)
        assertEquals(listOf("a", "v", "b"), r.solvable.objects.map { it.id })
        assertEquals(f.connections, r.solvable.connections)
    }

    @Test
    fun anEnergyStreamGoesWithTheUnitItFeeds() {
        val heater = SimObject("h", "E-100", ObjectType.Heater) // no outlet temperature or duty
        val q = SimObject("q", "Q-100", ObjectType.EnergyStream)
        val r = Readiness.of(
            fs(listOf(feed("a"), heater, stream("b"), q), listOf(Connection("a", 0, "h", 0), Connection("h", 0, "b", 0), Connection("q", 0, "h", 1))),
        )
        assertEquals("Waits for E-100", r.notReady["q"])
        assertEquals(listOf("a"), r.solvable.objects.map { it.id })
    }

    @Test
    fun aSetLeftOutStillGivesItsTargetItsValue() {
        // The Set copies the valve's pressure drop to a second valve; its source waits for an empty feed, the target doesn't.
        val v1 = SimObject("v1", "VLV-100", ObjectType.Valve, specs = mapOf(SpecKey.PRESSURE_DROP to Spec(2e5, "Pa")))
        val v2 = SimObject("v2", "VLV-101", ObjectType.Valve)
        val set = SimObject(
            "s", "SET-1", ObjectType.Set,
            refs = mapOf(SetKeys.SOURCE_OBJECT to "v1", SetKeys.TARGET_OBJECT to "v2", SetKeys.VARIABLE to SpecKey.PRESSURE_DROP),
        )
        val r = Readiness.of(
            fs(
                listOf(stream("e", "empty"), v1, stream("o1"), feed("f"), v2, stream("o2"), set),
                listOf(Connection("e", 0, "v1", 0), Connection("v1", 0, "o1", 0), Connection("f", 0, "v2", 0), Connection("v2", 0, "o2", 0)),
            ),
        )
        assertEquals(setOf("e", "v1", "o1", "s"), r.notReady.keys)
        assertEquals("Waits for VLV-100", r.notReady["s"])
        assertEquals(2e5, r.solvable.obj("v2")!!.specs.getValue(SpecKey.PRESSURE_DROP).si)
        assertEquals(listOf("f", "v2", "o2"), r.solvable.objects.map { it.id })
    }

    @Test
    fun blockingProblemsAreOnlyTheFlowsheetWideOnes() {
        assertEquals(emptyList(), Specification.blocking(fs(listOf(stream("s")))))
        assertEquals(listOf("Choose a property package", "Add components"), Specification.blocking(Flowsheet(objects = listOf(stream("s")))).map { it.second })
    }
}
