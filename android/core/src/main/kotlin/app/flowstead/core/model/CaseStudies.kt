/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.api.SolveResult
import app.flowstead.core.api.SolveStatus
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault

/**
 * A HYSYS-style case study (D51): one specification stepped from [from] to [to] in [steps] equal steps (SI), the
 * whole flowsheet solved at each, and the chosen results recorded. App-only: the engine never sees it.
 */
@Serializable
data class CaseStudy(
    val id: String,
    val name: String,
    /** The object whose specification is varied, and which one (a spec it already has). */
    val objectId: String? = null,
    val specKey: String? = null,
    val from: Double? = null,
    val to: Double? = null,
    val steps: Int = 10,
    val outputs: List<CaseOutput> = emptyList(),
    /** Optional second variable, stepped in an outer loop: one curve per value (D51). */
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault(EncodeDefault.Mode.NEVER) val objectId2: String? = null,
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault(EncodeDefault.Mode.NEVER) val specKey2: String? = null,
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault(EncodeDefault.Mode.NEVER) val from2: Double? = null,
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault(EncodeDefault.Mode.NEVER) val to2: Double? = null,
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault(EncodeDefault.Mode.NEVER) val steps2: Int = 4,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val results: CaseResults? = null,
)

/** A result to record at each point: ([objectId], result key). */
@Serializable
data class CaseOutput(val objectId: String, val key: String)

/**
 * What a run gave, point by point. [values] has one list per output, aligned with [x]; null where the engine
 * returned nothing for that point (the point failed, or the result is absent), with the reason in [errors].
 * [modelHash] is the flowsheet's hash when it ran, so a later change marks it out of date.
 */
@Serializable
data class CaseResults(
    val modelHash: String,
    val x: List<Double>,
    val values: List<List<Double?>>,
    val errors: List<String?>,
    /** The second variable's value at each point, when the study has one. */
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault(EncodeDefault.Mode.NEVER) val x2: List<Double>? = null,
)

object CaseStudies {
    const val MAX_STEPS = 50
    /** Most solves one study may ask for (first × second variable points). */
    const val MAX_POINTS = 200

    /** True when the study steps a second variable. */
    fun twoWay(cs: CaseStudy) = cs.objectId2 != null

    /** What stops the study from running, or null. */
    fun problem(fs: Flowsheet, cs: CaseStudy): String? {
        val obj = fs.objects.firstOrNull { it.id == cs.objectId } ?: return "Choose the object to vary"
        val key = cs.specKey?.takeIf { it in obj.specs } ?: return "Choose which of ${obj.tag}'s specifications to vary"
        if (cs.from == null || cs.to == null) return "Give the range of ${SpecKey.label(key).lowercase()}"
        if (!(cs.from.isFinite() && cs.to.isFinite()) || cs.from == cs.to) return "The range needs two different values"
        if (cs.steps !in 1..MAX_STEPS) return "Between 1 and $MAX_STEPS steps"
        if (cs.outputs.isEmpty()) return "Choose at least one result to record"
        cs.outputs.firstOrNull { o -> fs.objects.none { it.id == o.objectId } }?.let { return "A recorded object no longer exists" }
        if (twoWay(cs)) {
            val obj2 = fs.objects.firstOrNull { it.id == cs.objectId2 } ?: return "Choose the second object to vary, or remove the second variable"
            val key2 = cs.specKey2?.takeIf { it in obj2.specs } ?: return "Choose which of ${obj2.tag}'s specifications to vary second"
            if (obj2.id == obj.id && key2 == key) return "The second variable must differ from the first"
            if (cs.from2 == null || cs.to2 == null || !(cs.from2.isFinite() && cs.to2.isFinite()) || cs.from2 == cs.to2)
                return "Give two different values for the second variable's range"
            if (cs.steps2 !in 1..MAX_STEPS) return "Between 1 and $MAX_STEPS steps for the second variable"
            if ((cs.steps + 1) * (cs.steps2 + 1) > MAX_POINTS) return "At most $MAX_POINTS points in all: fewer steps, please"
        }
        return null
    }

