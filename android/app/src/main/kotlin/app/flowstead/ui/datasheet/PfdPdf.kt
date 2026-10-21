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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import app.flowstead.core.model.DocumentInfo
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.PropertyTables
import app.flowstead.core.model.ResultView
import app.flowstead.core.units.UnitSet
import app.flowstead.ui.flowsheet.CanvasGeometry
import app.flowstead.ui.flowsheet.IconPalette
import app.flowstead.ui.flowsheet.drawUnitIcon
import java.io.OutputStream
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * The flowsheet as a process flow diagram on one A3 landscape page: the drawing (symbols, lines, tags and
 * the property tables shown on the flowsheet) centred and scaled to fit, inside a border, with the project's
 * title block along the bottom and the Flowstead name. Symbols are the canvas's own, drawn for print.
 */
object PfdPdf {
    private const val PAGE_W = 1191f // A3 landscape, points
    private const val PAGE_H = 842f
    private const val MARGIN = 20f
    private const val TITLE_H = 92f
    private const val GAP = 18f
    /** Largest magnification of a small flowsheet, so a two-object drawing doesn't fill the sheet. */
    private const val MAX_SCALE = 2.4f

    // World-unit text sizes (scaled with the drawing).
    private const val TAG_SIZE = 9f
    private const val TABLE_SIZE = 6.5f

    class Header(
        val projectName: String,
        val info: DocumentInfo,
        val date: String,
        val units: String,
        val propertyPackage: String?,
        val footer: String,
        val stale: Boolean,
        val solved: Boolean,
    )

    private val ink = Color.rgb(0x1A, 0x1A, 0x1A)
    private val muted = Color.rgb(0x5F, 0x66, 0x6D)
    private val grid = Color.rgb(0x80, 0x80, 0x80)

    // Print palette: black symbols on white, energy lines amber and dashed as on screen.
    private val palette = IconPalette(
        fill = ComposeColor.White,
        stroke = ComposeColor(0xFF1A1A1A),
        accent = ComposeColor(0xFF0E7C74),
        vapour = ComposeColor(0xFF1A1A1A),
        liquid = ComposeColor(0xFF1A1A1A),
        energy = ComposeColor(0xFFC77A12),
    )

    private fun paint(size: Float, bold: Boolean = false, color: Int = ink, mono: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        this.color = color
    }

    private val line = Paint().apply { color = grid; strokeWidth = 0.5f; style = Paint.Style.STROKE }
    private val frame = Paint().apply { color = ink; strokeWidth = 1.2f; style = Paint.Style.STROKE }

