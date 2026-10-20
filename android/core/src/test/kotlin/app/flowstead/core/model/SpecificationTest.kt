/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SpecificationTest {
    private val repo = File(System.getProperty("flowstead.sharedDir") ?: error("flowstead.sharedDir not set")).parentFile

    /** Every benchmark the engine solves must also pass the app's own checks: they mirror the validator. */
    @Test
    fun everyBenchmarkIsCompleteForTheApp() {
        val cases = File(repo, "benchmarks").listFiles()!!.filter { File(it, "input.json").isFile }
        assertTrue(cases.size >= 9)
        for (case in cases) {
            val root = FlowsheetJson.parseToJsonElement(File(case, "input.json").readText()).jsonObject
            val fs = FlowsheetJson.decodeFromJsonElement(Flowsheet.serializer(), root.getValue("flowsheet"))
            assertEquals(emptyList(), Specification.problems(fs), case.name)
        }
    }

    private fun pipe(vararg specs: Pair<String, Double>) = SimObject(
        "p", "PIPE-100", ObjectType.PipeSegment, specs = specs.associate { (k, v) -> k to Spec(v, "m") },
    )

    private fun status(o: SimObject): SpecStatus {
        val fs = Flowsheet(
            objects = listOf(SimObject("a", "1", ObjectType.MaterialStream), o, SimObject("b", "2", ObjectType.MaterialStream)),
            connections = listOf(Connection("a", 0, o.id, 0), Connection(o.id, 0, "b", 0)),
        )
        return Specification.status(fs, o)
    }

    @Test
    fun pipeNeedsLengthAndDiameterAndBothHeatTransferInputs() {
        assertEquals(listOf("length", "inside diameter"), assertIs<SpecStatus.UnderSpecified>(status(pipe())).needs)
        assertEquals(SpecStatus.Complete, status(pipe(SpecKey.LENGTH to 100.0, SpecKey.INNER_DIAMETER to 0.1)))
        val withU = pipe(SpecKey.LENGTH to 100.0, SpecKey.INNER_DIAMETER to 0.1, SpecKey.HEAT_TRANSFER_COEFFICIENT to 5.0)
        assertEquals(listOf("ambient temperature"), assertIs<SpecStatus.UnderSpecified>(status(withU)).needs)
        assertIs<SpecStatus.Invalid>(status(pipe(SpecKey.LENGTH to 100.0, SpecKey.INNER_DIAMETER to 0.1, SpecKey.INCREMENTS to 2.5)))
        assertIs<SpecStatus.Invalid>(status(pipe(SpecKey.LENGTH to -1.0, SpecKey.INNER_DIAMETER to 0.1)))
    }

    @Test
    fun orificeMustBeSmallerThanThePipe() {
        fun plate(d: Double) = SimObject(
            "o", "FO-100", ObjectType.OrificePlate,
            specs = mapOf(SpecKey.ORIFICE_DIAMETER to Spec(d, "mm"), SpecKey.PIPE_DIAMETER to Spec(0.1, "mm")),
        )
        assertEquals(SpecStatus.Complete, status(plate(0.06)))
        assertIs<SpecStatus.Invalid>(status(plate(0.1)))
        assertEquals(
            listOf("orifice diameter", "pipe inside diameter"),
            assertIs<SpecStatus.UnderSpecified>(status(SimObject("o", "FO-100", ObjectType.OrificePlate))).needs,
        )
    }

    @Test
    fun aSetSuppliesItsTargetsSpecAndChecksTheVariable() {
        val ac = SimObject("ac", "AC-100", ObjectType.AirCooler, specs = mapOf(SpecKey.OUTLET_TEMPERATURE to Spec(318.15, "C")))
        val e = SimObject("e", "E-101", ObjectType.Cooler)
        fun set(key: String) = SimObject(
            "s", "SET-1", ObjectType.Set,
            refs = mapOf(SetKeys.SOURCE_OBJECT to "ac", SetKeys.TARGET_OBJECT to "e", SetKeys.VARIABLE to key),
            specs = mapOf(SetKeys.OFFSET to Spec(5.0, "dC")),
        )
        val base = Flowsheet(objects = listOf(ac, e))
        assertEquals(listOf("outlet temperature or duty"), (assertIs<SpecStatus.UnderSpecified>(Specification.status(base, e))).needs.filterNot { it.endsWith("stream") })
        val withSet = base.copy(objects = base.objects + set(SpecKey.OUTLET_TEMPERATURE))
        assertEquals(323.15, Specification.withSets(withSet).obj("e")!!.specs.getValue(SpecKey.OUTLET_TEMPERATURE).si, 1e-9)
        assertEquals(SpecStatus.Complete, Specification.set(withSet, withSet.obj("s")!!))
        val wrong = base.copy(objects = base.objects + set(SpecKey.OUTLET_PRESSURE))
        assertIs<SpecStatus.Invalid>(Specification.status(wrong, wrong.obj("s")!!))
    }

    @Test
    fun componentSplitterNeedsFractionsOfKnownCompounds() {
        fun cs(vararg splits: Pair<String, Double>) = SimObject(
            "x", "X-100", ObjectType.ComponentSplitter, specs = splits.associate { (c, f) -> SpecKey.split(c) to Spec(f, "frac") },
        )
        val fs = Flowsheet(compounds = listOf("Methane", "Carbon dioxide"))
        fun st(o: SimObject) = Specification.status(fs.copy(objects = listOf(o)), o)
        assertTrue("fraction of each component to the overhead" in assertIs<SpecStatus.UnderSpecified>(st(cs())).needs)
        assertIs<SpecStatus.Invalid>(st(cs("Carbon dioxide" to 1.2)))
        assertIs<SpecStatus.Invalid>(st(cs("Water" to 0.5)))
        assertEquals("Carbon dioxide to overhead", SpecKey.label(SpecKey.split("Carbon dioxide")))
    }
}
