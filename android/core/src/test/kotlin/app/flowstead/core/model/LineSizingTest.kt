/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.api.ObjectResult
import app.flowstead.core.api.ObjectStatus
import app.flowstead.core.api.SolveResult
import app.flowstead.core.api.SolveStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LineSizingTest {
    private val inch = 0.0254

    @Test
    fun asmeDimensionsMatchTheStandards() {
        // Well-known B36.10M inside diameters (in): NPS 2, 4, 6, 8, 10 Sch 40; 12 STD and Sch 40; 24 STD; 1 XS; 1/2 Sch 160.
        val cases = listOf(
            Triple("2", "40 / STD / 40S", 2.067), Triple("4", "40 / STD / 40S", 4.026), Triple("6", "40 / STD / 40S", 6.065),
            Triple("8", "40 / STD / 40S", 7.981), Triple("10", "40 / STD / 40S", 10.020), Triple("12", "STD / 40S", 12.000),
            Triple("12", "40", 11.938), Triple("24", "20 / STD", 23.250), Triple("1", "80 / XS / 80S", 0.957),
            Triple("1/2", "160", 0.464), Triple("16", "40 / XS", 15.000),
        )
        for ((nps, sch, id) in cases) {
            val size = PipeSizes.size(nps)!!
            assertEquals(id, size.insideDiameterM(size.schedule(sch)!!) / inch, 5e-4, "NPS $nps $sch")
        }
        assertEquals(8.625, PipeSizes.size("8")!!.outsideDiameterM / inch, 1e-9)
        assertEquals(30, PipeSizes.ALL.size)
        // Walls increase down each size's list, so the dropdown reads thin to thick.
        for (s in PipeSizes.ALL) assertEquals(s.schedules.map { it.wallM }.sorted(), s.schedules.map { it.wallM }, s.nps)
    }

    @Test
    fun pickingASizeSetsBothDiametersAndCustomKeepsTheId() {
        val pipe = SimObject("p", "PIPE-100", ObjectType.PipeSegment)
        var fs = FlowsheetEditing.setPipeSize(Flowsheet(objects = listOf(pipe)), "p", "4", null)
        val o = fs.obj("p")!!
        assertEquals("40 / STD / 40S", o.refs[LineSizing.SCHEDULE]) // Sch 40 by default
        assertEquals(4.026 * inch, o.specs.getValue(SpecKey.INNER_DIAMETER).si, 1e-9)
        assertEquals(4.5 * inch, o.specs.getValue(SpecKey.OUTER_DIAMETER).si, 1e-9)
        fs = FlowsheetEditing.setPipeSize(fs, "p", "4", "160")
        assertEquals(3.438 * inch, fs.obj("p")!!.specs.getValue(SpecKey.INNER_DIAMETER).si, 1e-9)
        fs = FlowsheetEditing.setPipeSize(fs, "p", null, null)
        assertNull(fs.obj("p")!!.refs[LineSizing.NPS])
        assertNull(fs.obj("p")!!.specs[SpecKey.OUTER_DIAMETER])
        assertEquals(3.438 * inch, fs.obj("p")!!.specs.getValue(SpecKey.INNER_DIAMETER).si, 1e-9)
    }

    @Test
    fun sizingCriteriaDoNotChangeTheModelHash() {
        val fs = Flowsheet(compounds = listOf("Water"))
        val withCriteria = fs.copy(sizingCriteria = SizingCriteria(liquid = LineCriteria(maxVelocity = Spec(3.0, "m/s"))))
        assertEquals(fs.modelHash(), withCriteria.modelHash())
        assertFalse("sizingCriteria" in FlowsheetJson.encodeToString(Flowsheet.serializer(), fs))
        val back = FlowsheetJson.decodeFromString(Flowsheet.serializer(), FlowsheetJson.encodeToString(Flowsheet.serializer(), withCriteria))
        assertEquals(withCriteria, back)
    }

    @Test
    fun erosionalLimitIsApiRp14eInSiUnits() {
        // C = 100: v_e = 100 / √ρ ft/s with ρ in lb/ft³ ⇔ ρv² = 10 000 lb/ft·s² = 14 881.6 kg/m·s².
        assertEquals(14_881.64, LineSizing.erosionalMomentumFlux(100.0), 0.01)
        // Water at 62.4 lb/ft³: v_e = 100 / √62.4 = 12.66 ft/s = 3.858 m/s; ρv² there = 999.5 × 3.858².
        val rho = 62.4 * 16.01846337
        val ve = 100 / Math.sqrt(62.4) * 0.3048
        assertEquals(rho * ve * ve, LineSizing.erosionalMomentumFlux(100.0), 0.5)
    }

    private fun solved(vfIn: Double, vfOut: Double, pipe: Map<String, Double>) = ResultView(
        StoredSolve(
            SolveResult(
                SolveStatus.Converged,
                objects = mapOf(
                    "in" to ObjectResult(ObjectStatus.Solved, properties = mapOf("vapourFraction" to vfIn)),
                    "out" to ObjectResult(ObjectStatus.Solved, properties = mapOf("vapourFraction" to vfOut)),
                    "p" to ObjectResult(ObjectStatus.Solved, properties = pipe),
                ),
            ),
            "h", 0,
        ),
        "h",
    )

    private val line = Flowsheet(
        objects = listOf(
            SimObject("in", "1", ObjectType.MaterialStream), SimObject("p", "PIPE", ObjectType.PipeSegment), SimObject("out", "2", ObjectType.MaterialStream),
        ),
        connections = listOf(Connection("in", 0, "p", 0), Connection("p", 0, "out", 0)),
    )

    @Test
    fun serviceFollowsTheSolvedStreamsUnlessChosen() {
        val pipe = line.obj("p")!!
        assertEquals(LineService.Gas, LineSizing.service(line, pipe, solved(1.0, 1.0, emptyMap())))
        assertEquals(LineService.Liquid, LineSizing.service(line, pipe, solved(0.0, 0.0, emptyMap())))
        assertEquals(LineService.TwoPhase, LineSizing.service(line, pipe, solved(0.0, 0.02, emptyMap())))
        assertNull(LineSizing.service(line, pipe, ResultView(null, "h")))
        assertEquals(LineService.Liquid, LineSizing.service(line, pipe.copy(refs = mapOf(LineSizing.SERVICE to "liquid")), solved(1.0, 1.0, emptyMap())))
    }

    @Test
    fun checksCompareEngineResultsWithTheLimitsThatAreSet() {
        val view = solved(
            0.0, 0.0,
            mapOf("maxVelocity" to 3.5, "inletVelocity" to 3.4, "outletVelocity" to 3.5, "maxMomentumFlux" to 12_000.0, "frictionPressureGradient" to 300.0),
        )
        val c = LineCriteria(
            maxVelocity = Spec(3.0, "m/s"), minVelocity = Spec(1.0, "m/s"), maxFrictionGradient = Spec(400.0, "kPa/100m"),
            erosionalC = Spec(100.0, "ratio"), maxMach = Spec(0.3, "ratio"),
        )
        val checks = LineSizing.checks(c, "p", view).associateBy { it.label }
        assertFalse(checks.getValue("Maximum velocity").passes)
        assertTrue(checks.getValue("Lowest velocity (inlet or outlet)").passes)
        assertEquals(3.4, checks.getValue("Lowest velocity (inlet or outlet)").value)
        assertTrue(checks.getValue("ΔP per length (friction)").passes)
        val erosion = checks.getValue("Velocity / erosional velocity")
        assertEquals(Math.sqrt(12_000.0 / 14_881.64), erosion.value, 1e-4)
        assertTrue(erosion.passes)
        // No Mach number from the engine (liquid line): that criterion has nothing to compare and is skipped.
        assertFalse("Maximum Mach number" in checks)
        assertEquals(4, checks.size)
        assertTrue(LineSizing.checks(LineCriteria(), "p", view).isEmpty())
    }
}