    /** Every point of the study: (first variable, second variable or null), the second in the outer loop. */
    fun grid(cs: CaseStudy): List<Pair<Double, Double?>> {
        val first = points(cs)
        if (!twoWay(cs)) return first.map { it to null }
        val a = cs.from2 ?: return emptyList()
        val b = cs.to2 ?: return emptyList()
        return (0..cs.steps2).flatMap { j -> val y = a + (b - a) * j / cs.steps2; first.map { it to y } }
    }

    /** The values the specification takes, from → to inclusive. */
    fun points(cs: CaseStudy): List<Double> {
        val a = cs.from ?: return emptyList()
        val b = cs.to ?: return emptyList()
        return (0..cs.steps).map { i -> a + (b - a) * i / cs.steps }
    }

    /** The flowsheet with the varied specification(s) set to [x] (and [x2]) (SI), each in the unit it was typed in. */
    fun at(fs: Flowsheet, cs: CaseStudy, x: Double, x2: Double? = null): Flowsheet {
        fun set(f: Flowsheet, id: String?, key: String?, v: Double): Flowsheet {
            val obj = f.objects.first { it.id == id }
            return FlowsheetEditing.setSpec(f, obj.id, key!!, Spec(v, obj.specs.getValue(key).unit))
        }
        val first = set(fs, cs.objectId, cs.specKey, x)
        return if (x2 != null && twoWay(cs)) set(first, cs.objectId2, cs.specKey2, x2) else first
    }

    /** Each output's value from one solve, or null; plus why the point failed, if it did. */
    fun read(fs: Flowsheet, cs: CaseStudy, result: SolveResult): Pair<List<Double?>, String?> {
        val hash = fs.modelHash()
        val view = ResultView(StoredSolve(result, hash, 0L), hash)
        val values = cs.outputs.map { o -> (view.value(o.objectId, o.key) as? DisplayValue.Calculated)?.si }
        val error = if (result.status == SolveStatus.Converged) null
        else result.messages.firstOrNull { it.level == app.flowstead.core.api.MessageLevel.Error }?.text ?: "Not converged (${result.status.name.lowercase()})"
        return values to error
    }

    /** One plot per output against the first variable; with a second variable, one line per value of it. */
    fun plots(fs: Flowsheet, cs: CaseStudy): List<Plot> {
        val r = cs.results ?: return emptyList()
        val key = cs.specKey ?: return emptyList()
        val varied = fs.objects.firstOrNull { it.id == cs.objectId }
        val second = fs.objects.firstOrNull { it.id == cs.objectId2 }
        val groups = r.x2?.let { x2 -> r.x.indices.groupBy { x2.getOrNull(it) } } ?: mapOf(null to r.x.indices.toList())
        return cs.outputs.mapIndexedNotNull { i, o ->
            val obj = fs.objects.firstOrNull { it.id == o.objectId } ?: return@mapIndexedNotNull null
            val ys = r.values.getOrNull(i) ?: return@mapIndexedNotNull null
            val series = groups.mapNotNull { (p, idx) ->
                // Points the engine gave no value for are left out, not bridged by a guess.
                val pts = idx.filter { ys.getOrNull(it) != null }
                if (pts.size < 2) null else PlotSeries(ResultKeys.label(o.key), pts.map { r.x[it] }, pts.map { ys[it]!! }, parameter = p)
            }
            if (series.isEmpty()) return@mapIndexedNotNull null
            Plot(
                title = "${obj.tag} ${ResultKeys.label(o.key).lowercase()}",
                xLabel = "${varied?.tag ?: ""} ${SpecKey.label(key).lowercase()}".trim(),
                xQuantity = SpecKey.quantity(key),
                yLabel = "${obj.tag} ${ResultKeys.label(o.key).lowercase()}",
                yQuantity = ResultKeys.quantityFor(obj, o.key),
                series = series,
                parameterLabel = if (r.x2 != null && cs.specKey2 != null) "${second?.tag ?: ""} ${SpecKey.label(cs.specKey2).lowercase()}".trim() else null,
                parameterQuantity = cs.specKey2?.let { SpecKey.quantity(it) },
            )
        }
    }
}
