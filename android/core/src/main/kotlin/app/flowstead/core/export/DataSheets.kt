/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.export

import app.flowstead.core.api.Phases
import app.flowstead.core.format.ValueFormat
import app.flowstead.core.model.ChoiceKey
import app.flowstead.core.model.DisplayValue
import app.flowstead.core.model.DocumentInfo
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.LineService
import app.flowstead.core.model.LineSizing
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.PipeSizes
import app.flowstead.core.model.PortKind
import app.flowstead.core.model.PortSide
import app.flowstead.core.model.Ports
import app.flowstead.core.model.ReactionType
import app.flowstead.core.model.ReactorKeys
import app.flowstead.core.model.Reactors
import app.flowstead.core.model.ResultKeys
import app.flowstead.core.model.ResultView
import app.flowstead.core.model.SheetKeys
import app.flowstead.core.model.SimObject
import app.flowstead.core.model.SizingCriteria
import app.flowstead.core.model.SpecKey
import app.flowstead.core.model.StoredSolve
import app.flowstead.core.model.displayName
import app.flowstead.core.model.modelHash
import app.flowstead.core.model.streamAt
import app.flowstead.core.units.EngUnit
import app.flowstead.core.units.Quantity
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.UnitSet
import app.flowstead.core.units.Units
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * One equipment data sheet, laid out as an engineering contractor's: a title block, numbered lines in
 * sections, notes and a revision block. Every value is text already formatted for print. Process
 * numbers are only the engine's results, or "—" when it returned none; inputs are the user's.
 */
data class DataSheet(
    val title: String,
    val tag: String,
    val service: String,
    val info: DocumentInfo,
    val documentNumber: String,
    val revision: String,
    val revisionDescription: String,
    val date: String,
    val unitSystem: String,
    val sections: List<Section>,
    val notes: List<String>,
    val footer: String,
    val paper: Paper = Paper.A4Portrait,
    /** Caption under the Flowstead logo, naming the kind of document. */
    val kind: String = "PROCESS DATA SHEET",
    /** Second title line; "TAG: [tag]" when null. */
    val subtitle: String? = null,
) {
    enum class Paper { A4Portrait, A3Landscape }

    val heading: String get() = subtitle ?: "TAG: $tag"

    /** [columns] head the value columns; each [Line] has a description, a unit and one value per column. */
    data class Section(val title: String, val columns: List<String>, val lines: List<Line>)

    data class Line(val description: String, val unit: String, val values: List<String>, val bold: Boolean = false)
}

object DataSheets {
    const val MISSING = "—"

    /** Unit operations that get a data sheet (streams and logical blocks don't). */
    fun isEquipment(t: ObjectType): Boolean = !t.isStream && t !in setOf(ObjectType.Recycle, ObjectType.Adjust, ObjectType.Set, ObjectType.EnergyRecycle, ObjectType.ReliefValve)

    fun build(
        projectName: String,
        fs: Flowsheet,
        solve: StoredSolve?,
        units: UnitSet,
        obj: SimObject,
        today: String,
        appVersion: String,
    ): DataSheet {
        val view = ResultView(solve, fs.modelHash())
        val ctx = UnitContext(fs.atmosphericPressurePa)
        val b = Builder(fs, view, units, ctx, obj)
        val info = (fs.documentInfo ?: DocumentInfo()).let { if (it.project.isBlank()) it.copy(project = projectName) else it }
        val sections = buildList {
            add(b.general(solve))
            b.operating()?.let(::add)
            b.inputs()?.let(::add)
            b.performance()?.let(::add)
            b.lineSizing()?.let(::add)
            b.reactions()?.let(::add)
            b.composition()?.let(::add)
            add(b.design())
        }
        return DataSheet(
            title = obj.type.displayName().uppercase() + " DATA SHEET",
            tag = obj.tag,
            service = obj.sheet[SheetKeys.SERVICE].orEmpty(),
            info = info,
            documentNumber = obj.sheet[SheetKeys.DOCUMENT_NUMBER].orEmpty(),
            revision = obj.sheet[SheetKeys.REVISION]?.ifBlank { null } ?: "A",
            revisionDescription = obj.sheet[SheetKeys.REVISION_DESCRIPTION]?.ifBlank { null } ?: "Issued for review",
            date = today,
            unitSystem = units.name,
            sections = sections,
            notes = b.notes(solve),
            footer = "Flowstead $appVersion" + (solve?.result?.let { " · DWSIM ${it.dwsimVersion}" } ?: ""),
        )
    }

