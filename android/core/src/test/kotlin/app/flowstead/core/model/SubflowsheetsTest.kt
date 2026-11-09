/*
 * Flowstead (D132, D133): sub-flowsheets.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Benchmark 06's train (SUCTION → K-100 → DISCHARGE → E-100 → COOLED): putting the cooler in a sub-flowsheet and
 * flattening it again must give back exactly the flowsheet the engine solved before (so the same numbers), and a
 * sub-flowsheet on its own package must carry DISCHARGE across as a feed defined by the engine's result.
 */
package app.flowstead.core.model

import app.flowstead.core.api.ObjectResult
import app.flowstead.core.api.ObjectStatus
import app.flowstead.core.api.PhaseResult
import app.flowstead.core.api.SolveResult
import app.flowstead.core.api.SolveStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubflowsheetsTest {
    private val train = Flowsheet(
        propertyPackage = "Peng-Robinson (PR)",
        compounds = listOf("Methane", "Ethane", "Propane"),
        objects = listOf(
            SimObject("suc", "SUCTION", ObjectType.MaterialStream, 0.0, 0.0,
                specs = mapOf("temperature" to Spec(303.15, "C"), "pressure" to Spec(1.0e6, "bara"), "molarFlow" to Spec(0.1, "kmol/h")),
                composition = mapOf("Methane" to 0.8, "Ethane" to 0.15, "Propane" to 0.05)),
            SimObject("k", "K-100", ObjectType.Compressor, 120.0, 0.0, specs = mapOf("outletPressure" to Spec(1.5e6, "bara"), "efficiency" to Spec(0.75, "%"))),
            SimObject("dis", "DISCHARGE", ObjectType.MaterialStream, 240.0, 0.0),
            SimObject("e", "E-100", ObjectType.Cooler, 360.0, 0.0, specs = mapOf("outletTemperature" to Spec(313.15, "C"), "pressureDrop" to Spec(0.0, "bar"))),
            SimObject("out", "COOLED", ObjectType.MaterialStream, 480.0, 0.0),
        ),
        connections = listOf(Connection("suc", 0, "k", 0), Connection("k", 0, "dis", 0), Connection("dis", 0, "e", 0), Connection("e", 0, "out", 0)),
    )

    /** The flowsheet the engine gets, ids aside: tags, types, specs, compositions and the tag graph. */
    private fun shape(f: Flowsheet): Pair<Set<String>, Set<Pair<String, String>>> {
        val tag = f.objects.associate { it.id to it.tag }
        return f.objects.map { "${it.tag}|${it.type}|${it.specs}|${it.composition}" }.toSet() to
            f.connections.map { tag.getValue(it.fromObject) to tag.getValue(it.toObject) }.toSet()
    }

    @Test
    fun groupingTheCoolerAndFlatteningGivesTheSameFlowsheet() {
        val (g, block) = Subflowsheets.group(train, listOf("e"), 360.0, 0.0)
        // DISCHARGE stays outside; inside, a boundary stream matched to it feeds E-100. COOLED goes inside with it? No:
        // only E-100 was chosen, so COOLED stays outside and gets a boundary stream inside.
        assertEquals(block.id, g.objects.first { it.id == "e" }.parent)
        assertEquals(2, block.ports.size)
        val bd = Subflowsheets.boundary(g, block.id)
        assertEquals(listOf(true, false), bd.sortedBy { !it.feed }.map { it.feed })
        assertEquals(setOf("dis", "out"), block.ports.map { it.outer }.toSet())
        // The engine sees exactly what it saw before.
        val flat = Subflowsheets.flatten(g)
        assertEquals(shape(train), shape(flat))
        assertTrue(flat.objects.all { it.parent == null } && flat.subflowsheets.isEmpty())
        // The grouped case serializes and reads back.
        val back = FlowsheetJson.decodeFromString(Flowsheet.serializer(), FlowsheetJson.encodeToString(Flowsheet.serializer(), g))
        assertEquals(g, back)
        // Ungrouping restores the flat flowsheet.
        assertEquals(shape(train), shape(Subflowsheets.ungroup(g, block.id)))
        // A flowsheet without sub-flowsheets keeps its hash (the new fields are not written).
        assertEquals(train.modelHash(), FlowsheetJson.decodeFromString(Flowsheet.serializer(), FlowsheetJson.encodeToString(Flowsheet.serializer(), train)).modelHash())
        assertTrue("subflowsheets" !in FlowsheetJson.encodeToString(Flowsheet.serializer(), train))
    }

    @Test
    fun groupingAFeedMovesItsSpecsToTheOutsideStream() {
        val (g, block) = Subflowsheets.group(train, listOf("suc", "k"), 60.0, 0.0)
        // SUCTION is a feed with no source: it goes inside as an unmatched boundary feed and keeps its specs there.
        assertEquals(block.id, g.objects.first { it.id == "suc" }.parent)
        assertEquals(3, g.objects.first { it.id == "suc" }.specs.size)
        // K-100's outlet DISCHARGE stays outside and is matched to a new boundary product inside.
        assertEquals(listOf("dis"), block.ports.map { it.outer })
        assertEquals(shape(train), shape(Subflowsheets.flatten(g)))
        assertTrue(Readiness.of(Subflowsheets.flatten(g)).anyReady)
    }

    @Test
    fun ownPackageCarriesTheStreamAcrossFromTheEnginesResult() {
        val (g0, block) = Subflowsheets.group(train, listOf("e"), 360.0, 0.0)
        val g = Subflowsheets.setFluid(g0, block.id, SubFluid("Soave-Redlich-Kwong (SRK)", listOf("Methane", "Ethane", "Propane")))
        assertTrue(Subflowsheets.multiPackage(g))
        val (regions, transfers) = Subflowsheets.regions(g)
        assertEquals(listOf(null, block.id), regions.map { it.id })
        assertEquals("Soave-Redlich-Kwong (SRK)", regions[1].flowsheet.propertyPackage)
        assertEquals(setOf("K-100", "SUCTION", "DISCHARGE", "COOLED"), regions[0].flowsheet.objects.map { it.tag }.toSet())
        // Into the block: DISCHARGE → its inner twin; out of it: the inner product → COOLED.
        val into = transfers.single { it.targetRegion == block.id }
        assertEquals("dis", into.sourceStream)
        val out = transfers.single { it.sourceRegion == block.id }
        assertEquals("out", out.targetStream)
        assertEquals(listOf(null, block.id), Subflowsheets.order(regions, transfers))
        // What the engine returned for DISCHARGE defines the inner feed: T, P and molar flow (SI) and the composition.
        val dis = ObjectResult(ObjectStatus.Solved, properties = mapOf("temperature" to 360.0, "pressure" to 1.5e6, "vapourFraction" to 1.0, "molarFlow" to 0.0278),
            phases = mapOf("overall" to PhaseResult(composition = mapOf("Methane" to 0.8, "Ethane" to 0.15, "Propane" to 0.05))))
        val (specs, x) = Subflowsheets.transferSpecs(into, dis, "DISCHARGE", regions[1].flowsheet)!!
        assertEquals(mapOf("pressure" to 1.5e6, "temperature" to 360.0, "molarFlow" to 0.0278), specs.mapValues { it.value.si })
        assertEquals(0.8, x.getValue("Methane"), 1e-15)
        val pvf = Subflowsheets.transferSpecs(into.copy(basis = TransferBasis.PVF), dis, "DISCHARGE", regions[1].flowsheet)!!
        assertEquals(setOf("pressure", "vapourFraction", "molarFlow"), pvf.first.keys)
        assertEquals(0.0, Subflowsheets.change(pvf, pvf))
        // A compound the other package lacks is refused, never dropped.
        val narrow = regions[1].flowsheet.copy(compounds = listOf("Methane", "Ethane"))
        val e = assertFailsWith<EditRejected> { Subflowsheets.transferSpecs(into, dis, "DISCHARGE", narrow) }
        assertTrue("Propane" in e.message!!)
        // Results combine: the worst status, every object's result, the block's messages labelled.
        val r0 = SolveResult(SolveStatus.Converged, objects = mapOf("dis" to dis))
        val r1 = SolveResult(SolveStatus.Failed, objects = mapOf("e" to ObjectResult(ObjectStatus.Error, error = "x")))
        val all = Subflowsheets.combine(g, listOf(regions[0] to r0, regions[1] to r1), 2)
        assertEquals(SolveStatus.Failed, all.status)
        assertEquals(setOf("dis", "e"), all.objects.keys)
        assertEquals(2, all.iterations)
    }

    @Test
    fun anEnergyStreamCannotCrossAPackageBoundary() {
        val f = train.copy(
            objects = train.objects + SimObject("q", "Q-100", ObjectType.EnergyStream, 360.0, 60.0),
            connections = train.connections + Connection("e", 1, "q", 0),
        )
        val (g0, block) = Subflowsheets.group(f, listOf("e"), 360.0, 0.0)
        val g = Subflowsheets.setFluid(g0, block.id, SubFluid("Soave-Redlich-Kwong (SRK)", f.compounds))
        assertFailsWith<EditRejected> { Subflowsheets.regions(g) }
        // On the parent's package it is simply joined.
        assertEquals(shape(f), shape(Subflowsheets.flatten(g0)))
    }

    @Test
    fun linkingFollowsHysysRules() {
        val (g, block) = Subflowsheets.group(train, listOf("e"), 360.0, 0.0)
        val innerFeed = block.ports.first { it.outer == "dis" }.inner
        // Unmatch, then only DISCHARGE (an open product of the parent) can be matched to the inner feed.
        val u = Subflowsheets.link(g, block.id, innerFeed, null)
        assertNull(Subflowsheets.of(u, block.id)!!.ports.firstOrNull { it.inner == innerFeed })
        assertEquals(listOf("dis"), Subflowsheets.outerCandidates(u, block.id, innerFeed).map { it.id })
        assertFailsWith<EditRejected> { Subflowsheets.link(u, block.id, innerFeed, "suc") }
        // Deleting the block deletes what is inside and keeps the parent's streams.
        val d = Subflowsheets.delete(g, block.id)
        assertTrue(d.objects.none { it.id == "e" } && d.objects.any { it.id == "dis" } && d.subflowsheets.isEmpty())
        assertNotEquals(g.modelHash(), d.modelHash())
        // Moving a block changes no result.
        assertEquals(g.modelHash(), Subflowsheets.move(g, block.id, 999.0, -48.0).modelHash())
    }

    @Test
    fun aStreamPassesThroughOneBlockStraightIntoAnother() {
        // DISCHARGE (main) → X on block P (main package) → inside P, X runs straight into block A (own package), where
        // A's boundary stream IN feeds E-100. X has no connection on P: its match to A is what drains it.
        val f = train.copy(
            objects = train.objects.filter { it.id != "out" }.map { if (it.id == "e") it.copy(parent = "a") else it } + listOf(
                SimObject("x", "X", ObjectType.MaterialStream, 0.0, 0.0, parent = "pp"),
                SimObject("in", "IN", ObjectType.MaterialStream, 0.0, 0.0, parent = "a"),
                SimObject("o", "OUT", ObjectType.MaterialStream, 0.0, 0.0, parent = "a"),
            ),
            connections = listOf(Connection("suc", 0, "k", 0), Connection("k", 0, "dis", 0), Connection("in", 0, "e", 0), Connection("e", 0, "o", 0)),
            subflowsheets = listOf(
                Subflowsheet("pp", "P", ports = listOf(SubPort("x", "dis"))),
                Subflowsheet("a", "A", parent = "pp", ports = listOf(SubPort("in", "x"))),
            ),
        )
        assertEquals(listOf("x"), Subflowsheets.boundary(f, "pp").filter { it.feed }.map { it.stream.id })
        assertEquals(listOf("dis"), Subflowsheets.outerCandidates(f, "pp", "x").map { it.id } + emptyList())
        // On one package: IN and X both join DISCHARGE, and both get its result.
        val flat = Subflowsheets.flatten(f)
        assertTrue(flat.connections.contains(Connection("dis", 0, "e", 0)))
        assertEquals(setOf("x" to "dis", "in" to "dis"), Subflowsheets.joinedAll(f).toList().toSet())
        // On its own package, A takes DISCHARGE itself (X is joined into it on the main package).
        val own = f.copy(subflowsheets = f.subflowsheets.map { if (it.id == "a") it.copy(fluid = SubFluid("Soave-Redlich-Kwong (SRK)", f.compounds)) else it })
        val (_, transfers) = Subflowsheets.regions(own)
        assertEquals(listOf(Transfer(null, "dis", "a", "in")), transfers)
        // A boundary stream deleted inside leaves no dangling match.
        val gone = own.copy(objects = own.objects.filter { it.id != "in" }, connections = own.connections.filter { it.fromObject != "in" })
        assertTrue(Subflowsheets.regions(gone).second.isEmpty())
    }

    @Test
    fun aStreamOfTheParentGoesIntoAnEmptyBlockAsHysysConnections() {
        // DISCHARGE has a free end once E-100 is gone: it can go into an empty block, which makes its boundary stream.
        val f = train.copy(objects = train.objects.filter { it.id !in setOf("e", "out") }, connections = train.connections.take(2))
        val (f1, b) = Subflowsheets.add(f, null, 300.0, 0.0)
        assertEquals(listOf("dis"), Subflowsheets.connectable(f1, b.id, true).map { it.id })
        val (f2, inner) = Subflowsheets.connect(f1, b.id, "dis", true)
        val bd = Subflowsheets.boundary(f2, b.id).single()
        assertEquals(inner, bd.stream.id); assertTrue(bd.feed); assertEquals("dis", bd.port?.outer)
        assertEquals("DISCHARGE-SUB-1", bd.stream.tag)
        // Nothing inside is joined to it yet, and it stays matched; flattened, the engine sees DISCHARGE as before.
        assertEquals(f2, Subflowsheets.clean(f2))
        assertEquals(shape(f), shape(Subflowsheets.flatten(f2)))
        // DISCHARGE is no longer free; a stream joined at both ends can't go in.
        assertTrue(Subflowsheets.connectable(f2, b.id, true).isEmpty())
        assertFailsWith<EditRejected> { Subflowsheets.connect(f2, b.id, "suc", true) }
        // Taken out again: the stream inside goes with it (nothing was joined to it).
        val f3 = Subflowsheets.disconnect(f2, b.id, inner)
        assertTrue(f3.objects.none { it.id == inner } && Subflowsheets.of(f3, b.id)!!.ports.isEmpty())
        // A stream out: COOLED-like free start.
        val g = f1.copy(objects = f1.objects + SimObject("q", "Q", ObjectType.MaterialStream))
        val (g2, qi) = Subflowsheets.connect(g, b.id, "q", false)
        assertEquals(false, Subflowsheets.boundary(g2, b.id).first { it.stream.id == qi }.feed)
    }

    @Test
    fun whatAnEditMakesInsideABlockBelongsToIt() {
        val (g, block) = Subflowsheets.group(train, listOf("e"), 360.0, 0.0)
        // A stream wired from E-100's free port to empty canvas while the block is open.
        val (wired, _) = FlowsheetEditing.add(g, ObjectType.MaterialStream, 500.0, 100.0)
        val adopted = Subflowsheets.adopt(g, wired, block.id)
        assertEquals(block.id, adopted.objects.last().parent)
        // On the main flowsheet nothing changes, and objects that were already somewhere stay there.
        assertEquals(wired, Subflowsheets.adopt(g, wired, null))
        assertEquals(g.objects.map { it.parent }, adopted.objects.dropLast(1).map { it.parent })
    }

    // ---------- dragging objects into a sub-flowsheet, and duplicating one ----------

    @Test
    fun movingTheCompressorIntoTheCoolersBlockKeepsTheFlowsheetTheEngineSolves() {
        val (g, block) = Subflowsheets.group(train, listOf("e"), 360.0, 0.0)
        val m = Subflowsheets.moveInto(g, listOf("k"), block.id)
        // K-100 is inside; DISCHARGE, which ran straight from it into the block, is inside too, keeping its id and tag.
        assertEquals(block.id, m.obj("k")!!.parent)
        assertEquals(block.id, m.obj("dis")!!.parent)
        assertEquals("DISCHARGE", m.obj("dis")!!.tag)
        // Only SUCTION and COOLED are left on the main flowsheet, each matched across the block's edge.
        assertEquals(setOf("SUCTION", "COOLED"), m.objects.filter { it.parent == null }.map { it.tag }.toSet())
        assertEquals(setOf("suc", "out"), Subflowsheets.of(m, block.id)!!.ports.map { it.outer }.toSet())
        // The engine sees exactly the flowsheet it solved before: same numbers.
        assertEquals(shape(train), shape(Subflowsheets.flatten(m)))
        // The other way round (browser test 4o): K-100 dragged into an empty block, then DISCHARGE, which the block now
        // gives out, and E-100 dragged in together.
        val (e0, b2) = Subflowsheets.add(train, null, 240.0, 240.0)
        val k1 = Subflowsheets.moveInto(e0, listOf("k"), b2.id)
        val k2 = Subflowsheets.moveInto(k1, listOf("dis", "e"), b2.id)
        assertEquals(listOf(b2.id, b2.id, b2.id), listOf("k", "dis", "e").map { k2.obj(it)!!.parent })
        assertEquals("DISCHARGE", k2.obj("dis")!!.tag)
        assertEquals(shape(train), shape(Subflowsheets.flatten(k2)))
    }

    @Test
    fun movingAStreamWithItsUnitAndFromAnEmptyBlockGivesTheSameFlowsheet() {
        // Everything but SUCTION, dragged into an empty block in two goes: first DISCHARGE and E-100, then K-100.
        val (f0, b) = Subflowsheets.add(train, null, 600.0, 0.0)
        val m1 = Subflowsheets.moveInto(f0, listOf("dis", "e"), b.id)
        // DISCHARGE now crosses the block's edge: outside, a twin stands for it (its tag the engine's only change).
        fun units(f: Flowsheet) = shape(f.copy(objects = f.objects.map { if (it.type.isStream) it.copy(tag = it.tag.substringBefore("-SUB")) else it }))
        assertEquals(units(train), units(Subflowsheets.flatten(m1)))
        val m2 = Subflowsheets.moveInto(m1, listOf("k"), b.id)
        assertEquals(shape(train), shape(Subflowsheets.flatten(m2)))
        assertEquals(setOf("SUCTION", "COOLED"), m2.objects.filter { it.parent == null }.map { it.tag }.toSet())
        // Moved back out (ungrouped), the case is the train again.
        assertEquals(shape(train), shape(Subflowsheets.ungroup(m2, b.id)))
    }

    @Test
    fun movingIntoABlockOnItsOwnPackageNeedsItsComponents() {
        val (f0, b) = Subflowsheets.add(train, null, 600.0, 0.0)
        val own = Subflowsheets.setFluid(f0, b.id, SubFluid("Peng-Robinson (PR)", listOf("Methane", "Ethane")))
        val e = assertFailsWith<EditRejected> { Subflowsheets.moveInto(own, listOf("suc", "k"), b.id) }
        assertTrue("Propane" in e.message!!, e.message)
        // Equipment without a composition of its own moves in.
        assertEquals(b.id, Subflowsheets.moveInto(own, listOf("k"), b.id).obj("k")!!.parent)
        // A stream matched to another block stays on that block's flowsheet: DISCHARGE feeds a cooler block.
        val (g, cooler) = Subflowsheets.group(train, listOf("e"), 360.0, 0.0)
        val (g2, other) = Subflowsheets.add(g, null, 0.0, 300.0)
        val r = assertFailsWith<EditRejected> { Subflowsheets.moveInto(g2, listOf("k", "dis"), other.id) }
        assertTrue("DISCHARGE" in r.message!! && cooler.tag in r.message!!, r.message)
        assertFailsWith<EditRejected> { Subflowsheets.group(g2, listOf("k", "dis"), 0.0, 0.0) }
        // Its unit alone moves, and the stream stays matched, now crossing into the new block too.
        val k = Subflowsheets.moveInto(g2, listOf("k"), other.id)
        assertEquals(shape(train), shape(Subflowsheets.flatten(k)))
        // Only from the flowsheet the block is on.
        val (f1, inner) = Subflowsheets.add(f0, b.id, 0.0, 0.0)
        assertFailsWith<EditRejected> { Subflowsheets.moveInto(f1, listOf("k"), inner.id) }
    }

    @Test
    fun duplicatingABlockCopiesEverythingInsideWithNewIdsAndTags() {
        // The cooler in a block, with a nested block holding nothing but a note of a stream; a table on E-100.
        val (g0, block) = Subflowsheets.group(train, listOf("e"), 360.0, 0.0)
        val (g1, nested) = Subflowsheets.add(g0, block.id, 0.0, 0.0)
        val g = FlowsheetEditing.setTable(g1, "e", listOf("duty"))
        val (d, copy, notes) = Subflowsheets.duplicate(g, block.id)
        // The original is untouched.
        assertEquals(g.objects, d.objects.filter { o -> g.objects.any { it.id == o.id } })
        // The copy: a new block (and a new nested one) with the same contents, new ids, unique tags.
        val copied = d.objects.filter { it.parent == copy.id }
        val orig = g.objects.filter { it.parent == block.id }
        assertEquals(orig.map { it.type }.sorted(), copied.map { it.type }.sorted())
        assertTrue(copied.none { c -> g.objects.any { it.id == c.id } })
        assertEquals(d.objects.size, d.objects.map { it.tag.lowercase() }.toSet().size)
        assertEquals((d.subflowsheets.map { it.tag } + d.objects.map { it.tag }).size, (d.subflowsheets.map { it.tag.lowercase() } + d.objects.map { it.tag.lowercase() }).toSet().size)
        assertEquals(1, d.subflowsheets.count { it.parent == copy.id && it.id != nested.id })
        assertEquals(block.x + 48.0, copy.x)
        // Inside, the copy is joined as the original: E-100's feed and product, and its table.
        val cooler = copied.first { it.type == ObjectType.Cooler }
        assertEquals(2, d.connections.count { it.fromObject == cooler.id || it.toObject == cooler.id })
        assertEquals(listOf("duty"), d.tables.first { it.objectId == cooler.id }.keys)
        // Its streams in and out are there, not matched to anything outside, and the notes say so.
        assertEquals(2, Subflowsheets.boundary(d, copy.id).size)
        assertTrue(Subflowsheets.of(d, copy.id)!!.ports.all { it.outer == null })
        assertTrue(notes.any { "not connected" in it }, notes.toString())
        // The original still solves as the train; the copy is not ready until its streams are connected.
        assertEquals(shape(train), shape(Subflowsheets.flatten(g)))
        assertTrue(Readiness.of(d).notReady.keys.any { it in copied.map { c -> c.id } })
    }
}
