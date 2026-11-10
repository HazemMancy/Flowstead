/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.api.ObjectStatus
import app.flowstead.core.api.Phases
import app.flowstead.core.api.SolveResult

/**
 * Line sizing of a stream, as HYSYS's stream analysis (owner, D148): from the stream's own solved state, without a pipe
 * segment on the flowsheet. Design: every nominal size of a schedule, with the smallest that meets the criteria
 * marked; rating: one size (or an inside diameter). For each, ΔP per length, velocity, ρv² and the Mach number, and the
 * total ΔP over a length when one is given.
 *
 * Every number is the engine's: the stream's fluid (its solved temperature, pressure, flow and composition) is fed to a
 * horizontal, adiabatic test pipe of each size, 100 m long for the gradients (the stream's own conditions) and, if
 * given, one of the total length (the ΔP worked out along it, the fluid changing as it goes), all in one solve on the
 * stream's fluid package. The checks are line sizing's own (LineSizing.checks) against the project's criteria.
 */
object StreamLineSizing {
    const val TEST_LENGTH = 100.0
    /** The engine's default, commercial steel (DWSIM's 0.0457 mm). */
    const val DEFAULT_ROUGHNESS = 4.57e-5

    data class Candidate(val label: String, val nps: String?, val schedule: String?, val insideDiameter: Double)

    /**
     * [schedule] the schedule label (design, or rating by size); [nps] the size (rating); [insideDiameter] a diameter
     * given instead (rating); [service] null: from the stream's phases.
     */
    data class Input(
        val design: Boolean = true,
        val schedule: String = "40",
        val nps: String? = null,
        val insideDiameter: Double? = null,
        val roughness: Double = DEFAULT_ROUGHNESS,
        val totalLength: Double? = null,
        val service: LineService? = null,
    )

    data class Row(
        val candidate: Candidate,
        val error: String? = null,
        val gradient: Double? = null,
        val frictionGradient: Double? = null,
        val velocity: Double? = null,
        val momentumFlux: Double? = null,
        val mach: Double? = null,
        val totalDrop: Double? = null,
        val totalError: String? = null,
        val checks: List<SizingCheck> = emptyList(),
    ) {
        val passes: Boolean get() = error == null && checks.isNotEmpty() && checks.all { it.passes }
    }

    data class Outcome(val service: LineService, val rows: List<Row>, val recommended: Row?, val criteriaSet: Boolean)

    /** The stream's state from the last solve, or why there is none. */
    fun problem(fs: Flowsheet, results: ResultView, streamId: String): String? {
        val s = fs.objects.firstOrNull { it.id == streamId && it.type == ObjectType.MaterialStream } ?: return "Choose a material stream"
        if (results.stale) return "Solve the flowsheet first: the line is sized on the stream's solved state"
        fun v(k: String) = (results.value(s.id, k) as? DisplayValue.Calculated)?.si
        val w = v("massFlow")
        return when {
            v("temperature") == null || v("pressure") == null || w == null -> "Solve the flowsheet first: the line is sized on the stream's solved state"
            !(w > 0) -> "The stream has no flow"
            else -> null
        }
    }

    /**
     * The schedule names, one each ("40", "STD", "XS", "80", "10S" …), in table order. Above NPS 10, schedule 40 and
     * STD are different walls: a name picks, in every size, the wall that carries it.
     */
    val SCHEDULES: List<String> = PipeSizes.ALL.flatMap { s -> s.schedules.flatMap { it.label.split(" / ") } }.distinct().let { all ->
        val order = listOf("5S", "5", "10S", "10", "20", "30", "STD", "40", "40S", "60", "XS", "80", "80S", "100", "120", "140", "160", "XXS")
        order.filter { it in all } + all.filter { it !in order }
    }

    fun scheduleOf(size: PipeSizes.Size, name: String): PipeSizes.Schedule? =
        size.schedules.firstOrNull { name in it.label.split(" / ") } ?: size.schedule(name)

    /** The sizes tried: design, every size that has the schedule; rating, the one size or diameter. */
    fun candidates(input: Input): List<Candidate> = if (input.design) {
        PipeSizes.ALL.mapNotNull { s -> scheduleOf(s, input.schedule)?.let { Candidate("NPS ${s.nps}", s.nps, it.label, s.insideDiameterM(it)) } }
    } else {
        input.insideDiameter?.takeIf { it > 0 }?.let { listOf(Candidate("ID ${ValueFormatShort.mm(it)} mm", null, null, it)) }
            ?: PipeSizes.size(input.nps ?: "")?.let { s -> scheduleOf(s, input.schedule)?.let { listOf(Candidate("NPS ${s.nps}", s.nps, it.label, s.insideDiameterM(it))) } }
            ?: emptyList()
    }

