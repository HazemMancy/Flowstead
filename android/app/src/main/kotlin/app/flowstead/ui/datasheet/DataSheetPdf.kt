/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.datasheet

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import app.flowstead.core.export.DataSheet
import java.io.OutputStream

/**
 * Renders [DataSheet]s to an A4 PDF in the style of an engineering contractor's data sheet: a bordered
 * title block on every page, numbered lines in gridded tables, notes and a revision block. Each data sheet
 * starts on a new page and is numbered "Sheet n of m" on its own.
 */
object DataSheetPdf {
    // Page of the sheet being drawn: A4 portrait (data sheets) or A3 landscape (heat and material balance).
    private var paper = DataSheet.Paper.A4Portrait
    private val PAGE_W get() = if (paper == DataSheet.Paper.A3Landscape) 1191f else 595f // points
    private const val PAGE_H = 842f // the height of both A4 portrait and A3 landscape
    private const val MARGIN = 28f
    private val WIDTH get() = PAGE_W - 2 * MARGIN
    private const val PAD = 3f
    private const val FOOTER_H = 16f

    private val ink = Color.rgb(0x1A, 0x1A, 0x1A)
    private val grid = Color.rgb(0x80, 0x80, 0x80)
    private val bar = Color.rgb(0xD9, 0xDE, 0xE4)
    private val headFill = Color.rgb(0xF1, 0xF3, 0xF5)
    private val muted = Color.rgb(0x5F, 0x66, 0x6D)

