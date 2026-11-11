/*
 * Flowstead web (D124): copy and paste of a group of objects on the flowsheet. Pure model code on the app's Flowsheet,
 * in commonMain so it is tested on the JVM and in JavaScript. android/ is unchanged.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.web.model

import app.flowstead.core.model.AdjustKeys
import app.flowstead.core.model.Connection
import app.flowstead.core.model.EditRejected
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.FlowsheetEditing
import app.flowstead.core.model.PropertyTable
import app.flowstead.core.model.Reaction
import app.flowstead.core.model.Reactors
import app.flowstead.core.model.ReliefDeviceDef
import app.flowstead.core.model.ResultView
import app.flowstead.core.model.SetKeys
import app.flowstead.core.model.SimObject
import app.flowstead.core.model.Spec
import app.flowstead.core.model.SpecKey
import app.flowstead.core.model.StreamCopy
import app.flowstead.core.model.isFeed
import app.flowstead.core.model.obj
import app.flowstead.core.units.UnitSet
import kotlinx.serialization.Serializable

/**
 * What was copied: the objects, the connections that join two of them, their property tables, their relief-valve data
 * and their dynamic settings. A stream whose source unit was not copied pastes as a feed, so it carries what defines
 * it as one ([feedSpecs]): its solved temperature, pressure, molar flow and composition when the engine returned them
 * at the time of the copy (the app's "Duplicate as a feed" rule), nothing otherwise.
 */
@Serializable
data class Clip(
    val kind: String = KIND,
    val objects: List<SimObject>,
    val connections: List<Connection>,
    val tables: List<PropertyTable> = emptyList(),
    val reliefDevices: List<ReliefDeviceDef> = emptyList(),
    val dynamics: Map<String, Map<String, Spec>> = emptyMap(),
    /** Calculated streams that paste as feeds: id → (specs, composition) from the engine's current results. */
    val feedSpecs: Map<String, FeedSpec> = emptyMap(),
    /** Calculated streams that paste as feeds with nothing to define them: no current results when copied. */
    val unspecified: List<String> = emptyList(),
    /** The reactions the copied reactors use, so a reactor pastes into another project ready to solve. */
    val reactions: List<Reaction> = emptyList(),
) {
    companion object { const val KIND = "flowstead.clip.v1" }
}

@Serializable
data class FeedSpec(val specs: Map<String, Spec>, val composition: Map<String, Double>)

/** The flowsheet after a paste, the new objects' ids, and what the user must know about the copy (never silent). */
data class Pasted(val flowsheet: Flowsheet, val ids: List<String>, val notes: List<String>)

object FlowsheetClipboard {
    /** Refs that name another object, by the object types that hold them. */
    private val OBJECT_REFS = listOf(SetKeys.TARGET_OBJECT, SetKeys.SOURCE_OBJECT, AdjustKeys.MANIPULATED_OBJECT, AdjustKeys.CONTROLLED_OBJECT)

    fun copy(fs: Flowsheet, ids: Collection<String>, view: ResultView, units: UnitSet): Clip {
        val chosen = fs.objects.filter { it.id in ids }
        if (chosen.isEmpty()) throw EditRejected("Select something to copy")
        val inside = chosen.map { it.id }.toSet()
        val connections = fs.connections.filter { it.fromObject in inside && it.toObject in inside }
        val feedSpecs = mutableMapOf<String, FeedSpec>()
        val unspecified = mutableListOf<String>()
        for (s in chosen.filter { it.type.isStream && !fs.isFeed(it) }) {
            if (connections.any { it.toObject == s.id }) continue // its source unit comes too
            val items = StreamCopy.available(fs, s, view, units)
                .filter { it.key in setOf(SpecKey.TEMPERATURE, SpecKey.PRESSURE, SpecKey.MOLAR_FLOW, StreamCopy.COMPOSITION) }
            val composition = items.firstOrNull { it.key == StreamCopy.COMPOSITION }?.composition
            if (composition == null) unspecified += s.id
            else feedSpecs[s.id] = FeedSpec(items.mapNotNull { i -> i.spec?.let { i.key to it } }.toMap(), composition)
        }
        return Clip(
            objects = chosen,
            connections = connections,
            tables = fs.tables.filter { it.objectId in inside },
            reliefDevices = fs.safety?.devices.orEmpty().filter { it.id in inside },
            dynamics = fs.dynamics?.parameters.orEmpty().filterKeys { it in inside },
            feedSpecs = feedSpecs,
            unspecified = unspecified,
            reactions = chosen.flatMap { Reactors.reactionIds(it) }.distinct().mapNotNull { fs.reaction(it) },
        )
    }

