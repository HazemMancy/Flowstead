/*
 * Flowstead (D139): dragging objects into a sub-flowsheet, and duplicating one, on every benchmark, on the JVM and in
 * JavaScript (the code the page runs).
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Reference: the benchmark's own flowsheet. With the right half of its units dragged into a block one by one, the
 * case must flatten to exactly that flowsheet (tags, types, specs, compositions and connections), so the engine solves
 * the same case and gives the same numbers; a duplicate of the block holds the same objects.
 */
package app.flowstead.web.model

import app.flowstead.core.TestData
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.FlowsheetJson
import app.flowstead.core.model.Subflowsheets
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoveIntoBlockTest {
    private fun shape(f: Flowsheet): Pair<Set<String>, Set<Pair<String, String>>> {
        val tag = f.objects.associate { it.id to it.tag }
        return f.objects.map { "${it.tag}|${it.type}|${it.specs}|${it.composition}" }.toSet() to
            f.connections.map { tag.getValue(it.fromObject) to tag.getValue(it.toObject) }.toSet()
    }

    @Test
    fun everyBenchmarkWithHalfItsUnitsDraggedInFlattensToItself() {
        var cases = 0
        for ((name, json) in TestData.benchmarks) {
            val flat = FlowsheetJson.decodeFromJsonElement(Flowsheet.serializer(), FlowsheetJson.parseToJsonElement(json).jsonObject.getValue("flowsheet"))
            val units = flat.objects.filter { !it.type.isStream }.sortedBy { it.x }
            if (units.size < 2) continue
            val (empty, block) = Subflowsheets.add(flat, null, 0.0, 900.0)
            val moved = units.drop(units.size / 2).fold(empty) { acc, u -> Subflowsheets.moveInto(acc, listOf(u.id), block.id) }
            assertTrue(units.drop(units.size / 2).all { u -> moved.objects.first { it.id == u.id }.parent == block.id }, name)
            assertEquals(shape(flat), shape(Subflowsheets.flatten(moved)), name)
            // The same units dragged in together, as a selection.
            val together = Subflowsheets.moveInto(empty, units.drop(units.size / 2).map { it.id }, block.id)
            assertEquals(shape(flat), shape(Subflowsheets.flatten(together)), "$name, together")
            // A duplicate of the block holds as many objects of each type, under new ids and tags.
            val (dup, copy, _) = Subflowsheets.duplicate(moved, block.id)
            val inside = { f: Flowsheet, id: String -> f.objects.filter { it.parent == id }.map { it.type }.sortedBy { it.name } }
            assertEquals(inside(moved, block.id), inside(dup, copy.id), name)
            assertEquals(dup.objects.size, dup.objects.map { it.tag.lowercase() }.toSet().size, "$name: duplicate tags")
            assertEquals(shape(flat), shape(Subflowsheets.flatten(Subflowsheets.delete(dup, copy.id))), "$name: the original, with the copy deleted")
            cases++
        }
        assertTrue(cases >= 10, "$cases benchmarks")
    }
}
