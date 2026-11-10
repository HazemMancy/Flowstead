/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.api.ObjectResult
import app.flowstead.core.api.ObjectStatus
import app.flowstead.core.api.PhaseResult
import app.flowstead.core.api.SolveResult
import app.flowstead.core.api.SolveStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamLineSizingTest {
    private val s = SimObject("s", "WATER", ObjectType.MaterialStream)
    private val fs = Flowsheet(propertyPackage = "Steam Tables (IAPWS-IF97)", compounds = listOf("Water"), objects = listOf(s),
        sizingCriteria = SizingCriteria(liquid = LineCriteria(maxVelocity = Spec(3.0, "m/s"))))
    private val solved = SolveResult(SolveStatus.Converged, objects = mapOf("s" to ObjectResult(ObjectStatus.Solved,
        properties = mapOf("temperature" to 298.15, "pressure" to 10e5, "massFlow" to 20.0, "vapourFraction" to 0.0),
        phases = mapOf("overall" to PhaseResult(composition = mapOf("Water" to 1.0))))))
    private val view = ResultView(StoredSolve(solved, fs.modelHash(), 0), fs.modelHash())

    @Test
    fun theTestPipesCarryTheStreamsOwnStateAndEverySizeOfTheSchedule() {
        assertNull(StreamLineSizing.problem(fs, view, "s"))
        val input = StreamLineSizing.Input(design = true, schedule = "40", totalLength = 2000.0)
        val sizes = StreamLineSizing.candidates(input)
        assertEquals(PipeSizes.ALL.count { p -> p.schedules.any { "40" in it.label.split(" / ") } }, sizes.size)
        // Above NPS 10 schedule 40 is its own wall (NPS 12: 0.406 in, STD 0.375 in): ID 12.75 − 2·0.406 = 11.938 in.
        assertEquals(11.938 * 0.0254, sizes.first { it.nps == "12" }.insideDiameter, 1e-9)
        assertEquals(12.000 * 0.0254, StreamLineSizing.candidates(input.copy(schedule = "STD")).first { it.nps == "12" }.insideDiameter, 1e-9)
        val t = StreamLineSizing.flowsheet(fs, view, "s", input)
        // One 100 m pipe and one of the whole length a size, each fed the stream's T, P, flow and composition.
        assertEquals(sizes.size * 2, t.objects.count { it.type == ObjectType.PipeSegment })
        val feed = t.objects.first { it.id == "g0_in" }
        assertEquals(298.15, feed.specs[SpecKey.TEMPERATURE]!!.si)
        assertEquals(10e5, feed.specs[SpecKey.PRESSURE]!!.si)
        assertEquals(20.0, feed.specs[SpecKey.MASS_FLOW]!!.si)
        assertEquals(mapOf("Water" to 1.0), feed.composition)
        assertEquals(100.0, t.objects.first { it.id == "g0" }.specs[SpecKey.LENGTH]!!.si)
        assertEquals(2000.0, t.objects.first { it.id == "t0" }.specs[SpecKey.LENGTH]!!.si)
        assertEquals("Steam Tables (IAPWS-IF97)", t.propertyPackage)
        // Rating: one size, or the diameter given.
        assertEquals(1, StreamLineSizing.candidates(StreamLineSizing.Input(design = false, nps = "4")).size)
        assertEquals(0.1, StreamLineSizing.candidates(StreamLineSizing.Input(design = false, insideDiameter = 0.1)).single().insideDiameter)
        assertEquals(LineService.Liquid, StreamLineSizing.service(fs, view, "s", null))
    }

    @Test
    fun theSmallestSizeThatMeetsTheCriteriaIsTheOneRecommended() {
        // The engine's answer, made up: velocity falls with size; 3 m/s is the limit.
        val input = StreamLineSizing.Input(design = true, schedule = "40")
        val sizes = StreamLineSizing.candidates(input)
        val ans = SolveResult(SolveStatus.Converged, objects = sizes.indices.associate { i ->
            "g$i" to ObjectResult(ObjectStatus.Solved, properties = mapOf(
                "pressureDrop" to 50_000.0 / (i + 1), "maxVelocity" to 12.0 / (i + 1), "maxMomentumFlux" to 1.0e5 / (i + 1)))
        })
        val o = StreamLineSizing.outcome(fs, view, "s", input, ans)
        assertTrue(o.criteriaSet)
        // 12/(i+1) ≤ 3 first at i = 3.
        assertEquals(sizes[3], o.recommended!!.candidate)
        assertEquals(500.0 / 4, o.rows[3].gradient!!, 1e-9) // Pa/m: 12 500 Pa over 100 m
        assertTrue(o.rows.take(3).none { it.passes })
    }
}
