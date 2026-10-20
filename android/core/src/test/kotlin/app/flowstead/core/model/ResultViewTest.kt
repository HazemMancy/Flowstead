/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.api.ObjectResult
import app.flowstead.core.api.ObjectStatus
import app.flowstead.core.api.Phases
import app.flowstead.core.api.SolveResult
import app.flowstead.core.api.SolveStatus
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ResultViewTest {
    private val fixture: SolveResult by lazy {
        val dir = System.getProperty("flowstead.sharedDir") ?: error("flowstead.sharedDir not set")
        FlowsheetJson.decodeFromString(SolveResult.serializer(), File(dir, "fixtures/solve-result-letdown.json").readText())
    }

    @Test
    fun decodesARealEngineResponse() {
        assertEquals(SolveStatus.Converged, fixture.status)
        assertEquals("9.0.5", fixture.dwsimVersion)
        val lp = fixture.objects.getValue("lp")
        assertEquals(ObjectStatus.Solved, lp.status)
        assertEquals(1.5e6, lp.properties.getValue("pressure"), 1e-6)
        assertTrue(lp.properties.getValue("temperature") < 233.15) // JT cooling across the valve
        assertTrue(fixture.objects.getValue("v").properties.containsKey("pressureDrop"))
    }

    @Test
    fun valuesComeOnlyFromTheEngine() {
        val view = ResultView(StoredSolve(fixture, "h", 0), "h")
        assertIs<DisplayValue.Calculated>(view.value("lp", "temperature")).also { assertEquals(false, it.stale) }
        assertEquals(DisplayValue.Missing("Not returned by the engine"), view.value("lp", "noSuchKey"))
        assertEquals(DisplayValue.Missing("Added after the last solve"), view.value("new-object", "temperature"))
        assertEquals(listOf(Phases.OVERALL, Phases.VAPOUR, Phases.LIQUID1), view.presentPhases("feed"))
        assertEquals(DisplayValue.Missing("Aqueous / 2nd liquid phase not present"), view.phaseValue("feed", Phases.LIQUID2, "density"))
        assertIs<DisplayValue.Calculated>(view.composition("feed", Phases.VAPOUR, "Methane"))
    }

    @Test
    fun staleWhenTheFlowsheetChanged() {
        val view = ResultView(StoredSolve(fixture, "old", 0), "new")
        assertTrue(view.stale)
        assertEquals(true, assertIs<DisplayValue.Calculated>(view.value("lp", "pressure")).stale)
    }

    @Test
    fun errorsAndUnsolvedStates() {
        val r = SolveResult(
            SolveStatus.Failed,
            objects = mapOf(
                "a" to ObjectResult(ObjectStatus.Error, error = "Flash did not converge"),
                "b" to ObjectResult(ObjectStatus.NotSolved),
            ),
        )
        val view = ResultView(StoredSolve(r, "h", 0), "h")
        assertEquals(DisplayValue.Error("Flash did not converge"), view.value("a", "temperature"))
        assertEquals(DisplayValue.Missing("Not calculated"), view.value("b", "temperature"))
        assertEquals(DisplayValue.Missing("Not solved yet"), ResultView.NONE.value("a", "temperature"))
        val timeout = ResultView(StoredSolve(SolveResult(SolveStatus.Timeout), "h", 0), "h")
        assertEquals(DisplayValue.Error("Solve timed out"), timeout.value("a", "temperature"))
    }

    @Test
    fun modelHashTracksContent() {
        val a = Flowsheet(compounds = listOf("Methane"))
        assertEquals(a.modelHash(), a.copy().modelHash())
        assertNotEquals(a.modelHash(), a.copy(compounds = listOf("Ethane")).modelHash())
    }
}
