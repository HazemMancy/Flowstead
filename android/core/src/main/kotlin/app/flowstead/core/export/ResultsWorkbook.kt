/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.export

import app.flowstead.core.api.Phases
import app.flowstead.core.model.DisplayValue
import app.flowstead.core.model.DocumentInfo
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.ResultKeys
import app.flowstead.core.model.ResultView
import app.flowstead.core.model.StoredSolve
import app.flowstead.core.model.displayName
import app.flowstead.core.model.modelHash
import app.flowstead.core.units.Quantity
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.UnitSet
import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

sealed interface Cell {
    data class Text(val text: String) : Cell
    /** A number exactly as the engine returned it, converted to the display unit. */
    data class Number(val value: Double) : Cell
    data object Empty : Cell
}

data class Sheet(val name: String, val rows: List<List<Cell>>)

/**
 * The results of one solve as spreadsheet tables. Every number is an engine result converted to the
 * user's units; a value the engine did not return is an empty cell, never a guess (brief §4).
 */
object ResultsWorkbook {
    fun build(projectName: String, flowsheet: Flowsheet, solve: StoredSolve, units: UnitSet, appVersion: String = ""): List<Sheet> {
        val view = ResultView(solve, flowsheet.modelHash())
        val ctx = UnitContext(flowsheet.atmosphericPressurePa)
        val header = header(projectName, flowsheet.documentInfo ?: DocumentInfo(), solve, units, view.stale, appVersion)
        return listOf(
            Sheet("Streams", header + streams(flowsheet, view, units, ctx)),
            Sheet("Unit operations", header + unitOps(flowsheet, view, units, ctx)),
            Sheet("Solver log", header + log(flowsheet, solve)),
        )
    }

    private fun t(s: String) = Cell.Text(s)

    /** The project's title block (as on its data sheets, PFD and H&MB), then the solve it comes from. */
    private fun header(projectName: String, info: DocumentInfo, solve: StoredSolve, units: UnitSet, stale: Boolean, appVersion: String): List<List<Cell>> = buildList {
        val r = solve.result
        add(listOf(t("Project"), t(info.project.ifBlank { projectName })))
        add(listOf(t("Company"), t(info.company)))
        add(listOf(t("Project No."), t(info.projectNumber)))
        add(listOf(t("Client"), t(info.client)))
        add(listOf(t("Site / location"), t(info.site)))
        add(listOf(t("Prepared / Checked / Approved"), t(listOf(info.preparedBy, info.checkedBy, info.approvedBy).joinToString(" / "))))
        add(listOf(t("Software"), t("Flowstead" + (if (appVersion.isBlank()) "" else " $appVersion"))))
        add(listOf(t("Solved at (UTC)"), t(DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(Instant.ofEpochMilli(solve.solvedAtMillis).atOffset(ZoneOffset.UTC).withNano(0)))))
        add(listOf(t("Status"), t(r.status.name.lowercase())))
        add(listOf(t("Engine"), t("Flowstead engine ${r.engineVersion}, powered by DWSIM ${r.dwsimVersion}")))
        add(listOf(t("Units"), t(units.name)))
        if (stale) add(listOf(t("Warning"), t("The flowsheet changed after this solve; these results are out of date.")))
        add(emptyList())
    }

    private fun unitLabel(label: String, q: Quantity, units: UnitSet): String {
        val symbol = units.unitFor(q).symbol
        return if (symbol.isEmpty()) label else "$label [$symbol]"
    }

    private fun cell(v: DisplayValue, q: Quantity, units: UnitSet, ctx: UnitContext): Cell = when (v) {
        is DisplayValue.Calculated -> Cell.Number(units.unitFor(q).fromSi(v.si, ctx))
        is DisplayValue.Specified -> Cell.Number(units.unitFor(q).fromSi(v.si, ctx))
        is DisplayValue.Error -> t("error: ${v.reason}")
        is DisplayValue.Missing -> Cell.Empty
    }

    private fun streams(fs: Flowsheet, view: ResultView, units: UnitSet, ctx: UnitContext): List<List<Cell>> {
        val streams = fs.objects.filter { it.type == ObjectType.MaterialStream }
        if (streams.isEmpty()) return listOf(listOf(t("No material streams")))
        return buildList {
            add(listOf(t("Property")) + streams.map { t(it.tag) })
            for (key in ResultKeys.STREAM) {
                val q = ResultKeys.quantity(key)
                add(listOf(t(unitLabel(ResultKeys.label(key), q, units))) + streams.map { cell(view.value(it.id, key), q, units, ctx) })
            }
            add(emptyList())
            add(listOf(t("Overall composition [mole fraction]")))
            for (c in fs.compounds) {
                add(listOf(t(c)) + streams.map { cell(view.composition(it.id, Phases.OVERALL, c), Quantity.Ratio, units, ctx) })
            }
        }
    }

    private fun unitOps(fs: Flowsheet, view: ResultView, units: UnitSet, ctx: UnitContext): List<List<Cell>> = buildList {
        add(listOf(t("Tag"), t("Type"), t("Property"), t("Value"), t("Unit")))
        for (o in fs.objects.filter { it.type != ObjectType.MaterialStream }) {
            for (key in ResultKeys.forType(o.type)) {
                val q = ResultKeys.quantityFor(o, key)
                add(listOf(t(o.tag), t(o.type.displayName()), t(ResultKeys.label(key)), cell(view.value(o.id, key), q, units, ctx), t(units.unitFor(q).symbol)))
            }
        }
    }

    private fun log(fs: Flowsheet, solve: StoredSolve): List<List<Cell>> = buildList {
        add(listOf(t("Level"), t("Object"), t("Message")))
        val tags = fs.objects.associate { it.id to it.tag }
        for (m in solve.result.messages) {
            add(listOf(t(m.level.name.lowercase()), m.objectId?.let { t(tags[it] ?: it) } ?: Cell.Empty, t(m.text)))
        }
    }

    /** Up to 10 significant figures, never in exponent form, so spreadsheets read it as a plain number. */
    fun plain(value: Double): String =
        BigDecimal(value).round(MathContext(10)).stripTrailingZeros().toPlainString().let { if (it == "-0") "0" else it }
}

object Csv {
    /** All sheets in one file, each under its name, separated by a blank line. RFC 4180 quoting. */
    fun write(sheets: List<Sheet>): String = buildString {
        sheets.forEachIndexed { i, sheet ->
            if (i > 0) append("\r\n")
            append(field(sheet.name)).append("\r\n")
            for (row in sheet.rows) {
                append(row.joinToString(",") { cell -> field(text(cell)) }).append("\r\n")
            }
        }
    }

    private fun text(c: Cell) = when (c) {
        is Cell.Text -> c.text
        is Cell.Number -> ResultsWorkbook.plain(c.value)
        Cell.Empty -> ""
    }

    private fun field(s: String): String {
        // A leading =, + or @ would be run as a formula by spreadsheet apps; tags are user text.
        // (Not "-": negative numbers start with it.)
        val safe = if (s.isNotEmpty() && s[0] in "=+@") "'$s" else s
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }
}
