/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

/**
 * What can be solved now (D83). As in HYSYS, the solver calculates whatever is ready and leaves the rest: an object is
 * not ready when it is missing something itself, or when something it depends on is not ready (a stream or unit
 * upstream of it, the object a Set copies from or drives, what an Adjust varies or watches). Energy streams go with
 * the units they join. Everything else is sent to the engine as one flowsheet.
 */
data class Readiness(
    /** Objects not ready, with the reason shown in red: what they need, or what they wait for. */
    val notReady: Map<String, String>,
    /** The flowsheet the engine gets: ready objects and the connections between them. */
    val solvable: Flowsheet,
) {
    val anyReady: Boolean get() = solvable.objects.any { it.type != ObjectType.ReliefValve }

    companion object {
        fun of(flowsheet: Flowsheet): Readiness {
            if (!Subflowsheets.has(flowsheet)) return flat(flowsheet)
            // With sub-flowsheets (D132, D133): checked on the case flattened, each matched pair one stream; the engine
            // client gets the ready part of the case as it is (SubflowsheetSolve flattens it or solves it by package).
            val f = Subflowsheets.flatten(flowsheet)
            val r = flat(f)
            val pairs = Subflowsheets.joinedAll(flowsheet)
            val ready = r.solvable.objects.map { it.id }.toSet()
            val keep = flowsheet.objects.filter { it.id in ready || pairs[it.id] in ready }
            val ids = keep.map { it.id }.toSet()
            val notReady = r.notReady + pairs.mapNotNull { (inner, outer) -> r.notReady[outer]?.let { inner to it } }
            return Readiness(notReady, flowsheet.copy(objects = keep, connections = flowsheet.connections.filter { it.fromObject in ids && it.toObject in ids }))
        }

        /** Each object's own check, with sub-flowsheets the case flattened (a matched stream is defined by its partner). */
        fun statusOf(flowsheet: Flowsheet): Map<String, SpecStatus> {
            if (!Subflowsheets.has(flowsheet)) return flowsheet.objects.associate { it.id to Specification.status(flowsheet, it) }
            val f = Subflowsheets.flatten(flowsheet)
            val pairs = Subflowsheets.joinedAll(flowsheet)
            return flowsheet.objects.associate { o -> o.id to Specification.status(f, f.objects.firstOrNull { it.id == (pairs[o.id] ?: o.id) } ?: o) }
        }

        private fun flat(flowsheet: Flowsheet): Readiness {
            val applied = Specification.withSets(flowsheet)
            val byId = applied.objects.associateBy { it.id }
            val reason = linkedMapOf<String, String>()
            for (o in applied.objects.filter { it.type != ObjectType.ReliefValve }) {
                when (val st = Specification.status(flowsheet, o)) {
                    SpecStatus.Complete -> Unit
                    is SpecStatus.UnderSpecified -> reason[o.id] = "Needs ${st.needs.joinToString("; ")}"
                    is SpecStatus.OverSpecified -> reason[o.id] = st.problem
                    is SpecStatus.Invalid -> reason[o.id] = st.problem
                }
            }
            fun tag(id: String) = byId[id]?.tag ?: id
            fun waits(id: String, on: String) {
                if (id !in reason && byId[id]?.type != ObjectType.ReliefValve) reason[id] = "Waits for ${tag(on)}"
            }
            // Until nothing changes: downstream of what is not ready, energy streams with their units, Sets and Adjusts
            // with the objects they refer to.
            do {
                val before = reason.size
                for (c in applied.connections) {
                    if (c.fromObject in reason) waits(c.toObject, c.fromObject)
                    if (byId[c.fromObject]?.type == ObjectType.EnergyStream && c.toObject in reason) waits(c.fromObject, c.toObject)
                }
                for (o in applied.objects) {
                    val refs = when (o.type) {
                        ObjectType.Set -> listOfNotNull(o.refs[SetKeys.SOURCE_OBJECT], o.refs[SetKeys.TARGET_OBJECT])
                        ObjectType.Adjust, ObjectType.Controller -> listOfNotNull(o.refs[AdjustKeys.MANIPULATED_OBJECT], o.refs[AdjustKeys.CONTROLLED_OBJECT])
                        else -> emptyList()
                    }
                    refs.firstOrNull { it in reason }?.let { waits(o.id, it) }
                }
            } while (reason.size != before)

            // A Set left out still gives its target its value, so the target goes as the Set made it.
            val droppedSetTargets = applied.objects
                .filter { it.type == ObjectType.Set && it.id in reason }
                .mapNotNull { it.refs[SetKeys.TARGET_OBJECT] }.toSet()
            val keep = flowsheet.objects.filter { it.id !in reason }.map { o -> if (o.id in droppedSetTargets) byId.getValue(o.id) else o }
            val kept = keep.map { it.id }.toSet()
            val solvable = flowsheet.copy(
                objects = keep,
                connections = flowsheet.connections.filter { it.fromObject in kept && it.toObject in kept },
                // The engine never reads the flowsheet tools (D105); scenarios would make every solve's request large.
                spreadsheet = null, watch = emptyList(), scenarios = emptyList(),
            )
            return Readiness(reason, solvable)
        }
    }
}
