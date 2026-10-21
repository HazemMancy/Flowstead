/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.export

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.ceil
import kotlin.math.max

/**
 * Equipment data sheets as a formatted Excel workbook, one worksheet per equipment, laid out like the PDF:
 * the Flowstead logo and a title block, then numbered lines in bordered sections, notes and revisions. Numbers
 * are written as numbers with the decimals shown in the app; "—" (not returned by the engine) stays text.
 * Gridlines are off and each sheet prints on A4, one page wide.
 */
object DataSheetWorkbook {
    /** A PNG and its size in pixels, placed at the top left of every sheet. */
    class Logo(val png: ByteArray, val widthPx: Int, val heightPx: Int)

    fun write(sheets: List<DataSheet>, logo: Logo?): ByteArray {
        require(sheets.isNotEmpty()) { "No data sheets" }
        val names = uniqueNames(sheets.map { it.tag })
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(path: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
            fun put(path: String, xml: String) = put(path, xml.toByteArray(Charsets.UTF_8))
            put("[Content_Types].xml", contentTypes(sheets.size, logo != null))
            put("_rels/.rels", ROOT_RELS)
            put("docProps/app.xml", APP_PROPS)
            put("xl/workbook.xml", workbook(names))
            put("xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
            put("xl/styles.xml", STYLES)
            sheets.forEachIndexed { i, s ->
                val n = i + 1
                put("xl/worksheets/sheet$n.xml", Layout(s, logo).xml())
                if (logo != null) {
                    put("xl/worksheets/_rels/sheet$n.xml.rels", rels("drawing", "../drawings/drawing$n.xml"))
                    put("xl/drawings/drawing$n.xml", drawing(logo))
                    put("xl/drawings/_rels/drawing$n.xml.rels", rels("image", "../media/logo.png"))
                }
            }
            if (logo != null) put("xl/media/logo.png", logo.png)
        }
        return out.toByteArray()
    }

    // Cell styles (indices into cellXfs in STYLES).
    private const val BODY = 1
    private const val BODY_CENTRE = 2
    private const val BODY_BOLD = 3
    private const val BAR = 4
    private const val HEAD = 5
    private const val UNIT = 6
    private const val TITLE = 7
    private const val SUBTITLE = 8
    private const val INFO = 9
    private const val NOTE = 10
    private const val NUMBER0 = 11 // 11..17: 0..6 decimals
    private const val SMALL = 18

    private class Cell(val text: String?, val number: Double?, val style: Int)

    /** Places one data sheet on a grid: A No. | B Description | C Unit | D.. values. */
    private class Layout(val s: DataSheet, val logo: Logo?) {
        val values = max(3, s.sections.maxOfOrNull { if (it.columns.size == 1 && it.columns.single().isBlank()) 1 else it.columns.size } ?: 1)
        val last = 2 + values // index of the last column
        val cells = sortedMapOf<Int, MutableMap<Int, Cell>>()
        val heights = mutableMapOf<Int, Double>()
        val merges = mutableListOf<String>()
        var row = 1

        fun set(r: Int, c: Int, text: String?, style: Int) {
            val number = text?.let(::numberOf)
            val decimals = text?.substringAfter('.', "")?.takeIf { number != null && '.' in text }?.length ?: 0
            cells.getOrPut(r) { mutableMapOf() }[c] =
                if (number != null) Cell(null, number, NUMBER0 + decimals.coerceAtMost(6)) else Cell(text, null, style)
        }

        /** A merged, styled range; every cell of it gets the style so borders draw all round. */
        fun span(r: Int, c0: Int, c1: Int, text: String?, style: Int, numbers: Boolean = false) {
            for (c in c0..c1) cells.getOrPut(r) { mutableMapOf() }[c] = Cell(null, null, style)
            if (numbers) set(r, c0, text, style) else cells.getValue(r)[c0] = Cell(text, null, style)
            if (c1 > c0) merges += "${Xlsx.column(c0)}$r:${Xlsx.column(c1)}$r"
        }

        fun xml(): String {
            titleBlock()
            row++
            for (sec in s.sections) section(sec)
            notes()
            revisions()
            return render()
        }

        fun titleBlock() {
            // Rows 1–3: logo (A–B) | title, tag and document data across the rest.
            for (r in 1..3) for (c in 0..1) cells.getOrPut(r) { mutableMapOf() }[c] = Cell(null, null, 0)
            merges += "A1:B3"
            span(1, 2, last, s.title, TITLE)
            span(2, 2, last, "TAG: ${s.tag}", SUBTITLE)
            span(3, 2, last, listOf("Doc. No. ${s.documentNumber.ifBlank { "—" }}", "Rev. ${s.revision}", "Date ${s.date}", "Units ${s.unitSystem}").joinToString("   ·   "), SMALL)
            heights[1] = 24.0
            heights[2] = 18.0
            heights[3] = 15.0
            val i = s.info
            val boxes = listOf(
                listOf("Company" to i.company, "Project" to i.project, "Project No." to i.projectNumber),
                listOf("Client" to i.client, "Site / location" to i.site, "Service" to s.service),
                listOf("Prepared" to i.preparedBy, "Checked" to i.checkedBy, "Approved" to i.approvedBy),
            )
            row = 4
            for (line in boxes) {
                val ranges = listOf(0 to 1, 2 to 3, 4 to last)
                line.forEachIndexed { k, (label, value) -> span(row, ranges[k].first, ranges[k].second, "$label: $value", INFO) }
                heights[row] = 16.0
                row++
            }
        }

        var n = 0

        fun section(sec: DataSheet.Section) {
            span(row, 0, last, sec.title, BAR)
            heights[row] = 16.0
            row++
            val single = sec.columns.size == 1 && sec.columns.single().isBlank()
            if (!single) {
                set(row, 0, "No.", HEAD); set(row, 1, "Description", HEAD); set(row, 2, "Unit", HEAD)
                valueColumns(sec.columns.size).forEachIndexed { k, (c0, c1) -> span(row, c0, c1, sec.columns[k].replace('\n', ' '), HEAD) }
                row++
            }
            for (l in sec.lines) {
                val sub = l.bold && l.values.all { it.isEmpty() }
                set(row, 0, if (sub) "" else (++n).toString(), BODY_CENTRE)
                set(row, 1, l.description, if (l.bold) BODY_BOLD else BODY)
                set(row, 2, l.unit, UNIT)
                valueColumns(l.values.size).forEachIndexed { k, (c0, c1) -> span(row, c0, c1, l.values[k], BODY_CENTRE, numbers = true) }
                row++
            }
            row++
        }

        /** Value columns D.. shared out among [count] values; the last one takes what is left. */
        fun valueColumns(count: Int): List<Pair<Int, Int>> {
            val each = max(1, values / count)
            return (0 until count).map { k -> (3 + k * each) to if (k == count - 1) last else 3 + k * each + each - 1 }
        }

        fun notes() {
            span(row, 0, last, "NOTES", BAR)
            row++
            s.notes.forEachIndexed { k, note ->
                set(row, 0, "${k + 1}.", NOTE)
                span(row, 1, last, note, NOTE)
                heights[row] = 13.0 * max(1.0, ceil(note.length / (40.0 + 16 * values)))
                row++
            }
            row++
        }

        fun revisions() {
            span(row, 0, last, "REVISIONS", BAR)
            row++
            val heads = listOf("Rev.", "Description", "Date", "Prepared", "Checked", "Approved")
            val data = listOf(s.revision, s.revisionDescription, s.date, s.info.preparedBy, s.info.checkedBy, s.info.approvedBy)
            val cols = listOf(0 to 0, 1 to 1, 2 to 2) + valueColumnsFixed()
            for ((texts, style) in listOf(heads to HEAD, data to BODY)) {
                texts.forEachIndexed { k, t -> span(row, cols[k].first, cols[k].second, t, style) }
                row++
            }
        }

        /** Three columns for Prepared / Checked / Approved over D..last. */
        fun valueColumnsFixed(): List<Pair<Int, Int>> {
            val each = max(1, values / 3)
            return listOf(3 to 3 + each - 1, 3 + each to 3 + 2 * each - 1, 3 + 2 * each to last)
        }

        fun render(): String = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
            append("""<sheetPr><pageSetUpPr fitToPage="1"/></sheetPr>""")
            append("""<sheetViews><sheetView workbookViewId="0" showGridLines="0"/></sheetViews>""")
            append("""<sheetFormatPr defaultRowHeight="15"/>""")
            append("<cols>")
            append("""<col min="1" max="1" width="6" customWidth="1"/>""")
            append("""<col min="2" max="2" width="42" customWidth="1"/>""")
            append("""<col min="3" max="3" width="12" customWidth="1"/>""")
            append("""<col min="4" max="${last + 1}" width="${if (values <= 3) 18 else 15}" customWidth="1"/>""")
            append("</cols><sheetData>")
            for ((r, row) in cells) {
                append("""<row r="$r"""")
                heights[r]?.let { append(""" ht="$it" customHeight="1"""") }
                append(">")
                for ((c, cell) in row.toSortedMap()) {
                    val ref = Xlsx.column(c) + r
                    when {
                        cell.number != null -> append("""<c r="$ref" s="${cell.style}"><v>${ResultsWorkbook.plain(cell.number)}</v></c>""")
                        !cell.text.isNullOrEmpty() ->
                            append("""<c r="$ref" s="${cell.style}" t="inlineStr"><is><t xml:space="preserve">${esc(cell.text)}</t></is></c>""")
                        else -> append("""<c r="$ref" s="${cell.style}"/>""")
                    }
                }
                append("</row>")
            }
            append("</sheetData>")
            if (merges.isNotEmpty()) {
                append("""<mergeCells count="${merges.size}">""")
                merges.forEach { append("""<mergeCell ref="$it"/>""") }
                append("</mergeCells>")
            }
            append("""<pageMargins left="0.4" right="0.4" top="0.5" bottom="0.6" header="0.3" footer="0.3"/>""")
            append("""<pageSetup paperSize="9" orientation="portrait" fitToWidth="1" fitToHeight="0"/>""")
            append("<headerFooter><oddFooter>")
            append(esc("&L&8Generated with ${s.footer}. Process values are simulation results; verify before design use.&R&8${s.tag} · Page &P of &N"))
            append("</oddFooter></headerFooter>")
            if (logo != null) append("""<drawing r:id="rId1"/>""")
            append("</worksheet>")
        }
    }

    /** "1,234.50" → 1234.5; anything else (text, "—", "≤ 3.0") → null. */
    internal fun numberOf(s: String): Double? {
        val t = s.trim()
        if (!Regex("-?[0-9][0-9,]*(\\.[0-9]+)?").matches(t)) return null
        return t.replace(",", "").toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    private fun esc(s: String): String = buildString(s.length) {
        for (c in s) when {
            c == '&' -> append("&amp;")
            c == '<' -> append("&lt;")
            c == '>' -> append("&gt;")
            c == '"' -> append("&quot;")
            c < ' ' && c != '\t' && c != '\n' && c != '\r' -> Unit
            else -> append(c)
        }
    }

    private fun uniqueNames(names: List<String>): List<String> {
        val used = mutableSetOf<String>()
        return names.map { raw ->
            val base = raw.replace(Regex("[:\\\\/?*\\[\\]]"), "_").trim('\'').take(31).trim('\'').ifBlank { "Sheet" }
            var name = base
            var i = 2
            while (!used.add(name.lowercase())) name = base.take(27) + " ${i++}"
            name
        }
    }

    private fun drawing(logo: Logo): String {
        val emu = 9525L // per pixel at 96 dpi
        // Fit the logo into rows 1–3 (57 pt ≈ 76 px), keeping its aspect ratio.
        val h = 64L
        val w = h * logo.widthPx / logo.heightPx
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""" +
            """<xdr:oneCellAnchor><xdr:from><xdr:col>0</xdr:col><xdr:colOff>${6 * emu}</xdr:colOff><xdr:row>0</xdr:row><xdr:rowOff>${6 * emu}</xdr:rowOff></xdr:from>""" +
            """<xdr:ext cx="${w * emu}" cy="${h * emu}"/>""" +
            """<xdr:pic><xdr:nvPicPr><xdr:cNvPr id="2" name="Flowstead logo" descr="Flowstead"/><xdr:cNvPicPr><a:picLocks noChangeAspect="1"/></xdr:cNvPicPr></xdr:nvPicPr>""" +
            """<xdr:blipFill><a:blip r:embed="rId1"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill>""" +
            """<xdr:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="${w * emu}" cy="${h * emu}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic>""" +
            """<xdr:clientData/></xdr:oneCellAnchor></xdr:wsDr>"""
    }

    private fun rels(kind: String, target: String) =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/$kind" Target="$target"/></Relationships>"""

    private fun contentTypes(n: Int, logo: Boolean) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        if (logo) append("""<Default Extension="png" ContentType="image/png"/>""")
        append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        append("""<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>""")
        for (i in 1..n) {
            append("""<Override PartName="/xl/worksheets/sheet$i.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
            if (logo) append("""<Override PartName="/xl/drawings/drawing$i.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/>""")
        }
        append("</Types>")
    }

    private fun workbook(names: List<String>) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
        names.forEachIndexed { i, name -> append("""<sheet name="${esc(name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""") }
        append("</sheets></workbook>")
    }

    private fun workbookRels(n: Int) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        for (i in 1..n) {
            append("""<Relationship Id="rId$i" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$i.xml"/>""")
        }
        append("""<Relationship Id="rId${n + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        append("</Relationships>")
    }

    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/></Relationships>"""

    private const val APP_PROPS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"><Application>Flowstead</Application></Properties>"""

    private val STYLES: String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        append("""<numFmts count="7">""")
        for (d in 0..6) append("""<numFmt numFmtId="${164 + d}" formatCode="#,##0${if (d > 0) "." + "0".repeat(d) else ""}"/>""")
        append("</numFmts>")
        // Fonts: 0 body, 1 bold, 2 small grey, 3 title navy, 4 subtitle, 5 info.
        append("""<fonts count="6">""")
        append("""<font><sz val="10"/><name val="Calibri"/></font>""")
        append("""<font><b/><sz val="10"/><name val="Calibri"/></font>""")
        append("""<font><sz val="8"/><color rgb="FF5F666D"/><name val="Calibri"/></font>""")
        append("""<font><b/><sz val="14"/><color rgb="FF0B1F33"/><name val="Calibri"/></font>""")
        append("""<font><b/><sz val="12"/><color rgb="FF0E7C74"/><name val="Calibri"/></font>""")
        append("""<font><sz val="9"/><name val="Calibri"/></font>""")
        append("</fonts>")
        append("""<fills count="4"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>""")
        append("""<fill><patternFill patternType="solid"><fgColor rgb="FFD9DEE4"/><bgColor indexed="64"/></patternFill></fill>""")
        append("""<fill><patternFill patternType="solid"><fgColor rgb="FFF1F3F5"/><bgColor indexed="64"/></patternFill></fill></fills>""")
        val thin = """<left style="thin"><color rgb="FF808080"/></left><right style="thin"><color rgb="FF808080"/></right><top style="thin"><color rgb="FF808080"/></top><bottom style="thin"><color rgb="FF808080"/></bottom><diagonal/>"""
        append("""<borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border><border>$thin</border></borders>""")
        append("""<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""")
        fun xf(font: Int, fill: Int, border: Int, h: String, numFmt: Int = 0, wrap: Boolean = false) =
            """<xf numFmtId="$numFmt" fontId="$font" fillId="$fill" borderId="$border" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"""" +
                (if (numFmt != 0) """ applyNumberFormat="1"""" else "") +
                """><alignment horizontal="$h" vertical="center"${if (wrap) """ wrapText="1"""" else ""}/></xf>"""
        val xfs = listOf(
            """<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""", // 0 default
            xf(0, 0, 1, "left", wrap = true), // 1 BODY
            xf(0, 0, 1, "center", wrap = true), // 2 BODY_CENTRE
            xf(1, 0, 1, "left"), // 3 BODY_BOLD
            xf(1, 2, 1, "left"), // 4 BAR
            xf(1, 3, 1, "center", wrap = true), // 5 HEAD
            xf(2, 0, 1, "center"), // 6 UNIT
            xf(3, 0, 0, "center"), // 7 TITLE
            xf(4, 0, 0, "center"), // 8 SUBTITLE
            xf(5, 0, 1, "left", wrap = true), // 9 INFO
            xf(0, 0, 0, "left", wrap = true), // 10 NOTE
        ) + (0..6).map { d -> xf(0, 0, 1, "center", numFmt = 164 + d) } + // 11..17 NUMBER0..6
            listOf(xf(2, 0, 0, "center")) // 18 SMALL
        append("""<cellXfs count="${xfs.size}">""")
        xfs.forEach { append(it) }
        append("""</cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""")
    }
}