    private class Builder(
        val fs: Flowsheet,
        val view: ResultView,
        val units: UnitSet,
        val ctx: UnitContext,
        val obj: SimObject,
    ) {
        private fun unit(q: Quantity): EngUnit = units.unitFor(q)
        private fun symbol(u: EngUnit) = u.symbol.ifEmpty { if (u.quantity == Quantity.Fraction) "frac." else "–" }
        private fun num(si: Double, u: EngUnit) = ValueFormat.number(u.fromSi(si, ctx), u.decimals)
        private fun value(v: DisplayValue, u: EngUnit) = when (v) {
            is DisplayValue.Calculated -> num(v.si, u)
            is DisplayValue.Specified -> num(v.si, u)
            else -> MISSING
        }

        fun general(solve: StoredSolve?) = DataSheet.Section(
            "GENERAL", listOf(""),
            listOf(
                DataSheet.Line("Tag number", "", listOf(obj.tag), bold = true),
                DataSheet.Line("Equipment type", "", listOf(obj.type.displayName())),
                DataSheet.Line("Service", "", listOf(obj.sheet[SheetKeys.SERVICE].orEmpty())),
                DataSheet.Line("Property package", "", listOf(fs.propertyPackage ?: MISSING)),
                DataSheet.Line("Simulation status", "", listOf(status(solve))),
            ),
        )

        private fun status(solve: StoredSolve?): String {
            val r = solve?.result ?: return "Not solved"
            val at = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").format(Instant.ofEpochMilli(solve.solvedAtMillis).atOffset(ZoneOffset.UTC))
            val s = r.status.name.lowercase().replaceFirstChar { it.uppercase() }
            return "$s, $at" + if (view.stale) " (out of date)" else ""
        }

        /** Every connected material stream as a column; overall, vapour and liquid rows. */
        fun operating(): DataSheet.Section? {
            val ports = Ports.of(obj.type).filter { it.kind == PortKind.Material }
            val streams = ports.mapNotNull { p -> fs.streamAt(obj.id, p)?.let { p to it } }
            if (streams.isEmpty()) return null
            val columns = streams.map { (p, s) -> (if (p.side == PortSide.Inlet) "IN" else "OUT") + " · ${s.tag}" + if (ports.count { it.side == p.side } > 1) "\n${p.label}" else "" }
            val ids = streams.map { it.second.id }
            fun row(label: String, q: Quantity, get: (String) -> DisplayValue) =
                unit(q).let { u -> DataSheet.Line(label, symbol(u), ids.map { value(get(it), u) }) }
            fun overall(label: String, key: String) = row(label, ResultKeys.quantity(key)) { view.value(it, key) }
            fun phase(label: String, phase: String, key: String) = row(label, ResultKeys.quantity(key)) { view.phaseValue(it, phase, key) }
            fun present(phase: String) = ids.any { phase in view.presentPhases(it) }
            val lines = buildList {
                add(overall("Temperature", "temperature"))
                add(overall("Pressure", "pressure"))
                add(overall("Vapour fraction (molar)", "vapourFraction"))
                add(overall("Molar flow", "molarFlow"))
                add(overall("Mass flow", "massFlow"))
                add(phase("Actual volume flow", Phases.OVERALL, "volumeFlow"))
                add(phase("Density", Phases.OVERALL, "density"))
                add(phase("Molecular weight", Phases.OVERALL, "molecularWeight"))
                add(overall("Mass enthalpy", "massEnthalpy"))
                for ((ph, name) in listOf(Phases.VAPOUR to "Vapour", Phases.LIQUID1 to "Liquid", Phases.LIQUID2 to "Aqueous")) {
                    if (!present(ph)) continue
                    add(DataSheet.Line(name.uppercase(), "", ids.map { "" }, bold = true))
                    add(phase("$name mass flow", ph, "massFlow"))
                    add(phase("$name actual volume flow", ph, "volumeFlow"))
                    add(phase("$name density", ph, "density"))
                    add(phase("$name viscosity", ph, "viscosity"))
                    add(phase("$name molecular weight", ph, "molecularWeight"))
                    add(phase("$name Cp", ph, "massHeatCapacity"))
                    if (ph == Phases.VAPOUR) add(phase("Compressibility factor Z", ph, "compressibilityFactor"))
                }
            }
            return DataSheet.Section("OPERATING CONDITIONS", columns, lines)
        }

        /** The user's specifications and method choices. */
        fun inputs(): DataSheet.Section? {
            val lines = buildList {
                if (obj.type == ObjectType.PipeSegment || obj.type == ObjectType.OrificePlate) {
                    val size = obj.refs[LineSizing.NPS]?.let { PipeSizes.size(it) }
                    val sch = size?.let { s -> obj.refs[LineSizing.SCHEDULE]?.let { s.schedule(it) } }
                    if (size != null && sch != null) {
                        val u = small()
                        add(DataSheet.Line("Nominal size", "", listOf(size.label)))
                        add(DataSheet.Line("Schedule (ASME B36.10M / B36.19M)", "", listOf(sch.label)))
                        add(DataSheet.Line("Wall thickness", symbol(u), listOf(num(sch.wallM, u))))
                    }
                }
                for (key in SpecKey.forType(obj.type) + obj.specs.keys.filter { it.startsWith(SpecKey.SPLIT_PREFIX) }.sorted()) {
                    val spec = obj.specs[key] ?: continue
                    val q = runCatching { SpecKey.quantity(key) }.getOrNull() ?: continue
                    val u = if (key in SpecKey.SMALL_LENGTHS) small() else if (key == SpecKey.EFFICIENCY) Units.percent else unit(q)
                    val label = if (key.startsWith(SpecKey.SPLIT_PREFIX)) "Split to overhead: ${key.removePrefix(SpecKey.SPLIT_PREFIX)}" else SpecKey.label(key)
                    add(DataSheet.Line(label, symbol(u), listOf(num(spec.si, u))))
                }
                for ((key, label, options) in choices()) {
                    val chosen = obj.refs[key] ?: options.firstOrNull()?.first ?: continue
                    add(DataSheet.Line(label, "", listOf(options.firstOrNull { it.first == chosen }?.second ?: chosen)))
                }
            }
            return if (lines.isEmpty()) null else DataSheet.Section("DESIGN BASIS (INPUT)", listOf(""), lines)
        }

        private fun small(): EngUnit = if (unit(Quantity.Length) == Units.ft) Units.inch else Units.mm

        /** Method choices as (ref key, label, options); the first option is the default when none is stored. */
        private fun choices(): List<Triple<String, String, List<Pair<String, String>>>> = when (obj.type) {
            ObjectType.PipeSegment -> listOf(
                Triple(ChoiceKey.PIPE_CORRELATION, "Two-phase correlation", ChoiceKey.PIPE_CORRELATIONS),
                Triple(LineSizing.SERVICE, "Service (line sizing)", LineSizing.SERVICE_OPTIONS.map { (it.first ?: "") to it.second }),
            )
            ObjectType.OrificePlate -> listOf(Triple(ChoiceKey.ORIFICE_TAPS, "Pressure taps", ChoiceKey.ORIFICE_TAP_OPTIONS))
            ObjectType.DistillationColumn, ObjectType.RefluxedAbsorber ->
                listOf(Triple(ChoiceKey.CONDENSER, "Condenser", ChoiceKey.CONDENSER_OPTIONS), Triple(ChoiceKey.COLUMN_SOLVER, "Solver", ChoiceKey.COLUMN_SOLVERS))
            ObjectType.Absorber, ObjectType.ReboiledAbsorber -> listOf(Triple(ChoiceKey.COLUMN_SOLVER, "Solver", ChoiceKey.COLUMN_SOLVERS))
            // Keys have no default: listed only when chosen.
            ObjectType.ShortcutColumn -> listOf(ChoiceKey.LIGHT_KEY to "Light key", ChoiceKey.HEAVY_KEY to "Heavy key")
                .mapNotNull { (k, l) -> obj.refs[k]?.let { Triple(k, l, listOf(it to it)) } }
            else -> if (Reactors.isReactor(obj.type)) listOf(Triple(ReactorKeys.MODE, "Operation", ReactorKeys.MODES)) else emptyList()
        }

        /** The engine's results for the unit, in the editor's order, plus text results. */
        fun performance(): DataSheet.Section? {
            val keys = ResultKeys.forObject(fs, obj)
            if (keys.isEmpty()) return null
            val lines = keys.map { key ->
                val u = unit(ResultKeys.quantityFor(obj, key))
                DataSheet.Line(ResultKeys.label(key), symbol(u), listOf(value(view.value(obj.id, key), u)))
            } + listOfNotNull(view.text(obj.id, "flowRegime")?.let { DataSheet.Line("Flow regime(s)", "", listOf(it)) })
            return DataSheet.Section("PERFORMANCE (CALCULATED)", listOf(""), lines)
        }

        fun lineSizing(): DataSheet.Section? {
            if (obj.type != ObjectType.PipeSegment) return null
            val service: LineService = LineSizing.service(fs, obj, view) ?: return null
            val checks = LineSizing.checks((fs.sizingCriteria ?: SizingCriteria()).forService(service), obj.id, view)
            if (checks.isEmpty()) return null
            val lines = checks.map { c ->
                val u = unit(c.quantity)
                DataSheet.Line(
                    c.label + (c.note?.let { " ($it)" } ?: ""), symbol(u),
                    listOf((if (c.isMaximum) "≤ " else "≥ ") + num(c.limit, u), num(c.value, u), if (c.passes) "OK" else "EXCEEDED"),
                )
            }
            return DataSheet.Section("LINE SIZING — ${service.label.uppercase()} SERVICE", listOf("Limit", "Value", "Result"), lines)
        }

        fun reactions(): DataSheet.Section? {
            if (!Reactors.isReactor(obj.type)) return null
            val rs = Reactors.reactions(fs, obj)
            if (rs.isEmpty()) return null
            val lines = rs.map { r ->
                val basis = when (r.type) {
                    ReactionType.Conversion -> r.conversion?.let { "X(${r.baseCompound}) = ${ValueFormat.number(it * 100, 1)} %" }
                    ReactionType.Equilibrium -> "Equilibrium, ${r.phase.name.lowercase()} phase"
                    ReactionType.Kinetic -> "Kinetic (Arrhenius)"
                }
                DataSheet.Line(r.name, "", listOf(r.equation(), basis.orEmpty()))
            }
            return DataSheet.Section("REACTIONS", listOf("Equation", "Basis"), lines)
        }

        /** Overall mole fractions of every connected material stream; omitted for very long component lists. */
        fun composition(): DataSheet.Section? {
            val streams = Ports.of(obj.type).filter { it.kind == PortKind.Material }.mapNotNull { fs.streamAt(obj.id, it) }
            if (streams.isEmpty() || fs.compounds.isEmpty() || fs.compounds.size > 40) return null
            val u = Units.fraction
            val lines = fs.compounds.map { c -> DataSheet.Line(c, "mol frac.", streams.map { value(view.composition(it.id, Phases.OVERALL, c), u) }) }
            return DataSheet.Section("COMPOSITION (OVERALL)", streams.map { it.tag }, lines)
        }

        /** Mechanical fields: whatever the user typed, blank lines otherwise for completing by hand. */
        fun design() = DataSheet.Section(
            "MECHANICAL / DESIGN DATA", listOf(""),
            SheetKeys.DESIGN.map { (k, label) -> DataSheet.Line(label, "", listOf(obj.sheet[k].orEmpty())) },
        )

        fun notes(solve: StoredSolve?): List<String> = buildList {
            add(
                "Process data from a steady-state simulation (Flowstead, DWSIM engine" +
                    (solve?.result?.dwsimVersion?.let { " $it" } ?: "") + "), property package ${fs.propertyPackage ?: "not set"}.",
            )
            add("“—” marks a value the simulation did not return; nothing on this sheet is estimated.")
            add("Pressures are absolute unless the unit says gauge.")
            if (solve == null) add("The flowsheet has not been solved: operating data are blank.")
            if (view.stale) add("RESULTS OUT OF DATE: the flowsheet changed after the last solve.")
            obj.sheet[SheetKeys.NOTES]?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.let { addAll(it) }
        }
    }
}
