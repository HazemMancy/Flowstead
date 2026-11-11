/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.api.MessageLevel
import kotlinx.serialization.Serializable

/** A result kept in view on the canvas (D105): [key] of [objectId], with the object's solved value. */
@Serializable
data class WatchItem(val objectId: String, val key: String)

/**
 * A saved scenario (D105): the objects' specifications as they were and the key results then, for comparing cases side
 * by side and putting one back. The values are the engine's, SI, keyed "objectId|key".
 */
@Serializable
data class Scenario(
    val name: String,
    val savedAt: Long,
    val objects: List<SimObject>,
    val values: Map<String, Double> = emptyMap(),
    /** True when the flowsheet had been solved for exactly these specifications. */
    val solved: Boolean = false,
)

object Scenarios {
    /** Results kept with a scenario: every stream's state and flows, every unit's duty, power and pressure change. */
    val STREAM_KEYS = listOf("temperature", "pressure", "vapourFraction", "molarFlow", "massFlow")
    val UNIT_KEYS = listOf("duty", "power", "pressureDrop", "pressureRise")

    fun key(objectId: String, key: String) = "$objectId|$key"

    fun snapshot(fs: Flowsheet, results: ResultView, name: String, now: Long): Scenario {
        val values = buildMap {
            for (o in fs.objects) {
                val keys = (if (o.type == ObjectType.MaterialStream) STREAM_KEYS else UNIT_KEYS) + fs.watch.filter { it.objectId == o.id }.map { it.key }
                for (k in keys.distinct()) (results.value(o.id, k) as? DisplayValue.Calculated)?.takeIf { !it.stale }?.let { put(key(o.id, k), it.si) }
            }
        }
        return Scenario(name, now, fs.objects, values, solved = values.isNotEmpty() && !results.stale)
    }

    /** Puts a scenario's specifications back on the objects that still exist (topology and positions stay as they are). */
    fun restore(fs: Flowsheet, s: Scenario): Flowsheet {
        val byId = s.objects.associateBy { it.id }
        return fs.copy(objects = fs.objects.map { o ->
            byId[o.id]?.let { old -> o.copy(specs = old.specs, composition = old.composition, refs = old.refs, curve = old.curve, pipeSections = old.pipeSections) } ?: o
        })
    }

    /** The rows a comparison shows: watched results first, then every key result some scenario kept. */
    fun rows(fs: Flowsheet, scenarios: List<Scenario>): List<Pair<String, String>> {
        val kept = scenarios.flatMap { it.values.keys }.toSet()
        val watched = fs.watch.map { key(it.objectId, it.key) }.filter { it in kept }
        val rest = fs.objects.flatMap { o ->
            ((if (o.type == ObjectType.MaterialStream) STREAM_KEYS else UNIT_KEYS)).map { key(o.id, it) }
        }.filter { it in kept && it !in watched }
        return (watched + rest).map { it.substringBefore('|') to it.substringAfter('|') }
    }
}

enum class CheckLevel { Error, Warning, Info }

/** One finding of the flowsheet check, about an object when [objectId] is set. */
data class CheckItem(val level: CheckLevel, val objectId: String?, val text: String)

/**
 * The flowsheet check (D105): everything that would stop a solve or make a result doubtful, in one list — the
 * specification problems the solver refuses on, the engine's own errors and warnings from the last solve, and loose ends
 * (streams that go nowhere, units with an open port, duplicate tags, reactions and components nothing uses).
 */
object FlowsheetCheck {
    // With sub-flowsheets the case is checked flattened (D136): a stream matched across a block is one stream.
    fun run(case: Flowsheet, results: ResultView): List<CheckItem> = buildList {
        val fs = Subflowsheets.flatten(case)
        if (fs.propertyPackage == null) add(CheckItem(CheckLevel.Error, null, "No fluid package chosen."))
        if (fs.compounds.isEmpty()) add(CheckItem(CheckLevel.Error, null, "No components in the fluid."))
        for ((id, text) in Specification.problems(fs)) add(CheckItem(CheckLevel.Error, id, text))
        // The last solve's own messages, when they belong to the flowsheet as it is.
        if (!results.stale) {
            for (o in fs.objects) {
                if (results.objectStatus(o.id) == app.flowstead.core.api.ObjectStatus.Error) add(CheckItem(CheckLevel.Error, o.id, "${o.tag}: the engine could not calculate it."))
                for (m in results.messages(o.id)) {
                    val level = when (m.level) { MessageLevel.Error -> CheckLevel.Error; MessageLevel.Warning -> CheckLevel.Warning; else -> null } ?: continue
                    add(CheckItem(level, o.id, "${o.tag}: ${m.text}"))
                }
            }
        } else if (results.status != null) add(CheckItem(CheckLevel.Info, null, "The results are from before the last change; solve to check them again."))
        // Loose ends.
        val connected = fs.connections.flatMap { listOf(it.fromObject, it.toObject) }.toSet()
        for (o in fs.objects) {
            if (o.type.isStream && o.id !in connected && fs.objects.size > 1) add(CheckItem(CheckLevel.Warning, o.id, "${o.tag}: not connected to anything."))
        }
        for ((tag, objs) in fs.objects.groupBy { it.tag.trim() }.filter { it.value.size > 1 }) {
            add(CheckItem(CheckLevel.Warning, objs.first().id, "Tag $tag is used ${objs.size} times; data sheets and imports by tag become ambiguous."))
        }
        val usedReactions = fs.objects.flatMap { Reactors.reactionIds(it) }.toSet()
        for (r in fs.reactions.filter { it.id !in usedReactions }) add(CheckItem(CheckLevel.Info, null, "Reaction ${r.name} is not used by any reactor."))
        val inStreams = fs.objects.flatMap { o -> o.composition.filterValues { it > 0 }.keys }.toSet()
        if (fs.objects.any { it.type == ObjectType.MaterialStream && it.composition.isNotEmpty() }) {
            for (c in fs.compounds.filter { it !in inStreams && fs.reactions.none { r -> it in r.stoichiometry } }) {
                add(CheckItem(CheckLevel.Info, null, "$c is in the fluid but in no feed or reaction; removing it speeds up solving."))
            }
        }
    }.distinct()
}