    /** The service: the user's, else gas or liquid when the stream is single phase, else two-phase. */
    fun service(fs: Flowsheet, results: ResultView, streamId: String, given: LineService?): LineService {
        given?.let { return it }
        val vf = (results.value(streamId, "vapourFraction") as? DisplayValue.Calculated)?.si ?: return LineService.TwoPhase
        return when {
            vf >= 1 - 1e-6 -> LineService.Gas
            vf <= 1e-6 -> LineService.Liquid
            else -> LineService.TwoPhase
        }
    }

    /** The flowsheet the engine solves: the stream's fluid into a test pipe per size ("g<i>", 100 m; "t<i>", the total length). */
    fun flowsheet(fs: Flowsheet, results: ResultView, streamId: String, input: Input): Flowsheet {
        val base = SubflowsheetSolve.forObject(fs, streamId)
        fun v(k: String) = (results.value(streamId, k) as? DisplayValue.Calculated)?.si!!
        val x = base.compounds.associateWith { c -> (results.composition(streamId, Phases.OVERALL, c) as? DisplayValue.Calculated)?.si ?: 0.0 }.filterValues { it > 0 }
        val objects = mutableListOf<SimObject>()
        val connections = mutableListOf<Connection>()
        fun line(tag: String, d: Double, length: Double, increments: Int) {
            objects += SimObject("${tag}_in", "${tag}_in", ObjectType.MaterialStream, composition = x, specs = mapOf(
                SpecKey.TEMPERATURE to Spec(v("temperature"), "K"), SpecKey.PRESSURE to Spec(v("pressure"), "Pa"), SpecKey.MASS_FLOW to Spec(v("massFlow"), "kg/s")))
            objects += SimObject(tag, tag, ObjectType.PipeSegment, specs = mapOf(
                SpecKey.INNER_DIAMETER to Spec(d, "m"), SpecKey.LENGTH to Spec(length, "m"), SpecKey.ELEVATION_CHANGE to Spec(0.0, "m"),
                SpecKey.ROUGHNESS to Spec(input.roughness, "m"), SpecKey.INCREMENTS to Spec(increments.toDouble(), "")))
            objects += SimObject("${tag}_out", "${tag}_out", ObjectType.MaterialStream)
            connections += Connection("${tag}_in", 0, tag, 0)
            connections += Connection(tag, 0, "${tag}_out", 0)
        }
        candidates(input).forEachIndexed { i, c ->
            line("g$i", c.insideDiameter, TEST_LENGTH, 5)
            input.totalLength?.takeIf { it > 0 }?.let { line("t$i", c.insideDiameter, it, 20) }
        }
        return base.copy(
            objects = objects, connections = connections, subflowsheets = emptyList(), tables = emptyList(), safety = null,
            dynamics = null, caseStudies = emptyList(), spreadsheet = null, watch = emptyList(), scenarios = emptyList(),
        )
    }

    /** The table from the engine's answer to [flowsheet]. */
    fun outcome(fs: Flowsheet, results: ResultView, streamId: String, input: Input, solved: SolveResult): Outcome {
        val test = flowsheet(fs, results, streamId, input)
        val view = ResultView(StoredSolve(solved, test.modelHash(), 0), test.modelHash())
        val service = service(fs, results, streamId, input.service)
        val criteria = (fs.sizingCriteria ?: SizingCriteria()).forService(service)
        fun r(id: String, k: String) = (view.value(id, k) as? DisplayValue.Calculated)?.si
        fun err(id: String) = solved.objects[id]?.takeIf { it.status != ObjectStatus.Solved }?.let { it.error ?: "Not calculated" }
            ?: if (solved.objects[id] == null) "Not calculated" else null
        val rows = candidates(input).mapIndexed { i, c ->
            val g = "g$i"
            val e = err(g)
            if (e != null) Row(c, error = e)
            else Row(
                c,
                gradient = r(g, "pressureDrop")?.let { it / TEST_LENGTH },
                frictionGradient = r(g, "frictionPressureGradient"),
                velocity = r(g, "maxVelocity"),
                momentumFlux = r(g, "maxMomentumFlux"),
                mach = r(g, "maxMachNumber"),
                totalDrop = input.totalLength?.let { r("t$i", "pressureDrop") },
                totalError = input.totalLength?.let { err("t$i") },
                checks = LineSizing.checks(criteria, g, view),
            )
        }
        return Outcome(service, rows, rows.filter { it.passes }.minByOrNull { it.candidate.insideDiameter }, !criteria.isEmpty)
    }
}

/** Millimetres for a label. */
internal object ValueFormatShort {
    fun mm(m: Double): String = app.flowstead.core.format.ValueFormat.number(m * 1000, 1)
}
