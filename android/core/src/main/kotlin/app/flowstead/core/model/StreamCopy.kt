/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.api.Phases
import app.flowstead.core.units.UnitSet

/**
 * Copying a stream's data (owner's request, 1.8.8): a duplicate of a stream with everything that defines it, or chosen
 * values from one stream into a feed. A feed gives what the user specified; a calculated stream gives what the engine
 * returned for it in the current solve (never a stale or estimated value), so a product stream can be fed to a test unit.
 */
object StreamCopy {
    /** The item key for the composition; the others are spec keys. */
    const val COMPOSITION = "composition"

    /** One value that can be copied: a spec key with its value, or the composition (mole fractions). */
    data class Item(val key: String, val label: String, val spec: Spec? = null, val composition: Map<String, Double>? = null)

    /** What [source] can give, in the user's [units]. Empty for a calculated stream without current results. */
    fun available(fs: Flowsheet, source: SimObject, view: ResultView, units: UnitSet): List<Item> {
        if (source.type != ObjectType.MaterialStream) return emptyList()
        val keys = SpecKey.STREAM_STATE + SpecKey.STREAM_FLOW
        return if (fs.isFeed(source)) {
            keys.mapNotNull { k -> source.specs[k]?.let { Item(k, ResultKeys.label(k), it) } } +
                listOfNotNull(source.composition.takeIf { it.isNotEmpty() }?.let { Item(COMPOSITION, "Composition", composition = it) })
        } else {
            fun calc(k: String) = (view.value(source.id, k) as? DisplayValue.Calculated)?.takeIf { !it.stale }?.si
            val values = listOf(SpecKey.TEMPERATURE, SpecKey.PRESSURE, SpecKey.VAPOUR_FRACTION, SpecKey.MOLAR_FLOW, SpecKey.MASS_FLOW)
                .mapNotNull { k -> calc(k)?.let { Item(k, ResultKeys.label(k), Spec(it, units.unitFor(ResultKeys.quantity(k)).id)) } }
            val x = fs.compounds.associateWith { c ->
                (view.composition(source.id, Phases.OVERALL, c) as? DisplayValue.Calculated)?.takeIf { !it.stale }?.si
            }
            values + listOfNotNull(
                x.takeIf { m -> m.values.all { it != null } && m.isNotEmpty() }?.let { m ->
                    Item(COMPOSITION, "Composition", composition = m.mapValues { it.value!! }.filterValues { it > 0 })
                },
            )
        }
    }

    /**
     * Puts the chosen [items] on feed stream [targetId]. Copied conditions replace the target's conditions, a copied
     * flow its flow, and a copied composition its composition, so the target is never left with a mix of old and new.
     */
    fun apply(fs: Flowsheet, targetId: String, items: List<Item>): Flowsheet {
        val target = fs.obj(targetId) ?: throw EditRejected("That stream no longer exists")
        if (target.type != ObjectType.MaterialStream || !fs.isFeed(target)) throw EditRejected("Copy into a feed stream: ${target.tag} is calculated")
        if (items.isEmpty()) return fs
        val state = items.filter { it.key in SpecKey.STREAM_STATE && it.spec != null }
        val flow = items.filter { it.key in SpecKey.STREAM_FLOW && it.spec != null }
        var specs = target.specs
        if (state.isNotEmpty()) specs = specs - SpecKey.STREAM_STATE.toSet() + state.associate { it.key to it.spec!! }
        if (flow.isNotEmpty()) specs = specs - SpecKey.STREAM_FLOW.toSet() + flow.associate { it.key to it.spec!! }
        val composition = items.firstOrNull { it.key == COMPOSITION }?.composition?.filterKeys { it in fs.compounds } ?: target.composition
        return fs.copy(objects = fs.objects.map { if (it.id == targetId) it.copy(specs = specs, composition = composition) else it })
    }

    /**
     * A new feed stream beside [sourceId] with all it can give: its specs and composition if it is a feed, its solved
     * temperature, pressure, molar flow and composition if it is calculated. Not connected.
     */
    fun duplicate(fs: Flowsheet, sourceId: String, view: ResultView, units: UnitSet): Pair<Flowsheet, SimObject> {
        val source = fs.obj(sourceId) ?: throw EditRejected("That stream no longer exists")
        if (source.type != ObjectType.MaterialStream) throw EditRejected("Only material streams can be duplicated")
        val all = available(fs, source, view, units)
        // A calculated stream gives several values; a feed needs two conditions and one flow: T, P and molar flow.
        val items = if (fs.isFeed(source)) all else all.filter { it.key in setOf(SpecKey.TEMPERATURE, SpecKey.PRESSURE, SpecKey.MOLAR_FLOW, COMPOSITION) }
        if (!fs.isFeed(source) && items.none { it.key == COMPOSITION }) throw EditRejected("Solve first: ${source.tag} has no current results to copy")
        val (withCopy, copy) = FlowsheetEditing.add(fs, ObjectType.MaterialStream, source.x, source.y + 72.0)
        val filled = apply(withCopy, copy.id, items)
        return filled to filled.obj(copy.id)!!
    }
}
