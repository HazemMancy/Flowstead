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
}
