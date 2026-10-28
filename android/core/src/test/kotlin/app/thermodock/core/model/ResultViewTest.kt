/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.core.model

import app.thermodock.core.api.ObjectResult
import app.thermodock.core.api.ObjectStatus
import app.thermodock.core.api.Phases
import app.thermodock.core.api.SolveResult
import app.thermodock.core.api.SolveStatus
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ResultViewTest {
    private val fixture: SolveResult by lazy {
        val dir = System.getProperty("thermodock.sharedDir") ?: error("thermodock.sharedDir not set")
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
        // The liquid density method changes results; unset, it is not written, so older flowsheets keep their hash.
        assertNotEquals(a.modelHash(), a.copy(liquidDensity = LiquidDensityMethods.COSTALD).modelHash())
        assertNotEquals(a.modelHash(), a.copy(volumeShift = VolumeShiftMethods.NONE).modelHash())
        assertTrue("liquidDensity" !in FlowsheetJson.encodeToString(Flowsheet.serializer(), a))
        assertTrue("volumeShift" !in FlowsheetJson.encodeToString(Flowsheet.serializer(), a))
        assertNotEquals(a.modelHash(), a.copy(vapourViscosity = VapourViscosityMethods.LGE).modelHash())
        assertTrue("vapourViscosity" !in FlowsheetJson.encodeToString(Flowsheet.serializer(), a))
    }

    @Test
    fun unitResultsIncludeEverythingTheEngineReturnedAndItsStreams() {
        val fs = Flowsheet(
            objects = listOf(
                SimObject("a", "FEED", ObjectType.MaterialStream),
                SimObject("v", "VLV-100", ObjectType.Valve),
                SimObject("b", "LP", ObjectType.MaterialStream),
            ),
            connections = listOf(Connection("a", 0, "v", 0), Connection("v", 0, "b", 0)),
        )
        val r = SolveResult(
            SolveStatus.Converged,
            objects = mapOf("v" to ObjectResult(ObjectStatus.Solved, properties = mapOf("pressureDrop" to 1e5, "duty" to 0.0, "somethingNew" to 1.0))),
        )
        val view = ResultView(StoredSolve(r, "h", 0), "h")
        val keys = ResultKeys.allForObject(fs, fs.objects[1], view)
        assertEquals(ResultKeys.forType(ObjectType.Valve) + "duty", keys) // unlabelled keys stay hidden
        assertEquals(listOf("IN · FEED", "OUT · LP"), UnitStreams.of(fs, "v").map { it.header })
    }

    @Test
    fun unitMessagesBelongToTheirUnit() {
        val r = SolveResult(
            SolveStatus.Converged,
            messages = listOf(
                app.thermodock.core.api.SolverMessage(app.thermodock.core.api.MessageLevel.Info, "free water", null),
                app.thermodock.core.api.SolverMessage(app.thermodock.core.api.MessageLevel.Warning, "K-100: liquid in the feed", "k"),
                app.thermodock.core.api.SolverMessage(app.thermodock.core.api.MessageLevel.Info, "V-100: no liquid", "v"),
            ),
        )
        val view = ResultView(StoredSolve(r, "h", 0), "h")
        assertEquals(listOf("K-100: liquid in the feed"), view.warnings("k").map { it.text })
        assertTrue(view.warnings("v").isEmpty())
        assertEquals(1, view.messages("v").size)
    }
}
