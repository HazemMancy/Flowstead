/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pipe segments made of runs and fittings (D98). */
class PipeSectionsTest {
    private fun pipe(sections: List<PipeSegmentSection>?, vararg specs: Pair<String, Double>) = SimObject(
        "p", "PIPE-100", ObjectType.PipeSegment, specs = specs.associate { (k, v) -> k to Spec(v, "m") }, pipeSections = sections,
    )

    private fun status(o: SimObject): SpecStatus {
        val fs = Flowsheet(
            objects = listOf(SimObject("a", "1", ObjectType.MaterialStream), o, SimObject("b", "2", ObjectType.MaterialStream)),
            connections = listOf(Connection("a", 0, o.id, 0), Connection(o.id, 0, "b", 0)),
        )
        return Specification.status(fs, o)
    }

    @Test
    fun sectionsReplaceTheLengthAndAreCheckedAsTheEngineDoes() {
        val ok = listOf(PipeSegmentSection(length = 50.0), PipeSegmentSection(fitting = "globe_valve"), PipeSegmentSection(length = 20.0, elevationChange = 5.0))
        assertEquals(SpecStatus.Complete, status(pipe(ok, SpecKey.INNER_DIAMETER to 0.1)))
        assertEquals(listOf("inside diameter", "section 1 length"), assertIs<SpecStatus.UnderSpecified>(status(pipe(listOf(PipeSegmentSection()))) ).needs)
        assertEquals("Section 2: unknown fitting 'plug'", assertIs<SpecStatus.Invalid>(status(pipe(listOf(PipeSegmentSection(length = 1.0), PipeSegmentSection(fitting = "plug")), SpecKey.INNER_DIAMETER to 0.1))).problem)
        assertEquals("Add at least one straight section", assertIs<SpecStatus.Invalid>(status(pipe(listOf(PipeSegmentSection(fitting = "gate_valve")), SpecKey.INNER_DIAMETER to 0.1))).problem)
        assertEquals("Section 1: the elevation change must be smaller than the length", assertIs<SpecStatus.Invalid>(status(pipe(listOf(PipeSegmentSection(length = 5.0, elevationChange = 6.0)), SpecKey.INNER_DIAMETER to 0.1))).problem)
    }

    @Test
    fun turningSectionsOffKeepsTheFirstRun() {
        val fs = Flowsheet(objects = listOf(pipe(null, SpecKey.LENGTH to 80.0, SpecKey.ELEVATION_CHANGE to 2.0, SpecKey.INNER_DIAMETER to 0.1)))
        val on = FlowsheetEditing.setPipeSections(fs, "p", listOf(PipeSegmentSection(length = 80.0, elevationChange = 2.0), PipeSegmentSection(fitting = "elbow_90", count = 4)))
        val o = on.objects.single()
        assertNull(o.specs[SpecKey.LENGTH])
        assertNull(o.specs[SpecKey.ELEVATION_CHANGE])
        assertEquals(2, o.pipeSections!!.size)
        val off = FlowsheetEditing.setPipeSections(on, "p", null).objects.single()
        assertNull(off.pipeSections)
        assertEquals(80.0, off.specs[SpecKey.LENGTH]!!.si)
        assertEquals(2.0, off.specs[SpecKey.ELEVATION_CHANGE]!!.si)
    }

    @Test
    fun theJsonIsTheEnginesShape() {
        val fs = Flowsheet(objects = listOf(pipe(listOf(PipeSegmentSection(length = 10.0), PipeSegmentSection(fitting = "gate_valve", count = 2)), SpecKey.INNER_DIAMETER to 0.1)))
        val json = FlowsheetJson.encodeToJsonElement(Flowsheet.serializer(), fs).jsonObject
        val sections = json["objects"]!!.jsonArray[0].jsonObject["pipeSections"]!!.jsonArray
        assertEquals("10.0", sections[0].jsonObject["length"]!!.jsonPrimitive.content)
        assertTrue("fitting" !in sections[0].jsonObject)
        assertEquals("gate_valve", sections[1].jsonObject["fitting"]!!.jsonPrimitive.content)
        assertEquals("2", sections[1].jsonObject["count"]!!.jsonPrimitive.content)
        // A pipe without sections writes no field, so older projects and their model hash are unchanged.
        val plain = FlowsheetJson.encodeToJsonElement(Flowsheet.serializer(), Flowsheet(objects = listOf(pipe(null, SpecKey.LENGTH to 1.0)))).jsonObject
        assertTrue("pipeSections" !in plain["objects"]!!.jsonArray[0].jsonObject)
    }

    @Test
    fun aSectionedPipeIsProtectedByItsRuns() {
        // 100 m of 0.2 m and 50 m of 0.1 m: one pipe of the same volume and surface (D = 4V/S).
        val o = pipe(listOf(PipeSegmentSection(length = 100.0), PipeSegmentSection(fitting = "gate_valve"), PipeSegmentSection(length = 50.0, innerDiameter = 0.1)), SpecKey.INNER_DIAMETER to 0.2)
        val (g, _) = Safety.pipeGeometry(o)!!
        val v = Math.PI / 4 * (0.2 * 0.2 * 100 + 0.1 * 0.1 * 50)
        val s = Math.PI * (0.2 * 100 + 0.1 * 50)
        assertEquals(4 * v / s, g.diameter, 1e-12)
    }

    @Test
    fun everyFittingIsDescribed() {
        assertEquals(28, PipeFittings.ALL.size)
        assertEquals(PipeFittings.ALL.size, PipeFittings.ALL.map { it.id }.toSet().size)
        assertEquals("L/D 340", PipeFittings.describe(PipeFittings.get("globe_valve")!!))
        assertEquals("K 5.67", PipeFittings.describe(PipeFittings.get("reducer_2_1")!!))
    }
}
