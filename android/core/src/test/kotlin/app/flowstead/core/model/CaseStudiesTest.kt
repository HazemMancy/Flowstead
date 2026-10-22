/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.api.MessageLevel
import app.flowstead.core.api.ObjectResult
import app.flowstead.core.api.ObjectStatus
import app.flowstead.core.api.SolveResult
import app.flowstead.core.api.SolveStatus
import app.flowstead.core.api.SolverMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CaseStudiesTest {
    private val feed = SimObject("f", "FEED", ObjectType.MaterialStream, specs = mapOf(SpecKey.TEMPERATURE to Spec(300.0, "C"), SpecKey.PRESSURE to Spec(5e6, "bara")))
    private val fs = Flowsheet(objects = listOf(feed))
    private val study = CaseStudy("c", "T sweep", "f", SpecKey.TEMPERATURE, 280.0, 320.0, 4, listOf(CaseOutput("f", "vapourFraction")))

    @Test
    fun checksAndStepsTheRange() {
        assertNull(CaseStudies.problem(fs, study))
        assertEquals(listOf(280.0, 290.0, 300.0, 310.0, 320.0), CaseStudies.points(study))
        assertEquals("Choose the object to vary", CaseStudies.problem(fs, study.copy(objectId = null)))
        assertEquals("The range needs two different values", CaseStudies.problem(fs, study.copy(to = 280.0)))
        assertEquals("Choose at least one result to record", CaseStudies.problem(fs, study.copy(outputs = emptyList())))
        // Only a specification the object has can be varied.
        assertEquals("Choose which of FEED's specifications to vary", CaseStudies.problem(fs, study.copy(specKey = SpecKey.MOLAR_FLOW)))
    }

    @Test
    fun eachPointKeepsTheUnitAndRecordsOnlyEngineNumbers() {
        val at = CaseStudies.at(fs, study, 310.0).objects.single().specs.getValue(SpecKey.TEMPERATURE)
        assertEquals(Spec(310.0, "C"), at)
        val ok = SolveResult(SolveStatus.Converged, objects = mapOf("f" to ObjectResult(ObjectStatus.Solved, properties = mapOf("vapourFraction" to 0.4))))
        assertEquals(listOf(0.4) to null, CaseStudies.read(fs, study, ok))
        val failed = SolveResult(SolveStatus.Failed, messages = listOf(SolverMessage(MessageLevel.Error, "Flash failed")))
        assertEquals(listOf(null) to "Flash failed", CaseStudies.read(fs, study, failed))
    }

    @Test
    fun plotsLeaveGapsWhereThereIsNoValue() {
        val withResults = study.copy(results = CaseResults(fs.modelHash(), listOf(280.0, 290.0, 300.0), listOf(listOf(0.1, null, 0.3)), listOf(null, "x", null)))
        val plot = CaseStudies.plots(fs, withResults).single()
        assertEquals(listOf(280.0, 300.0), plot.series.single().x)
    }

    @Test
    fun editingWhatItVariesDropsOldResultsButRenamingKeepsThem() {
        val results = CaseResults("h", listOf(1.0, 2.0), listOf(listOf(1.0, 2.0)), listOf(null, null))
        val saved = FlowsheetEditing.setCaseResults(FlowsheetEditing.upsertCaseStudy(fs, study), "c", results)
        assertEquals(results, FlowsheetEditing.upsertCaseStudy(saved, study.copy(name = "Renamed")).caseStudies.single().results)
        assertNull(FlowsheetEditing.upsertCaseStudy(saved, study.copy(steps = 8)).caseStudies.single().results)
        // Case studies change no result.
        assertEquals(fs.modelHash(), saved.modelHash())
    }
}
