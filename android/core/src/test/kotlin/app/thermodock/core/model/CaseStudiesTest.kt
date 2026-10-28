/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.core.model

import app.thermodock.core.api.MessageLevel
import app.thermodock.core.api.ObjectResult
import app.thermodock.core.api.ObjectStatus
import app.thermodock.core.api.SolveResult
import app.thermodock.core.api.SolveStatus
import app.thermodock.core.api.SolverMessage
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

    @Test
    fun aSecondVariableGivesAFamilyOfCurves() {
        val two = study.copy(steps = 2, objectId2 = "f", specKey2 = SpecKey.PRESSURE, from2 = 4e6, to2 = 6e6, steps2 = 1)
        assertNull(CaseStudies.problem(fs, two))
        // The second variable is the outer loop.
        assertEquals(
            listOf(280.0 to 4e6, 300.0 to 4e6, 320.0 to 4e6, 280.0 to 6e6, 300.0 to 6e6, 320.0 to 6e6),
            CaseStudies.grid(two),
        )
        val at = CaseStudies.at(fs, two, 300.0, 6e6).objects.single().specs
        assertEquals(Spec(6e6, "bara"), at[SpecKey.PRESSURE])
        assertEquals("The second variable must differ from the first", CaseStudies.problem(fs, two.copy(specKey2 = SpecKey.TEMPERATURE)))
        assertEquals("At most 200 points in all: fewer steps, please", CaseStudies.problem(fs, two.copy(steps = 50, steps2 = 10)))

        val results = CaseResults(fs.modelHash(), listOf(280.0, 300.0, 320.0, 280.0, 300.0, 320.0), listOf(listOf(0.1, 0.2, 0.3, 0.0, 0.1, 0.2)),
            List(6) { null }, x2 = listOf(4e6, 4e6, 4e6, 6e6, 6e6, 6e6))
        val plot = CaseStudies.plots(fs, two.copy(results = results)).single()
        assertEquals(listOf(4e6, 6e6), plot.series.map { it.parameter })
        assertEquals("FEED pressure", plot.parameterLabel)
    }
}
