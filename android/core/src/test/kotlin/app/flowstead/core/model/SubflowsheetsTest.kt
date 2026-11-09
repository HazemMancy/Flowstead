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
}
