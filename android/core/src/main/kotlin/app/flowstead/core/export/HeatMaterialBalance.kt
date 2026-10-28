/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.export

import app.flowstead.core.api.Phases
import app.flowstead.core.format.ValueFormat
import app.flowstead.core.model.DisplayValue
import app.flowstead.core.model.DocumentInfo
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.ResultKeys
import app.flowstead.core.model.ResultView
import app.flowstead.core.model.SimObject
import app.flowstead.core.model.StoredSolve
import app.flowstead.core.model.StreamLayout
import app.flowstead.core.model.displayName
import app.flowstead.core.model.modelHash
import app.flowstead.core.units.EngUnit
import app.flowstead.core.units.Quantity
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.UnitSet
import app.flowstead.core.units.Units

/**
 * The flowsheet's heat and material balance: every material stream as a column (where it comes from and
 * goes to, conditions, flows, properties, composition), then energy streams and unit-operation duties.
 * Built as [DataSheet]s on A3 landscape, so it prints and exports with the project's title block; numbers
 * are the engine's only ("—" when it returned none).
 */
object HeatMaterialBalance {
    /** Streams per sheet that fit A3 landscape in the PDF; the workbook takes them all on one sheet. */
    const val STREAMS_PER_PDF_SHEET = 10

    fun build(
        projectName: String,
        fs: Flowsheet,
        solve: StoredSolve?,
        units: UnitSet,
        today: String,
        appVersion: String,
        streamsPerSheet: Int = Int.MAX_VALUE,
    ): List<DataSheet> {
        val view = ResultView(solve, fs.modelHash())
        val ctx = UnitContext(fs.atmosphericPressurePa)
        val info = (fs.documentInfo ?: DocumentInfo()).let { if (it.project.isBlank()) it.copy(project = projectName) else it }
        val streams = fs.objects.filter { it.type == ObjectType.MaterialStream }
        val f = Format(units, ctx)
        val chunks = if (streams.isEmpty()) listOf(emptyList()) else streams.chunked(streamsPerSheet.coerceAtLeast(1))
        val notes = notes(fs, solve, view)
        val footer = "Flowstead $appVersion" + (solve?.result?.let { " · DWSIM ${it.dwsimVersion}" } ?: "")
        return chunks.mapIndexed { i, chunk ->
            val sections = buildList {
                if (chunk.isNotEmpty()) {
                    add(conditions(fs, view, f, chunk))
                    composition(fs, view, f, chunk)?.let(::add)
                }
                // Energy streams and duties once, on the last sheet.
                if (i == chunks.lastIndex) {
                    energy(fs, view, f)?.let(::add)
                    duties(fs, view, f)?.let(::add)
                }
            }
            val range = if (chunks.size > 1) " — STREAMS ${chunk.first().tag} TO ${chunk.last().tag}" else ""
            DataSheet(
                title = "HEAT AND MATERIAL BALANCE",
                tag = "",
                service = fs.propertyPackage?.let { "Property package: $it" }.orEmpty(),
                info = info,
                documentNumber = info.hmbNumber,
                revision = info.revision.ifBlank { "A" },
                revisionDescription = "Issued for review",
                date = today,
                unitSystem = units.name,
                sections = sections,
                notes = notes,
                footer = footer,
                paper = DataSheet.Paper.A3Landscape,
                kind = "HEAT & MATERIAL BALANCE",
                subtitle = (info.project.ifBlank { projectName }).uppercase() + range,
            )
        }
    }

    private class Format(val units: UnitSet, val ctx: UnitContext) {
        fun unit(q: Quantity): EngUnit = units.unitFor(q)
        fun symbol(u: EngUnit) = u.symbol.ifEmpty { if (u.quantity == Quantity.Fraction) "frac." else "–" }
        fun value(v: DisplayValue, u: EngUnit) = when (v) {
            is DisplayValue.Calculated -> ValueFormat.number(u.fromSi(v.si, ctx), u.decimals)
            is DisplayValue.Specified -> ValueFormat.number(u.fromSi(v.si, ctx), u.decimals)
            else -> DataSheets.MISSING
        }
    }

