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
)

object CaseStudies {
    const val MAX_STEPS = 50

    /** What stops the study from running, or null. */
    fun problem(fs: Flowsheet, cs: CaseStudy): String? {
        val obj = fs.objects.firstOrNull { it.id == cs.objectId } ?: return "Choose the object to vary"
        val key = cs.specKey?.takeIf { it in obj.specs } ?: return "Choose which of ${obj.tag}'s specifications to vary"
        if (cs.from == null || cs.to == null) return "Give the range of ${SpecKey.label(key).lowercase()}"
        if (!(cs.from.isFinite() && cs.to.isFinite()) || cs.from == cs.to) return "The range needs two different values"
        if (cs.steps !in 1..MAX_STEPS) return "Between 1 and $MAX_STEPS steps"
        if (cs.outputs.isEmpty()) return "Choose at least one result to record"
        cs.outputs.firstOrNull { o -> fs.objects.none { it.id == o.objectId } }?.let { return "A recorded object no longer exists" }
        return null
    }

    /** The values the specification takes, from → to inclusive. */
    fun points(cs: CaseStudy): List<Double> {
        val a = cs.from ?: return emptyList()
        val b = cs.to ?: return emptyList()
        return (0..cs.steps).map { i -> a + (b - a) * i / cs.steps }
    }

    /** The flowsheet with the varied specification set to [x] (SI), in the unit it was typed in. */
    fun at(fs: Flowsheet, cs: CaseStudy, x: Double): Flowsheet {
        val obj = fs.objects.first { it.id == cs.objectId }
        val old = obj.specs.getValue(cs.specKey!!)
        return FlowsheetEditing.setSpec(fs, obj.id, cs.specKey, Spec(x, old.unit))
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

    /** One plot per output against the varied specification. */
    fun plots(fs: Flowsheet, cs: CaseStudy): List<Plot> {
        val r = cs.results ?: return emptyList()
        val key = cs.specKey ?: return emptyList()
        val varied = fs.objects.firstOrNull { it.id == cs.objectId }
        return cs.outputs.mapIndexedNotNull { i, o ->
            val obj = fs.objects.firstOrNull { it.id == o.objectId } ?: return@mapIndexedNotNull null
            val ys = r.values.getOrNull(i) ?: return@mapIndexedNotNull null
            // Points the engine gave no value for are left out, not bridged by a guess.
            val pts = r.x.indices.filter { ys.getOrNull(it) != null }
            if (pts.size < 2) return@mapIndexedNotNull null
            Plot(
                title = "${obj.tag} ${ResultKeys.label(o.key).lowercase()}",
                xLabel = "${varied?.tag ?: ""} ${SpecKey.label(key).lowercase()}".trim(),
                xQuantity = SpecKey.quantity(key),
                yLabel = "${obj.tag} ${ResultKeys.label(o.key).lowercase()}",
                yQuantity = ResultKeys.quantityFor(obj, o.key),
                series = listOf(PlotSeries(ResultKeys.label(o.key), pts.map { r.x[it] }, pts.map { ys[it]!! })),
            )
        }
    }
}
