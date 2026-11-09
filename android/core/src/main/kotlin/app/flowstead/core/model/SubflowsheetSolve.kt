/*
 * Flowstead (D132, D133): solving a case with sub-flowsheets. The engine never sees a block.
 * - Every sub-flowsheet on the main fluid package: the case is solved flat, each matched pair of boundary streams one
 *   stream, so the engine solves exactly the flowsheet it would without blocks.
 * - Sub-flowsheets on their own packages: one engine solve a part (the main package's objects, then each own-package
 *   block's), each on its own package. A boundary stream crossing into another part becomes a feed there, defined by
 *   the engine's result on the side it comes from (transfer basis T–P or P–VF, molar flow and composition), as HYSYS's.
 *   A part is solved again only when what it is fed changed by more than [TOLERANCE]; the case has settled when a
 *   sweep solves nothing. Every number is the engine's.
 * The web's engine client (web/src/subflowsheets.js) follows the same steps.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.api.SolveResult
import app.flowstead.core.api.SolveStatus
import app.flowstead.core.api.SolverMessage
import app.flowstead.core.api.MessageLevel

object SubflowsheetSolve {
    const val TOLERANCE = 1e-6
    const val MAX_SWEEPS = 30

    /** Tools that need the whole case on one package (one part alone would leave the rest out without saying so). */
    const val WHOLE_CASE = "This works on the whole case on one fluid package: this case has sub-flowsheets with their own packages. Give them the parent’s package, or use it on a case without them."

    /** Solves [fs] with [run] (one engine solve of a flowsheet without blocks). Throws [EditRejected] with the reason when it can't. */
    suspend fun solve(fs: Flowsheet, run: suspend (Flowsheet) -> SolveResult): SolveResult {
        if (!Subflowsheets.has(fs)) return run(fs)
        if (!Subflowsheets.multiPackage(fs)) return Subflowsheets.expand(fs, run(Subflowsheets.flatten(fs)))
        return regions(fs, run)
    }

    private suspend fun regions(fs: Flowsheet, run: suspend (Flowsheet) -> SolveResult): SolveResult {
        val (regions, transfers) = Subflowsheets.regions(fs)
        val order = Subflowsheets.order(regions, transfers)
        val byId = regions.associateBy { it.id }
        val tag = fs.objects.associate { it.id to it.tag }
        val results = HashMap<String?, SolveResult>()
        val values = HashMap<Int, Pair<Map<String, Spec>, Map<String, Double>>>()
        val left = HashMap<Int, Boolean>()
        var sweeps = 0; var solves = 0; var settled = false
        fun loopMessage(t: Transfer) = "${tag[t.targetStream] ?: "A boundary stream"} closes a loop between fluid packages: give it an estimate (temperature, pressure, flow and composition)"
        while (sweeps < MAX_SWEEPS) {
            sweeps++
            var solvedAny = false
            for (rid in order) {
                var rf = byId.getValue(rid).flowsheet
                var need = rid !in results
                val out = ArrayList<String>()
                transfers.forEachIndexed { i, t ->
                    if (t.targetRegion != rid) return@forEachIndexed
                    val src = results[t.sourceRegion]?.objects?.get(t.sourceStream)
                    val v = Subflowsheets.transferSpecs(t, src, tag[t.sourceStream] ?: t.sourceStream, rf)
                    if (v == null) {
                        if (t.sourceRegion in results) {
                            // The part it comes from ran without one of its own boundary feeds: a loop between parts.
                            val loop = transfers.withIndex().any { (j, u) -> u.targetRegion == t.sourceRegion && left[j] == true }
                            throw EditRejected(if (loop) loopMessage(t) else "${tag[t.sourceStream] ?: "A boundary stream"} has no result, so ${tag[t.targetStream] ?: "the stream it feeds"} can't be defined")
                        }
                        // Its source has not run yet: start from the stream's own estimate when it has one, else leave it
                        // out this time (what it feeds solves once it comes in).
                        val own = fs.objects.firstOrNull { it.id == t.targetStream }
                        if (own == null || own.specs.size < 3 || own.composition.isEmpty()) out += t.targetStream
                        if (left[i] != true) { left[i] = true; need = true }
                        return@forEachIndexed
                    }
                    if (left[i] == true || Subflowsheets.change(values[i], v) > TOLERANCE) { need = true; values[i] = v; left[i] = false }
                    val use = values.getValue(i)
                    rf = Subflowsheets.applyTransfer(rf, t.targetStream, use.first, use.second)
                }
                if (!need) continue
                if (out.isNotEmpty()) rf = rf.copy(objects = rf.objects.filter { it.id !in out }, connections = rf.connections.filter { it.fromObject !in out && it.toObject !in out })
                results[rid] = run(rf); solves++; solvedAny = true
            }
            if (!solvedAny) { settled = true; break }
        }
        transfers.withIndex().firstOrNull { (i, _) -> left[i] == true }?.let { if (settled) throw EditRejected(loopMessage(it.value)) }
        val parts = regions.mapNotNull { r -> results[r.id]?.let { r to it } }
        val combined = Subflowsheets.combine(fs, parts, sweeps)
        return if (!settled) combined.copy(status = SolveStatus.Failed, messages = listOf(SolverMessage(MessageLevel.Error, "The streams between fluid packages did not settle in $MAX_SWEEPS sweeps")) + combined.messages)
        else combined.copy(messages = listOf(SolverMessage(MessageLevel.Info, "Fluid packages: ${regions.size} parts, $solves engine solves")) + combined.messages)
    }

    /**
     * The flowsheet a tool on one stream or unit ([objectId]) sends: the part of the case on that object's fluid package,
     * flat, with that package in the case's fields. For a case without own-package blocks, the case flattened.
     */
    fun forObject(fs: Flowsheet, objectId: String?): Flowsheet {
        if (!Subflowsheets.has(fs)) return fs
        if (!Subflowsheets.multiPackage(fs)) return Subflowsheets.flatten(fs)
        val (regions, _) = Subflowsheets.regions(fs)
        val want = objectId?.let { id -> fs.objects.firstOrNull { it.id == id } }?.let { Subflowsheets.regionOf(fs, it.parent) }
        return (regions.firstOrNull { it.id == want } ?: regions.first()).flowsheet
    }
}