    /**
     * Adds a copy of [clip] to [fs], moved by ([dx], [dy]) and put on the grid. New ids; each tag kept if [fs] does not
     * use it, else the next free tag of its type. References between copied objects follow the copies; a Set, Adjust or
     * controller pointing at an object that was not copied loses that reference (two of them on one variable would fight),
     * and the note says so. Refused, naming them, if a stream uses a component [fs] does not have.
     */
    fun paste(fs: Flowsheet, clip: Clip, dx: Double, dy: Double): Pasted {
        if (clip.kind != Clip.KIND || clip.objects.isEmpty()) throw EditRejected("Nothing to paste")
        // Reactions the target does not have yet come with the reactors that use them (same id: the target's is kept).
        val newReactions = clip.reactions.filter { fs.reaction(it.id) == null }
        val missing = (clip.objects.flatMap { it.composition.keys } + clip.feedSpecs.values.flatMap { it.composition.keys } +
            newReactions.flatMap { it.stoichiometry.keys }).filter { it !in fs.compounds }.distinct()
        if (missing.isNotEmpty()) throw EditRejected("Add ${missing.joinToString(", ")} to the components first: the copied streams or reactions use ${if (missing.size == 1) "it" else "them"}")
        val notes = mutableListOf<String>()
        newReactions.forEach { notes += "Added reaction ${it.name}, which the copied reactors use" }
        val newIds = clip.objects.associate { it.id to FlowsheetEditing.newId() }
        var out = if (newReactions.isEmpty()) fs else fs.copy(reactions = fs.reactions + newReactions)
        val pasted = mutableListOf<SimObject>()
        for (o in clip.objects) {
            // Tags are unique ignoring case (FlowsheetEditing.rename). The palette's tags are upper case (K-101, 3), so
            // the next one is found among the existing tags in upper case: a "k-100" keeps K-100 from being given again.
            val used = out.objects.map { it.tag.lowercase() }.toSet()
            val tag = if (o.tag.lowercase() !in used) o.tag
                else FlowsheetEditing.nextTag(out.copy(objects = out.objects.map { it.copy(tag = it.tag.uppercase()) }), o.type)
            var refs = o.refs.mapValues { (_, v) -> newIds[v] ?: v }
            for (k in OBJECT_REFS) {
                val v = o.refs[k] ?: continue
                if (v !in newIds) {
                    refs = refs - k
                    val name = fs.obj(v)?.tag ?: "an object"
                    notes += "$tag: ${refLabel(k)} ($name) was not copied, so choose it again"
                }
            }
            var copy = o.copy(id = newIds.getValue(o.id), tag = tag, x = FlowsheetEditing.snap(o.x + dx), y = FlowsheetEditing.snap(o.y + dy), refs = refs)
            clip.feedSpecs[o.id]?.let { f ->
                copy = copy.copy(specs = f.specs, composition = f.composition)
                notes += "$tag: its source unit was not copied, so it is a feed with the solved temperature, pressure, flow and composition of ${o.tag}"
            }
            if (o.id in clip.unspecified) {
                copy = copy.copy(specs = emptyMap(), composition = emptyMap())
                notes += "$tag: its source unit was not copied and ${o.tag} had no current results, so it is a feed to specify"
            }
            pasted += copy
            out = out.copy(objects = out.objects + copy)
        }
        out = out.copy(
            connections = out.connections + clip.connections.map { it.copy(fromObject = newIds.getValue(it.fromObject), toObject = newIds.getValue(it.toObject)) },
            tables = out.tables + clip.tables.map { it.copy(objectId = newIds.getValue(it.objectId)) },
        )
        for (d in clip.reliefDevices) {
            val id = newIds.getValue(d.id)
            val protectedId = d.protectedId?.let { p -> newIds[p] ?: p.takeIf { out.obj(it) != null } }
            out = FlowsheetEditing.upsertReliefDevice(out, d.copy(id = id, tag = out.obj(id)!!.tag, protectedId = protectedId, result = null, resultHash = null))
        }
        if (clip.dynamics.isNotEmpty()) {
            val dyn = out.dynamics ?: app.flowstead.core.model.DynamicsSetup()
            out = out.copy(dynamics = dyn.copy(parameters = dyn.parameters + clip.dynamics.mapKeys { newIds.getValue(it.key) }))
        }
        return Pasted(out, pasted.map { it.id }, notes)
    }

    private fun refLabel(key: String) = when (key) {
        SetKeys.TARGET_OBJECT -> "its target"
        SetKeys.SOURCE_OBJECT -> "its source"
        AdjustKeys.MANIPULATED_OBJECT -> "its manipulated object"
        else -> "its controlled object"
    }

    /**
     * Several objects moved together by ([dx], [dy]), each as FlowsheetEditing.move moves one: its property table stays
     * where it is on the flowsheet (the owner's rule for a move).
     */
    fun moveAll(fs: Flowsheet, ids: Collection<String>, dx: Double, dy: Double): Flowsheet {
        if (!dx.isFinite() || !dy.isFinite()) throw EditRejected("Not a position")
        return ids.distinct().fold(fs) { f, id -> f.obj(id)?.let { FlowsheetEditing.move(f, id, it.x + dx, it.y + dy) } ?: f }
    }

    /** Deletes every object in [ids], with the app's rule for each (connections, tables, a PSV's relief valve). */
    fun deleteAll(fs: Flowsheet, ids: Collection<String>): Flowsheet = ids.fold(fs) { f, id -> if (f.obj(id) != null) FlowsheetEditing.delete(f, id) else f }
}
