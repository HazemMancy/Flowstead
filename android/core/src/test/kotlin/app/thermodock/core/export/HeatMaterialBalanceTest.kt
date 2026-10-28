/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.core.export

import app.thermodock.core.api.Phases
import app.thermodock.core.api.SolveResult
import app.thermodock.core.model.Connection
import app.thermodock.core.model.DocumentInfo
import app.thermodock.core.model.Flowsheet
import app.thermodock.core.model.FlowsheetJson
import app.thermodock.core.model.ObjectType
import app.thermodock.core.model.SimObject
import app.thermodock.core.model.StoredSolve
import app.thermodock.core.model.modelHash
import app.thermodock.core.units.Quantity
import app.thermodock.core.units.UnitSet
import app.thermodock.core.units.Units
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeatMaterialBalanceTest {
    private val result: SolveResult by lazy {
        val dir = System.getProperty("thermodock.sharedDir") ?: error("thermodock.sharedDir not set")
        FlowsheetJson.decodeFromString(SolveResult.serializer(), File(dir, "fixtures/solve-result-letdown.json").readText())
    }

    private val flowsheet by lazy {
        Flowsheet(
            propertyPackage = "Peng-Robinson (PR)",
            compounds = result.objects.getValue("feed").phases!!.getValue(Phases.OVERALL).composition.keys.toList(),
            objects = listOf(
                SimObject("feed", "FEED", ObjectType.MaterialStream),
                SimObject("v", "VLV-100", ObjectType.Valve),
                SimObject("lp", "LP", ObjectType.MaterialStream),
            ),
            connections = listOf(Connection("feed", 0, "v", 0), Connection("v", 0, "lp", 0)),
            documentInfo = DocumentInfo(company = "ACME", hmbNumber = "HMB-001", revision = "B"),
        )
    }

    private val units = UnitSet.SI.with(Quantity.Temperature, Units.C).with(Quantity.Pressure, Units.bara)

    private fun build(perSheet: Int = Int.MAX_VALUE) = HeatMaterialBalance.build(
        "Letdown", flowsheet, StoredSolve(result, flowsheet.modelHash(), 1_760_000_000_000), units, "2026-10-21", "1.1.0", perSheet,
    )

    @Test
    fun everyStreamIsAColumnWithWhereItComesFromAndGoesTo() {
        val sheet = build().single()
        val streams = sheet.sections.first { it.title == "MATERIAL STREAMS" }
        assertEquals(listOf("FEED", "LP"), streams.columns)
        assertEquals(listOf("Feed", "VLV-100"), streams.lines.first { it.description == "From" }.values)
        assertEquals(listOf("VLV-100", "Product"), streams.lines.first { it.description == "To" }.values)
        assertEquals("-40.00", streams.lines.first { it.description == "Temperature" }.values[0]) // 233.15 K
    }

    @Test
    fun itCarriesTheProjectTitleBlockOnA3Landscape() {
        val sheet = build().single()
        assertEquals(DataSheet.Paper.A3Landscape, sheet.paper)
        assertEquals("HMB-001", sheet.documentNumber)
        assertEquals("B", sheet.revision)
        assertEquals("ACME", sheet.info.company)
        assertEquals("Letdown", sheet.info.project) // the project's name when the title block leaves it blank
        assertTrue(sheet.footer.startsWith("ThermoDock 1.1.0"))
    }

    @Test
    fun thePdfSplitsStreamsAcrossSheets() {
        val sheets = build(perSheet = 1)
        assertEquals(2, sheets.size)
        assertEquals(listOf("FEED"), sheets[0].sections.first().columns)
        assertTrue(sheets[1].subtitle!!.endsWith("STREAMS LP TO LP"))
    }

    @Test
    fun theWorkbookIsWritten() {
        val bytes = DataSheetWorkbook.write(build(), null)
        assertTrue(bytes.size > 1000)
    }
}