    private fun text(size: Float, bold: Boolean = false, color: Int = ink) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        this.color = color
    }

    private val body = text(7.2f)
    private val bodyBold = text(7.2f, bold = true)
    private val small = text(5.8f, color = muted)
    private val barText = text(7.6f, bold = true)
    private val line = Paint().apply { color = grid; strokeWidth = 0.5f; style = Paint.Style.STROKE }
    private val frame = Paint().apply { color = ink; strokeWidth = 1f; style = Paint.Style.STROKE }
    private val fill = Paint().apply { style = Paint.Style.FILL }

    @Synchronized
    fun write(sheets: List<DataSheet>, out: OutputStream) {
        val doc = PdfDocument()
        try {
            var pageNo = 0
            for (sheet in sheets) {
                paper = sheet.paper
                val pages = paginate(sheet)
                pages.forEachIndexed { i, blocks ->
                    pageNo++
                    val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W.toInt(), PAGE_H.toInt(), pageNo).create())
                    val c = page.canvas
                    var y = titleBlock(c, sheet, i + 1, pages.size)
                    for (b in blocks) { b.draw(c, MARGIN, y); y += b.height }
                    footer(c, sheet)
                    doc.finishPage(page)
                }
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    // ---- layout

    private abstract class Block {
        abstract val height: Float
        abstract fun draw(c: Canvas, x: Float, y: Float)
        /** A section bar should not end a page on its own. */
        open val keepWithNext: Boolean = false
    }

    private fun paginate(sheet: DataSheet): List<List<Block>> {
        val usable = PAGE_H - MARGIN - FOOTER_H - (MARGIN + TITLE_H)
        val blocks = buildBlocks(sheet)
        val pages = mutableListOf<MutableList<Block>>(mutableListOf())
        var used = 0f
        var i = 0
        var current: Pair<String, Block?>? = null // section title and its column header, repeated after a break
        while (i < blocks.size) {
            val b = blocks[i]
            // Spacing that falls on a page break is simply dropped.
            if (b is Gap && used + b.height > usable) { i++; continue }
            if (b is SectionBar) current = b.title to (blocks.getOrNull(i + 1) as? HeaderRow)
            val need = b.height + if (b.keepWithNext) blocks.getOrNull(i + 1)?.height ?: 0f else 0f
            if (used + need > usable && pages.last().isNotEmpty()) {
                pages += mutableListOf<Block>()
                used = 0f
                // Continue the section on the new page with its bar and column heads.
                if (b !is SectionBar && current != null) {
                    val cont = SectionBar(current.first + " (cont.)")
                    pages.last() += cont
                    used += cont.height
                    current.second?.takeIf { b !is HeaderRow }?.let { pages.last() += it; used += it.height }
                }
            }
            pages.last() += b
            used += b.height
            i++
        }
        return pages
    }

    private fun buildBlocks(sheet: DataSheet): List<Block> = buildList {
        var n = 0
        for (s in sheet.sections) {
            add(SectionBar(s.title))
            val single = s.columns.size == 1 && s.columns.single().isBlank()
            val widths = widths(if (single) 1 else s.columns.size)
            if (!single) add(HeaderRow(listOf("No.", "Description", "Unit") + s.columns, widths))
            for (l in s.lines) {
                val number = if (l.bold && l.values.all { it.isEmpty() }) "" else (++n).toString()
                add(Row(listOf(number, l.description, l.unit) + l.values, widths, bold = l.bold))
            }
            add(Gap(5f))
        }
        add(SectionBar("NOTES"))
        sheet.notes.forEachIndexed { i, note -> add(NoteRow("${i + 1}.", note)) }
        add(Gap(8f))
        add(SectionBar("REVISIONS"))
        val rw = listOf(28f, 62f, WIDTH - 28f - 62f - 3 * 80f, 80f, 80f, 80f)
        add(HeaderRow(listOf("Rev.", "Date", "Description", "Prepared", "Checked", "Approved"), rw, plain = true))
        add(Row(listOf(sheet.revision, sheet.date, sheet.revisionDescription, sheet.info.preparedBy, sheet.info.checkedBy, sheet.info.approvedBy), rw, plain = true))
    }

    /** No. | Description | Unit | values: the description column narrows as value columns are added. */
    private fun widths(values: Int): List<Float> {
        val no = 22f
        val unit = 50f
        val desc = if (values <= 1) 245f else 170f
        val each = (WIDTH - no - unit - desc) / values
        return listOf(no, desc, unit) + List(values) { each }
    }

    private class Gap(override val height: Float) : Block() {
        override fun draw(c: Canvas, x: Float, y: Float) = Unit
    }

    private class SectionBar(val title: String) : Block() {
        override val height = 14f
        override val keepWithNext = true
        override fun draw(c: Canvas, x: Float, y: Float) {
            fill.color = bar
            c.drawRect(x, y, x + WIDTH, y + height, fill)
            c.drawRect(x, y, x + WIDTH, y + height, line)
            c.drawText(title, x + PAD + 1, y + height - 4f, barText)
        }
    }

    /**
     * One table line. Data tables are No. | Description | Unit | values: numbers and units centred, the unit in small
     * print. A [plain] table (the revision block) is left-aligned text throughout.
     */
    private open class Row(
        val cells: List<String>,
        val widths: List<Float>,
        val bold: Boolean = false,
        val header: Boolean = false,
        val plain: Boolean = false,
    ) : Block() {
        private val paints = cells.indices.map { i ->
            when {
                header -> bodyBold
                bold -> bodyBold
                i == 2 && !plain -> small // unit column
                else -> body
            }
        }
        private val wrapped = cells.mapIndexed { i, t -> wrap(t, paints[i], widths[i] - 2 * PAD) }
        override val height = maxOf(12f, wrapped.maxOf { it.size } * 8.6f + 3.4f)
        override fun draw(c: Canvas, x: Float, y: Float) {
            if (header) {
                fill.color = headFill
                c.drawRect(x, y, x + widths.sum(), y + height, fill)
            }
            var cx = x
            cells.indices.forEach { i ->
                c.drawRect(cx, y, cx + widths[i], y + height, line)
                val p = paints[i]
                wrapped[i].forEachIndexed { k, s ->
                    // Descriptions (and the revision table) left-aligned; numbers and units centred.
                    val tx = if (i == 1 || plain) cx + PAD else cx + (widths[i] - p.measureText(s)) / 2
                    c.drawText(s, tx, y + 9f + k * 8.6f, p)
                }
                cx += widths[i]
            }
        }
    }

    private class HeaderRow(cells: List<String>, widths: List<Float>, plain: Boolean = false) : Row(cells, widths, header = true, plain = plain)

    private class NoteRow(val label: String, val textValue: String) : Block() {
        private val lines = wrap(textValue, body, WIDTH - 22f - 2 * PAD)
        override val height = lines.size * 8.6f + 3.4f
        override fun draw(c: Canvas, x: Float, y: Float) {
            c.drawText(label, x + PAD, y + 9f, body)
            lines.forEachIndexed { k, s -> c.drawText(s, x + 22f, y + 9f + k * 8.6f, body) }
        }
    }

    // ---- title block and footer

    private const val TITLE_H = 104f

    /** Draws the title block; returns the y where content starts. */
    private fun titleBlock(c: Canvas, s: DataSheet, sheetNo: Int, sheets: Int): Float {
        val x = MARGIN
        val y = MARGIN
        val h1 = 46f
        val h2 = 26f
        val h3 = 26f
        c.drawRect(x, y, x + WIDTH, y + h1 + h2 + h3, frame)
        // Row 1: Flowstead | title and tag | document data
        val w1 = 170f
        val w3 = 150f
        val w2 = WIDTH - w1 - w3
        c.drawLine(x + w1, y, x + w1, y + h1, line)
        c.drawLine(x + w1 + w2, y, x + w1 + w2, y + h1, line)
        // Flowstead's mark and name: every data sheet is a Flowstead document.
        FlowsteadLogo.drawLockup(c, x + PAD + 2, y + 6f, 24f)
        c.drawText(s.kind, x + PAD + 2, y + h1 - 6f, small)
        val titlePaint = text(11f, bold = true)
        wrap(s.title, titlePaint, w2 - 2 * PAD).take(2).forEachIndexed { k, t ->
            c.drawText(t, x + w1 + (w2 - titlePaint.measureText(t)) / 2, y + 16f + k * 13f, titlePaint)
        }
        val tagPaint = text(10f, bold = true)
        val tag = s.heading
        c.drawText(tag, x + w1 + (w2 - tagPaint.measureText(tag)) / 2, y + h1 - 7f, tagPaint)
        val dx = x + w1 + w2
        val docLines = listOf(
            "Doc. No." to s.documentNumber.ifBlank { "—" },
            "Rev." to s.revision,
            "Date" to s.date,
            "Sheet" to "$sheetNo of $sheets",
        )
        docLines.forEachIndexed { k, (label, value) ->
            val ly = y + 10f + k * 10.5f
            c.drawText(label, dx + PAD + 1, ly, small)
            c.drawText(value, dx + 46f, ly, bodyBold)
        }
        // Rows 2 and 3: labelled boxes.
        fun boxes(top: Float, h: Float, items: List<Pair<String, String>>) {
            c.drawLine(x, top, x + WIDTH, top, line)
            val w = WIDTH / items.size
            items.forEachIndexed { k, (label, value) ->
                val bx = x + k * w
                if (k > 0) c.drawLine(bx, top, bx, top + h, line)
                c.drawText(label.uppercase(), bx + PAD + 1, top + 8f, small)
                wrap(value, body, w - 2 * PAD - 2).take(2).forEachIndexed { j, t -> c.drawText(t, bx + PAD + 1, top + 16.5f + j * 7.8f, body) }
            }
        }
        boxes(
            y + h1, h2,
            listOf("Company" to s.info.company, "Project" to s.info.project, "Project No." to s.info.projectNumber, "Client" to s.info.client, "Site / location" to s.info.site),
        )
        boxes(
            y + h1 + h2, h3,
            listOf("Service" to s.service, "Units" to s.unitSystem, "Prepared" to s.info.preparedBy, "Checked" to s.info.checkedBy, "Approved" to s.info.approvedBy),
        )
        return y + TITLE_H
    }

    private fun footer(c: Canvas, s: DataSheet) {
        val fy = PAGE_H - MARGIN + 4f
        c.drawLine(MARGIN, fy - 9f, MARGIN + WIDTH, fy - 9f, line)
        c.drawText("Generated with ${s.footer}. Process values are simulation results; verify before design use.", MARGIN, fy, small)
    }

    /** Word-wraps [text] to [width]; explicit newlines are kept and over-long words are broken. */
    internal fun wrap(text: String, paint: Paint, width: Float): List<String> {
        if (text.isEmpty()) return listOf("")
        val out = mutableListOf<String>()
        for (para in text.split('\n')) {
            var cur = ""
            for (word in para.split(' ')) {
                val candidate = if (cur.isEmpty()) word else "$cur $word"
                if (paint.measureText(candidate) <= width) { cur = candidate; continue }
                if (cur.isNotEmpty()) out += cur
                var w = word
                while (paint.measureText(w) > width && w.length > 1) {
                    var cut = w.length - 1
                    while (cut > 1 && paint.measureText(w, 0, cut) > width) cut--
                    out += w.substring(0, cut)
                    w = w.substring(cut)
                }
                cur = w
            }
            out += cur
        }
        return out
    }
}