    /** "From" and "To" for a stream: the unit operations it connects, or Feed / Product. */
    private fun ends(fs: Flowsheet, s: SimObject): Pair<String, String> {
        val tags = fs.objects.associate { it.id to it.tag }
        val from = fs.connections.firstOrNull { it.toObject == s.id }?.let { tags[it.fromObject] } ?: "Feed"
        val to = fs.connections.firstOrNull { it.fromObject == s.id }?.let { tags[it.toObject] } ?: "Product"
        return from to to
    }

    private fun conditions(fs: Flowsheet, view: ResultView, f: Format, streams: List<SimObject>): DataSheet.Section {
        val ids = streams.map { it.id }
        val lines = buildList {
            val ends = streams.map { ends(fs, it) }
            add(DataSheet.Line("From", "", ends.map { it.first }))
            add(DataSheet.Line("To", "", ends.map { it.second }))
            // HYSYS workbook layout: Overall, then each phase present in any of these streams.
            for (section in StreamLayout.sections(view, ids)) {
                add(DataSheet.Line(section.title.uppercase(), "", ids.map { "" }, bold = true))
                for (row in section.rows) {
                    val u = StreamLayout.unit(row, f.units)
                    add(DataSheet.Line(row.label, f.symbol(u), ids.map { f.value(StreamLayout.value(view, it, row), u) }))
                }
            }
        }
        return DataSheet.Section("MATERIAL STREAMS", streams.map { it.tag }, lines)
    }

    private fun composition(fs: Flowsheet, view: ResultView, f: Format, streams: List<SimObject>): DataSheet.Section? {
        if (fs.compounds.isEmpty()) return null
        val u = Units.fraction
        val lines = fs.compounds.map { c -> DataSheet.Line(c, "mol frac.", streams.map { f.value(view.composition(it.id, Phases.OVERALL, c), u) }) }
        return DataSheet.Section("COMPOSITION (OVERALL, MOLE FRACTION)", streams.map { it.tag }, lines)
    }

    private fun energy(fs: Flowsheet, view: ResultView, f: Format): DataSheet.Section? {
        val streams = fs.objects.filter { it.type == ObjectType.EnergyStream }
        if (streams.isEmpty()) return null
        val u = f.unit(Quantity.Power)
        val lines = streams.map { s ->
            val (from, to) = ends(fs, s)
            DataSheet.Line("${s.tag} ($from → $to)", f.symbol(u), listOf(f.value(view.value(s.id, "energyFlow"), u)))
        }
        return DataSheet.Section("ENERGY STREAMS", listOf(""), lines)
    }

    /** Every unit operation's duty and power results (heaters, coolers, compressors, columns, reactors…). */
    private fun duties(fs: Flowsheet, view: ResultView, f: Format): DataSheet.Section? {
        val lines = fs.objects.filter { !it.type.isStream }.flatMap { o ->
            ResultKeys.forObject(fs, o).filter { ResultKeys.quantityFor(o, it) == Quantity.Power }.map { key ->
                val u = f.unit(Quantity.Power)
                DataSheet.Line("${o.tag} — ${o.type.displayName()}: ${ResultKeys.label(key).lowercase()}", f.symbol(u), listOf(f.value(view.value(o.id, key), u)))
            }
        }
        return if (lines.isEmpty()) null else DataSheet.Section("UNIT OPERATION DUTIES", listOf(""), lines)
    }

    private fun notes(fs: Flowsheet, solve: StoredSolve?, view: ResultView): List<String> = buildList {
        add(
            "Steady-state simulation results (Flowstead, DWSIM engine" + (solve?.result?.dwsimVersion?.let { " $it" } ?: "") +
                "), property package ${fs.propertyPackage ?: "not set"}.",
        )
        add("“—” marks a value the simulation did not return; nothing here is estimated.")
        add("Pressures are absolute unless the unit says gauge.")
        if (solve == null) add("The flowsheet has not been solved: the balance is blank.")
        if (view.stale) add("RESULTS OUT OF DATE: the flowsheet changed after the last solve.")
    }
}
