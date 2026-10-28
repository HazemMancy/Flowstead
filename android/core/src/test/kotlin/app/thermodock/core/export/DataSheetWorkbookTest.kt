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
import app.thermodock.core.model.StoredSolve
import app.thermodock.core.model.modelHash
import app.thermodock.core.model.obj
import app.thermodock.core.units.UnitSet
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataSheetWorkbookTest {
    private val dir = File(System.getProperty("thermodock.sharedDir") ?: error("thermodock.sharedDir not set"), "fixtures")

    private fun sheets(): List<DataSheet> {
        val input = FlowsheetJson.decodeFromString(Flowsheet.serializer(), File(dir, "flowsheet-lines.json").readText())
        val result = FlowsheetJson.decodeFromString(SolveResult.serializer(), File(dir, "solve-result-lines.json").readText())
        val fs = FlowsheetEditing.setPipeSize(input, "pg", "8", "40 / STD / 40S").copy(
            documentInfo = DocumentInfo(company = "Sample Engineering Ltd", project = "Gas Gathering Expansion", projectNumber = "P-0001", preparedBy = "ThermoDock"),
            sizingCriteria = SizingCriteria(gas = LineCriteria(maxVelocity = Spec(20.0, "m/s"), maxMomentumFlux = Spec(15000.0, "kg/m.s2"))),
        ).let { FlowsheetEditing.setSheetField(it, "pg", SheetKeys.SERVICE, "Export gas to pipeline") }
        val stored = StoredSolve(result, fs.modelHash(), 0)
        return listOf("pg", "pt").map { DataSheets.build("Lines", fs, stored, UnitSet.SI, fs.obj(it)!!, "2026-10-21", "test") }
    }

    private fun logo(): DataSheetWorkbook.Logo {
        val img = BufferedImage(200, 48, BufferedImage.TYPE_INT_ARGB)
        val out = ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }
        return DataSheetWorkbook.Logo(out.toByteArray(), 200, 48)
    }

    private fun entries(bytes: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(bytes.inputStream()).use { z ->
            generateSequence { z.nextEntry }.forEach { e -> put(e.name, if (e.name.endsWith(".png")) "png" else z.readBytes().toString(Charsets.UTF_8)) }
        }
    }

    @Test
    fun oneFormattedWorksheetPerEquipmentWithTheLogo() {
        val bytes = DataSheetWorkbook.write(sheets(), logo())
        File("build/data-sheets-sample.xlsx").writeBytes(bytes) // for review (LibreOffice: soffice --convert-to pdf)
        val e = entries(bytes)
        assertTrue("xl/worksheets/sheet2.xml" in e && "xl/media/logo.png" in e && "xl/drawings/drawing1.xml" in e, e.keys.toString())
        assertTrue("""name="GAS LINE"""" in e.getValue("xl/workbook.xml"))
        val sheet = e.getValue("xl/worksheets/sheet1.xml")
        assertTrue("PIPE SEGMENT DATA SHEET" in sheet && "showGridLines=\"0\"" in sheet && "<drawing r:id=\"rId1\"/>" in sheet)
        // Engine numbers are numeric cells: the gas line's 79.13 kPa pressure drop.
        assertTrue(Regex("""<c r="D\d+" s="13"><v>79.13</v></c>""").containsMatchIn(sheet), "pressure drop not numeric")
    }

    @Test
    fun onlyPlainNumbersBecomeNumbers() {
        assertEquals(1234.5, DataSheetWorkbook.numberOf("1,234.50"))
        assertEquals(-0.31, DataSheetWorkbook.numberOf("-0.31"))
        assertNull(DataSheetWorkbook.numberOf("—"))
        assertNull(DataSheetWorkbook.numberOf("≤ 20.000"))
        assertNull(DataSheetWorkbook.numberOf("2026-10-21"))
        assertNull(DataSheetWorkbook.numberOf("NPS 8 (DN 200)"))
    }

    @Test
    fun withoutALogoTheWorkbookHasNoDrawing() {
        val e = entries(DataSheetWorkbook.write(sheets().take(1), null))
        assertTrue(e.keys.none { it.startsWith("xl/drawings") || it.startsWith("xl/media") })
    }
}
