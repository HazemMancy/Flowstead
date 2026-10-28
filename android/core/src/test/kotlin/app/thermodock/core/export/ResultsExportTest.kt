/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.core.export

import app.thermodock.core.api.MessageLevel
import app.thermodock.core.api.Phases
import app.thermodock.core.api.SolveResult
import app.thermodock.core.api.SolverMessage
import app.thermodock.core.model.Flowsheet
import app.thermodock.core.model.FlowsheetJson
import app.thermodock.core.model.ObjectType
import app.thermodock.core.model.SimObject
import app.thermodock.core.model.StoredSolve
import app.thermodock.core.model.modelHash
import app.thermodock.core.units.Quantity
import app.thermodock.core.units.UnitSet
import app.thermodock.core.units.Units
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResultsExportTest {
    private val result: SolveResult by lazy {
        val dir = System.getProperty("thermodock.sharedDir") ?: error("thermodock.sharedDir not set")
        FlowsheetJson.decodeFromString(SolveResult.serializer(), File(dir, "fixtures/solve-result-letdown.json").readText())
            .let { it.copy(messages = it.messages + SolverMessage(MessageLevel.Info, "Note, with \"quotes\"", "v")) }
    }

    private val flowsheet by lazy {
        Flowsheet(
            propertyPackage = "Peng-Robinson (PR)",
            compounds = result.objects.getValue("feed").phases!!.getValue(Phases.OVERALL).composition.keys.toList(),
            objects = listOf(
                SimObject("feed", "FEED", ObjectType.MaterialStream),
                SimObject("v", "VLV-100", ObjectType.Valve),
                SimObject("lp", "=LP", ObjectType.MaterialStream), // a tag a spreadsheet would run as a formula
                SimObject("new", "NEW", ObjectType.MaterialStream), // added after the solve
            ),
        )
    }

    private val units = UnitSet.SI.with(Quantity.Temperature, Units.C).with(Quantity.Pressure, Units.bara)

    private fun sheets(hash: String = flowsheet.modelHash()) =
        ResultsWorkbook.build("Letdown", flowsheet, StoredSolve(result, hash, 1_760_000_000_000), units)

    @Test
    fun streamsSheetHasEngineValuesInDisplayUnitsAndBlanksForMissing() {
        val streams = sheets().first { it.name == "Streams" }
        val head = streams.rows.first { it.firstOrNull() == Cell.Text("Property") }
        assertEquals(listOf("FEED", "=LP", "NEW"), head.drop(1).map { (it as Cell.Text).text })
        val t = streams.rows.first { it.firstOrNull() == Cell.Text("Temperature [°C]") }
        assertEquals(-40.0, (t[1] as Cell.Number).value, 1e-9) // 233.15 K
        assertEquals(Cell.Empty, t[3]) // NEW: not solved, so no number
        val p = streams.rows.first { it.firstOrNull() == Cell.Text("Pressure [bara]") }
        assertEquals(15.0, (p[2] as Cell.Number).value, 1e-9)
        assertFalse(sheets().flatMap { it.rows }.any { row -> row.any { it is Cell.Text && it.text.contains("out of date") } })
    }

    @Test
    fun staleResultsSayOnEverySheet() {
        assertTrue(sheets(hash = "other").all { s -> s.rows.any { r -> r.any { it is Cell.Text && it.text.contains("out of date") } } })
    }

    @Test
    fun csvQuotesAndDefusesFormulas() {
        val csv = Csv.write(sheets())
        assertTrue(csv.startsWith("Streams\r\nProject,Letdown\r\n"))
        assertTrue(csv.contains("Property,FEED,'=LP,NEW\r\n"))
        assertTrue(csv.contains("info,VLV-100,\"Note, with \"\"quotes\"\"\"\r\n"))
        assertTrue(csv.contains("\r\nTemperature [°C],-40,"))
    }

    @Test
    fun plainNumbersNeverUseExponents() {
        assertEquals("0.00000123", ResultsWorkbook.plain(1.23e-6))
        assertEquals("12345678900000", ResultsWorkbook.plain(1.23456789e13))
        assertEquals("0.3333333333", ResultsWorkbook.plain(1.0 / 3))
        assertEquals("0", ResultsWorkbook.plain(-0.0))
    }

    @Test
    fun xlsxIsAValidPackage() {
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(Xlsx.write(sheets()))).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readBytes().toString(Charsets.UTF_8) }
        }
        assertEquals(
            setOf(
                "[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml",
                "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml", "xl/worksheets/sheet3.xml",
            ),
            entries.keys,
        )
        assertTrue(entries.getValue("xl/workbook.xml").contains("""<sheet name="Unit operations" sheetId="2" r:id="rId2"/>"""))
        val s1 = entries.getValue("xl/worksheets/sheet1.xml")
        assertTrue(s1.contains("""<t xml:space="preserve">=LP</t>"""))
        assertTrue(Regex("""<c r="B\d+"><v>-40</v></c>""").containsMatchIn(s1))
        val s3 = entries.getValue("xl/worksheets/sheet3.xml")
        assertTrue(s3.contains("Note, with &quot;quotes&quot;"))
        // Every part parses as XML.
        val parser = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
        entries.values.forEach { parser.parse(ByteArrayInputStream(it.toByteArray())) }
    }

    @Test
    fun columnLetters() {
        assertEquals(listOf("A", "Z", "AA", "AZ", "BA", "ZZ", "AAA"), listOf(0, 25, 26, 51, 52, 701, 702).map(Xlsx::column))
    }

    @Test
    fun streamPropertiesReportedPerPhaseComeFromTheOverallPhase() {
        val solve = StoredSolve(result, flowsheet.modelHash(), 1_760_000_000_000)
        val view = app.thermodock.core.model.ResultView(solve, flowsheet.modelHash())
        val overall = result.objects.getValue("feed").phases!!.getValue(Phases.OVERALL).properties
        for (key in listOf("density", "volumeFlow", "molecularWeight", "massEntropy")) {
            val v = view.value("feed", key)
            assertTrue(v is app.thermodock.core.model.DisplayValue.Calculated, "$key: $v")
            assertEquals(overall.getValue(key), v.si, 0.0)
        }
        val streams = sheets().first { it.name == "Streams" }
        val density = streams.rows.filter { (it.firstOrNull() as? Cell.Text)?.text?.contains("mass density") == true }
        assertTrue(density.isNotEmpty() && density.all { it[1] is Cell.Number || it[1] == Cell.Empty }, "density rows: $density")
        assertTrue(density.any { it[1] is Cell.Number }, "a phase density is exported: $density")
        // HYSYS layout: an Overall block, then the phases present.
        assertTrue(streams.rows.any { it.firstOrNull() == Cell.Text("Overall") })
    }
}
