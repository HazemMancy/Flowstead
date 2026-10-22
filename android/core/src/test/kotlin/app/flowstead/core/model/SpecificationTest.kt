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

    @Test
    fun columnNeedsTraysFeedTrayPressureAndOneSpecPerEnd() {
        val col = SimObject("t", "T-100", ObjectType.DistillationColumn)
        val fs = Flowsheet(
            compounds = listOf("Propane", "Isobutane"),
            objects = listOf(SimObject("f", "1", ObjectType.MaterialStream), col, SimObject("d", "2", ObjectType.MaterialStream), SimObject("b", "3", ObjectType.MaterialStream)),
            connections = listOf(Connection("f", 0, "t", 0), Connection("t", 0, "d", 0), Connection("t", 1, "b", 0)),
        )
        fun st(vararg specs: Pair<String, Double>, refs: Map<String, String> = emptyMap()): SpecStatus {
            val o = col.copy(specs = specs.associate { (k, v) -> k to Spec(v, "") }, refs = refs)
            return Specification.status(fs.copy(objects = fs.objects.map { if (it.id == "t") o else it }), o)
        }
        assertEquals(
            listOf("top pressure", "number of trays", "feed 1 tray", "condenser specification", "reboiler specification"),
            assertIs<SpecStatus.UnderSpecified>(st()).needs,
        )
        val base = arrayOf(SpecKey.TRAYS to 20.0, SpecKey.feedStage(0) to 10.0, SpecKey.TOP_PRESSURE to 1.4e6, SpecKey.REFLUX_RATIO to 2.5)
        assertEquals(SpecStatus.Complete, st(*base, SpecKey.BOTTOMS_RATE to 15.0))
        assertIs<SpecStatus.OverSpecified>(st(*base, SpecKey.BOTTOMS_RATE to 15.0, SpecKey.BOILUP_RATIO to 2.0))
        assertIs<SpecStatus.Invalid>(st(*base.copyOfRange(0, 3), SpecKey.feedStage(0) to 25.0, SpecKey.REFLUX_RATIO to 2.5, SpecKey.BOTTOMS_RATE to 15.0))
        assertEquals(
            listOf("component for the reboiler specification"),
            assertIs<SpecStatus.UnderSpecified>(st(*base, SpecKey.BOTTOMS_FRACTION to 0.02)).needs,
        )
    }

    @Test
    fun aPumpOnItsCurveNeedsNoPressureSpec() {
        val curve = PerformanceCurve(listOf(CurvePoint(0.01, 80.0, 0.6, 2.0), CurvePoint(0.03, 60.0, 0.75, 4.0)))
        val pump = SimObject("u", "P-100", ObjectType.Pump, curve = curve)
        assertEquals(SpecStatus.Complete, status(pump))
        assertIs<SpecStatus.OverSpecified>(status(pump.copy(specs = mapOf(SpecKey.OUTLET_PRESSURE to Spec(5e5, "kPa")))))
        assertIs<SpecStatus.Invalid>(status(pump.copy(curve = PerformanceCurve(curve.points.take(1)))))
        assertIs<SpecStatus.Invalid>(status(pump.copy(curve = PerformanceCurve(listOf(curve.points[0], curve.points[1].copy(efficiency = null))))))
        // NPSH required belongs to pumps only.
        assertIs<SpecStatus.Invalid>(status(SimObject("u", "K-100", ObjectType.Compressor, curve = curve)))
        assertEquals(SpecStatus.Complete, status(SimObject("u", "K-100", ObjectType.Compressor, curve = PerformanceCurve(curve.points.map { it.copy(npshr = null) }))))
    }

    @Test
    fun settingACurveDropsThePressureSpecs() {
        val pump = SimObject("u", "P-100", ObjectType.Pump, specs = mapOf(SpecKey.PRESSURE_RISE to Spec(2e5, "kPa"), SpecKey.EFFICIENCY to Spec(0.7, "%")))
        val curve = PerformanceCurve(listOf(CurvePoint(0.01, 80.0), CurvePoint(0.03, 60.0)))
        val on = FlowsheetEditing.setCurve(Flowsheet(objects = listOf(pump)), "u", curve).objects.single()
        assertEquals(curve, on.curve)
        assertEquals(setOf(SpecKey.EFFICIENCY), on.specs.keys)
        val json = FlowsheetJson.encodeToString(Flowsheet.serializer(), Flowsheet(objects = listOf(on)))
        assertEquals(on, FlowsheetJson.decodeFromString(Flowsheet.serializer(), json).objects.single())
        assertEquals(null, FlowsheetEditing.setCurve(Flowsheet(objects = listOf(on)), "u", null).objects.single().curve)
    }
}
