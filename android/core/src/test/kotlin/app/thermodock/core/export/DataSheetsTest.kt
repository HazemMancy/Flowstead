/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.core.export

import app.thermodock.core.api.SolveResult
import app.thermodock.core.model.DocumentInfo
import app.thermodock.core.model.Flowsheet
import app.thermodock.core.model.FlowsheetEditing
import app.thermodock.core.model.FlowsheetJson
import app.thermodock.core.model.LineCriteria
import app.thermodock.core.model.SheetKeys
import app.thermodock.core.model.SizingCriteria
import app.thermodock.core.model.Spec
import app.thermodock.core.model.SpecKey
import app.thermodock.core.model.StoredSolve
import app.thermodock.core.model.modelHash
import app.thermodock.core.model.obj
import app.thermodock.core.units.UnitSet
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DataSheetsTest {
    private val dir = File(System.getProperty("thermodock.sharedDir") ?: error("thermodock.sharedDir not set"), "fixtures")
    private val fs: Flowsheet = FlowsheetJson.decodeFromString(Flowsheet.serializer(), File(dir, "flowsheet-lines.json").readText())
    private val result: SolveResult = FlowsheetJson.decodeFromString(SolveResult.serializer(), File(dir, "solve-result-lines.json").readText())

    private fun sheet(f: Flowsheet, id: String, solved: Boolean = true) =
        DataSheets.build("Lines", f, if (solved) StoredSolve(result, fs.modelHash(), 0) else null, UnitSet.SI, f.obj(id)!!, "2026-10-21", "test")

    private fun DataSheet.section(title: String) = sections.first { it.title.startsWith(title) }
    private fun DataSheet.Section.line(description: String) = lines.first { it.description == description }

    @Test
    fun pipeSheetCarriesTheEngineHydraulics() {
        val ds = sheet(fs, "pg")
        assertEquals("PIPE SEGMENT DATA SHEET", ds.title)
        assertEquals("GAS LINE", ds.tag)
        assertEquals("Lines", ds.info.project) // falls back to the project name
        val perf = ds.section("PERFORMANCE")
        // 79.13 kPa from the engine, printed in the SI set's kPa.
        assertEquals("79.13", perf.line("Pressure drop").values.single())
        assertEquals("m/s", perf.line("Maximum velocity").unit)
        assertTrue(perf.line("Maximum velocity").values.single().startsWith("2.81"))
        assertEquals("Vapor Only", perf.line("Flow regime(s)").values.single())
        // Inlet and outlet streams are columns of the operating conditions.
        val op = ds.section("OPERATING CONDITIONS")
        assertEquals(listOf("IN · GAS IN", "OUT · GAS OUT"), op.columns)
        assertEquals(listOf("40.00", "39.69"), op.line("Temperature").values)
        // A gas stream has no liquid block.
        assertFalse(op.lines.any { it.description == "LIQUID" })
    }

    @Test
    fun aValueTheEngineDidNotReturnIsADash() {
        val pipe = result.objects.getValue("pg")
        val without = result.copy(objects = result.objects + ("pg" to pipe.copy(properties = pipe.properties - "maxMachNumber")))
        val ds = DataSheets.build("Lines", fs, StoredSolve(without, fs.modelHash(), 0), UnitSet.SI, fs.obj("pg")!!, "2026-10-21", "test")
        assertEquals(DataSheets.MISSING, ds.section("PERFORMANCE").line("Maximum Mach number").values.single())
        val unsolved = sheet(fs, "pg", solved = false)
        assertTrue(unsolved.section("OPERATING CONDITIONS").line("Temperature").values.all { it == DataSheets.MISSING })
        assertTrue(unsolved.notes.any { "not been solved" in it })
    }

    @Test
    fun staleResultsAreFlaggedAndSheetTextDoesNotMakeThemStale() {
        val withText = fs.copy(
            documentInfo = DocumentInfo(company = "ACME Engineering", projectNumber = "P-1234"),
            objects = fs.objects.map { if (it.id == "pg") it.copy(sheet = mapOf(SheetKeys.SERVICE to "Export gas", SheetKeys.DOCUMENT_NUMBER to "P-1234-DS-001")) else it },
        )
        assertEquals(fs.modelHash(), withText.modelHash())
        val ds = sheet(withText, "pg")
        assertFalse(ds.notes.any { "OUT OF DATE" in it })
        assertEquals("Export gas", ds.service)
        assertEquals("P-1234-DS-001", ds.documentNumber)
        assertEquals("ACME Engineering", ds.info.company)

        val changed = FlowsheetEditing.setSpec(fs, "pg", SpecKey.LENGTH, Spec(6000.0, "m"))
        assertTrue(sheet(changed, "pg").notes.any { "OUT OF DATE" in it })
    }

    @Test
    fun pipeSheetShowsSizeAndTheSizingChecks() {
        val sized = FlowsheetEditing.setPipeSize(fs, "pg", "8", "40 / STD / 40S")
            .copy(sizingCriteria = SizingCriteria(gas = LineCriteria(maxVelocity = Spec(20.0, "m/s"), maxMomentumFlux = Spec(400.0, "kg/m.s2"))))
        // The size is the benchmark's own 8" Sch 40, so the stored solve still matches.
        assertEquals(fs.modelHash().length, sized.modelHash().length)
        val ds = DataSheets.build("Lines", sized, StoredSolve(result, sized.modelHash(), 0), UnitSet.SI, sized.obj("pg")!!, "2026-10-21", "test")
        val basis = ds.section("DESIGN BASIS")
        assertEquals("NPS 8 (DN 200)", basis.line("Nominal size").values.single())
        assertEquals("8.179", basis.line("Wall thickness").values.single())
        val sizing = ds.section("LINE SIZING — GAS SERVICE")
        assertEquals(listOf("Limit", "Value", "Result"), sizing.columns)
        assertEquals("OK", sizing.line("Maximum velocity").values[2])
        assertEquals("EXCEEDED", sizing.line("Maximum ρv²").values[2]) // 446 kg/m·s² > 400
    }

    @Test
    fun designFieldsPrintBlankForFillingInByHand() {
        val ds = sheet(fs, "pt")
        val design = ds.section("MECHANICAL")
        assertEquals(SheetKeys.DESIGN.map { it.second }, design.lines.map { it.description })
        assertTrue(design.lines.all { it.values.single().isEmpty() })
        // Two-phase line: both a vapour and a liquid block.
        val op = ds.section("OPERATING CONDITIONS")
        assertTrue(op.lines.any { it.description == "VAPOUR" } && op.lines.any { it.description == "LIQUID" })
    }
}