    fun write(fs: Flowsheet, view: ResultView, units: UnitSet, header: Header, out: OutputStream) {
        val doc = PdfDocument()
        try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W.toInt(), PAGE_H.toInt(), 1).create())
            val c = page.canvas
            c.drawRect(MARGIN, MARGIN, PAGE_W - MARGIN, PAGE_H - MARGIN, frame)
            val area = Rect(MARGIN + GAP, MARGIN + GAP, PAGE_W - MARGIN - GAP, PAGE_H - MARGIN - TITLE_H - GAP)
            drawing(c, fs, view, units, area)
            titleBlock(c, header)
            doc.finishPage(page)
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    private class TableLayout(val x: Float, val y: Float, val title: String, val rows: List<PropertyTables.Row>, val lw: Float, val vw: Float, val uw: Float) {
        val rowH = TABLE_SIZE * 1.45f
        val pad = TABLE_SIZE * 0.5f
        val w get() = maxOf(PfdPdf.tableHead.measureText(title), lw + vw + uw + 3 * pad) + 2 * pad
        val h get() = rowH * (rows.size + 1) + 2 * pad
    }

    private val tag = paint(TAG_SIZE, mono = true, color = ink)
    private val tableHead = paint(TABLE_SIZE, bold = true, mono = true)
    private val tableCell = paint(TABLE_SIZE, mono = true)
    private val tableUnit = paint(TABLE_SIZE, mono = true, color = muted)

    private fun drawing(c: Canvas, fs: Flowsheet, view: ResultView, units: UnitSet, area: Rect) {
        if (fs.objects.isEmpty()) {
            val p = paint(14f, color = muted)
            val msg = "The flowsheet is empty."
            c.drawText(msg, area.center.x - p.measureText(msg) / 2, area.center.y, p)
            return
        }
        val tables = fs.tables.mapNotNull { t ->
            val o = fs.objects.firstOrNull { it.id == t.objectId } ?: return@mapNotNull null
            val rows = PropertyTables.rows(fs, t, view, units)
            TableLayout(
                (o.x + t.dx).toFloat(), (o.y + t.dy).toFloat(), o.tag, rows,
                rows.maxOfOrNull { tableCell.measureText(it.label) } ?: 0f,
                rows.maxOfOrNull { tableCell.measureText(it.value) } ?: 0f,
                rows.maxOfOrNull { tableUnit.measureText(it.unit) } ?: 0f,
            )
        }

        // World bounds of everything drawn: symbols with their tags below, and the tables.
        var bounds = fs.objects.map { o ->
            val b = CanvasGeometry.bounds(o)
            val half = maxOf(b.width, tag.measureText(o.tag)) / 2
            Rect(b.center.x - half, b.top, b.center.x + half, b.bottom + 4f + TAG_SIZE * 1.3f)
        }.reduce(::union)
        tables.forEach { bounds = union(bounds, Rect(it.x, it.y, it.x + it.w, it.y + it.h)) }

        val scale = minOf(area.width / bounds.width.coerceAtLeast(1f), area.height / bounds.height.coerceAtLeast(1f), MAX_SCALE)
        val tx = area.left + (area.width - bounds.width * scale) / 2 - bounds.left * scale
        val ty = area.top + (area.height - bounds.height * scale) / 2 - bounds.top * scale

        c.save()
        c.translate(tx, ty)
        c.scale(scale, scale)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(c), Size(PAGE_W, PAGE_H)) {
            for (conn in fs.connections) {
                val (a, b) = CanvasGeometry.endpoints(fs, conn) { CanvasGeometry.center(it) } ?: continue
                val stream = fs.objects.firstOrNull { it.id == conn.fromObject && it.type.isStream } ?: fs.objects.firstOrNull { it.id == conn.toObject }
                val energy = stream?.type == ObjectType.EnergyStream
                val pts = CanvasGeometry.elbow(a, b)
                val path = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    pts.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(
                    path, if (energy) palette.energy else palette.stroke,
                    style = Stroke(width = 1.4f, cap = StrokeCap.Round, pathEffect = if (energy) PathEffect.dashPathEffect(floatArrayOf(5f, 4f)) else null),
                )
            }
            for (o in fs.objects) {
                val color = if (o.type == ObjectType.EnergyStream) palette.energy else palette.stroke
                drawUnitIcon(o.type, CanvasGeometry.center(o), CanvasGeometry.size(o.type), palette, strokeWidth = 1.4f, streamColor = color)
            }
        }
        for (o in fs.objects) {
            val b = CanvasGeometry.bounds(o)
            c.drawText(o.tag, b.center.x - tag.measureText(o.tag) / 2, b.bottom + 4f + TAG_SIZE, tag)
        }
        val box = Paint().apply { color = ink; strokeWidth = 0.6f; style = Paint.Style.STROKE }
        val fill = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
        for (t in tables) {
            c.drawRect(t.x, t.y, t.x + t.w, t.y + t.h, fill)
            c.drawRect(t.x, t.y, t.x + t.w, t.y + t.h, box)
            c.drawText(t.title, t.x + t.pad, t.y + t.pad + TABLE_SIZE, tableHead)
            c.drawLine(t.x, t.y + t.pad + t.rowH, t.x + t.w, t.y + t.pad + t.rowH, box)
            t.rows.forEachIndexed { i, r ->
                val y = t.y + t.pad + t.rowH * (i + 1) + TABLE_SIZE
                c.drawText(r.label, t.x + t.pad, y, tableCell)
                c.drawText(r.value, t.x + 2 * t.pad + t.lw + t.vw - tableCell.measureText(r.value), y, tableCell)
                c.drawText(r.unit, t.x + 3 * t.pad + t.lw + t.vw, y, tableUnit)
            }
        }
        c.restore()
    }

    /** Full-width title strip: Flowstead | title, project | boxes | document data. */
    private fun titleBlock(c: Canvas, h: Header) {
        val x = MARGIN
        val y = PAGE_H - MARGIN - TITLE_H
        val w = PAGE_W - 2 * MARGIN
        c.drawLine(x, y, x + w, y, frame)
        val small = paint(6f, color = muted)
        val body = paint(8f)
        val bold = paint(8f, bold = true)

        // Logo and software.
        val logoW = 190f
        FlowsteadLogo.drawLockup(c, x + 10f, y + 12f, 26f)
        c.drawText("STEADY-STATE PROCESS SIMULATION", x + 10f, y + 56f, small)
        c.drawText(h.footer, x + 10f, y + 68f, small)
        c.drawLine(x + logoW, y, x + logoW, y + TITLE_H, line)

        // Title and project.
        val titleW = 300f
        val tx = x + logoW
        val title = paint(15f, bold = true)
        val t1 = "PROCESS FLOW DIAGRAM"
        c.drawText(t1, tx + (titleW - title.measureText(t1)) / 2, y + 30f, title)
        val project = h.info.project.ifBlank { h.projectName }.uppercase()
        val pp = paint(10f, bold = true)
        c.drawText(project, tx + (titleW - pp.measureText(project)) / 2, y + 50f, pp)
        val sub = listOfNotNull(h.propertyPackage?.let { "Property package: $it" }, "Units: ${h.units}").joinToString("   ·   ")
        c.drawText(sub, tx + (titleW - small.measureText(sub)) / 2, y + 64f, small)
        val note = when {
            !h.solved -> "Not solved: tables show no values."
            h.stale -> "Results out of date: the flowsheet changed after the last solve."
            else -> null
        }
        note?.let { c.drawText(it, tx + (titleW - small.measureText(it)) / 2, y + 78f, paint(6f, color = Color.rgb(0xC8, 0x37, 0x2D))) }
        c.drawLine(tx + titleW, y, tx + titleW, y + TITLE_H, line)

        // Boxes: two rows of labelled fields.
        val docW = 170f
        val bx = tx + titleW
        val bw = w - logoW - titleW - docW
        fun row(top: Float, items: List<Pair<String, String>>) {
            val cw = bw / items.size
            items.forEachIndexed { k, (label, value) ->
                val cx = bx + k * cw
                if (k > 0) c.drawLine(cx, top, cx, top + TITLE_H / 2, line)
                c.drawText(label.uppercase(), cx + 4f, top + 10f, small)
                c.drawText(ellipsize(value, body, cw - 8f), cx + 4f, top + 26f, body)
            }
        }
        row(y, listOf("Company" to h.info.company, "Client" to h.info.client, "Project No." to h.info.projectNumber, "Site / location" to h.info.site))
        c.drawLine(bx, y + TITLE_H / 2, bx + bw, y + TITLE_H / 2, line)
        row(y + TITLE_H / 2, listOf("Prepared" to h.info.preparedBy, "Checked" to h.info.checkedBy, "Approved" to h.info.approvedBy))
        c.drawLine(bx + bw, y, bx + bw, y + TITLE_H, line)

        // Document data.
        val dx = bx + bw
        listOf(
            "Doc. No." to h.info.pfdNumber.ifBlank { "—" },
            "Rev." to h.info.revision.ifBlank { "A" },
            "Date" to h.date,
            "Sheet" to "1 of 1",
            "Size" to "A3 · not to scale",
        ).forEachIndexed { k, (label, value) ->
            val ly = y + 16f + k * 15f
            c.drawText(label, dx + 6f, ly, small)
            c.drawText(ellipsize(value, bold, docW - 56f), dx + 50f, ly, bold)
        }
    }

    private fun union(a: Rect, b: Rect) = Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))

    private fun ellipsize(text: String, p: Paint, width: Float): String {
        if (p.measureText(text) <= width) return text
        var t = text
        while (t.isNotEmpty() && p.measureText("$t…") > width) t = t.dropLast(1)
        return "$t…"
    }
}
