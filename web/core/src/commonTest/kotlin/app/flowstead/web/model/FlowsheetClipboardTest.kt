/*
 * Flowstead web (D124): copy and paste of several objects, on the JVM and in JavaScript.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Benchmark 06's train (SUCTION → K-100 → DISCHARGE → E-100 → COOLED) is copied and pasted: what defines each copy
 * must be exactly what defines its original, and every connection between copied objects must follow the copies.
 */
package app.flowstead.web.model

import app.flowstead.core.api.ObjectResult
import app.flowstead.core.api.ObjectStatus
import app.flowstead.core.api.PhaseResult
import app.flowstead.core.api.SolveResult
import app.flowstead.core.api.SolveStatus
import app.flowstead.core.model.AdjustKeys
import app.flowstead.core.model.Connection
import app.flowstead.core.model.EditRejected
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.FlowsheetEditing
import app.flowstead.core.model.FlowsheetJson
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.PropertyTable
import app.flowstead.core.model.Reaction
import app.flowstead.core.model.ReactionType
import app.flowstead.core.model.ReactorKeys
import app.flowstead.core.model.Readiness
import app.flowstead.core.model.ResultView
import app.flowstead.core.model.SimObject
import app.flowstead.core.model.Spec
import app.flowstead.core.model.Specification
import app.flowstead.core.model.StoredSolve
import app.flowstead.core.model.isFeed
import app.flowstead.core.model.modelHash
import app.flowstead.core.model.obj
import app.flowstead.core.units.UnitSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlowsheetClipboardTest {
    private val units = UnitSet.SI

    /** Benchmark 06 as input.json has it (the shape; the test data generator is JVM-only, so it is written out here). */
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
        connections = listOf(
            Connection("suc", 0, "k", 0), Connection("k", 0, "dis", 0), Connection("dis", 0, "e", 0), Connection("e", 0, "out", 0),
        ),
        tables = listOf(PropertyTable("out", listOf("temperature", "pressure"), 0.0, 60.0)),
    )

    private val none = ResultView(null, train.modelHash())

    @Test
    fun aWholeTrainPastesAsAnIdenticalTrain() {
        val clip = FlowsheetClipboard.copy(train, train.objects.map { it.id }, none, units)
        val r = FlowsheetClipboard.paste(train, clip, 0.0, 240.0)
        val f = r.flowsheet
        assertEquals(10, f.objects.size)
        assertEquals(5, r.ids.size)
        assertTrue(r.notes.isEmpty(), r.notes.toString())
        val copies = r.ids.map { f.obj(it)!! }
        // Same type, specs and composition as the original in the same place in the list, 240 below it.
        for ((o, c) in train.objects.zip(copies)) {
            assertEquals(o.type, c.type); assertEquals(o.specs, c.specs); assertEquals(o.composition, c.composition)
            assertEquals(o.x, c.x); assertEquals(o.y + 240.0, c.y)
            assertTrue(c.id !in train.objects.map { it.id })
        }
        // New tags, the next free of each type, as the palette gives them.
        assertEquals(listOf("1", "K-101", "2", "E-101", "3"), copies.map { it.tag })
        // The four connections follow the copies, port for port.
        val map = train.objects.map { it.id }.zip(r.ids).toMap()
        assertEquals(train.connections.map { it.copy(fromObject = map.getValue(it.fromObject), toObject = map.getValue(it.toObject)) },
            f.connections.drop(4))
        // Its table comes too, with the same offset.
        assertEquals(PropertyTable(map.getValue("out"), listOf("temperature", "pressure"), 0.0, 60.0), f.tables.last())
        // The copy is as ready to solve as the original: each copy has its original's specification status.
        for ((o, c) in train.objects.zip(copies)) assertEquals(Specification.status(train, o)::class, Specification.status(f, c)::class)
        assertTrue(Readiness.of(f).notReady.isEmpty())
    }

    @Test
    fun tagsAreKeptOnAFlowsheetThatDoesNotUseThem() {
        val empty = Flowsheet(propertyPackage = "Peng-Robinson (PR)", compounds = train.compounds)
        val clip = FlowsheetClipboard.copy(train, train.objects.map { it.id }, none, units)
        val r = FlowsheetClipboard.paste(empty, clip, 0.0, 0.0)
        assertEquals(train.objects.map { it.tag }, r.flowsheet.objects.map { it.tag })
    }

    @Test
    fun aStreamCutFromItsSourceBecomesAFeedWithItsSolvedConditions() {
        // DISCHARGE is calculated: copied with the cooler and COOLED but not the compressor, it pastes as a feed. Its
        // specs are the engine's numbers at the copy, exactly (the app's Duplicate-as-a-feed rule).
        val x = mapOf("Methane" to 0.8, "Ethane" to 0.15, "Propane" to 0.05)
        val result = SolveResult(SolveStatus.Converged, objects = mapOf(
            "dis" to ObjectResult(ObjectStatus.Solved, properties = mapOf("temperature" to 401.23, "pressure" to 3.0e6, "molarFlow" to 0.1, "vapourFraction" to 1.0),
                phases = mapOf("overall" to PhaseResult(composition = x))),
        ))
        val view = ResultView(StoredSolve(result, train.modelHash(), 0L), train.modelHash())
        val clip = FlowsheetClipboard.copy(train, listOf("dis", "e", "out"), view, units)
        val r = FlowsheetClipboard.paste(train, clip, 0.0, 240.0)
        val feed = r.flowsheet.obj(r.ids[0])!!
        assertTrue(r.flowsheet.isFeed(feed))
        assertEquals(setOf("temperature", "pressure", "molarFlow"), feed.specs.keys)
        assertEquals(401.23, feed.specs.getValue("temperature").si)
        assertEquals(3.0e6, feed.specs.getValue("pressure").si)
        assertEquals(0.1, feed.specs.getValue("molarFlow").si)
        assertEquals(x, feed.composition)
        assertEquals(1, r.notes.size); assertTrue("feed" in r.notes[0], r.notes[0])
        assertTrue(Readiness.of(r.flowsheet).notReady.keys.none { it in r.ids })
    }

    @Test
    fun withoutCurrentResultsTheCutStreamIsAFeedToSpecifyAndTheNoteSaysSo() {
        val clip = FlowsheetClipboard.copy(train, listOf("dis", "e", "out"), none, units)
        val r = FlowsheetClipboard.paste(train, clip, 0.0, 240.0)
        val feed = r.flowsheet.obj(r.ids[0])!!
        assertTrue(feed.specs.isEmpty() && feed.composition.isEmpty())
        assertTrue("specify" in r.notes.single(), r.notes.toString())
    }

    @Test
    fun anAdjustLosesOnlyTheReferencesThatWereNotCopied() {
        val (withAdj, adj) = FlowsheetEditing.add(train, ObjectType.Adjust, 240.0, -120.0)
        val fs = withAdj.copy(objects = withAdj.objects.map {
            if (it.id == adj.id) it.copy(refs = mapOf(AdjustKeys.MANIPULATED_OBJECT to "e", AdjustKeys.MANIPULATED_SPEC to "outletTemperature",
                AdjustKeys.CONTROLLED_OBJECT to "out", AdjustKeys.CONTROLLED_PROPERTY to "vapourFraction")) else it
        })
        val clip = FlowsheetClipboard.copy(fs, listOf(adj.id, "e"), ResultView(null, fs.modelHash()), units)
        val r = FlowsheetClipboard.paste(fs, clip, 0.0, 240.0)
        val copies = r.ids.map { r.flowsheet.obj(it)!! }
        val copy = copies.single { it.type == ObjectType.Adjust }
        assertEquals(copies.single { it.type == ObjectType.Cooler }.id, copy.refs[AdjustKeys.MANIPULATED_OBJECT]) // follows the copied cooler
        assertNull(copy.refs[AdjustKeys.CONTROLLED_OBJECT]) // COOLED was not copied: two Adjusts on one variable would fight
        assertEquals("vapourFraction", copy.refs[AdjustKeys.CONTROLLED_PROPERTY])
        assertTrue(r.notes.single().contains("COOLED"), r.notes.toString())
    }

    @Test
    fun aPsvBringsItsReliefValveDataWithoutItsResult() {
        val (withPsv, psv) = FlowsheetEditing.add(train, ObjectType.ReliefValve, 360.0, -120.0)
        val dev = withPsv.safety!!.devices.single().copy(protectedId = "e", setPressure = 3.5e6)
        val fs = FlowsheetEditing.upsertReliefDevice(withPsv, dev)
        val clip = FlowsheetClipboard.copy(fs, listOf(psv.id), ResultView(null, fs.modelHash()), units)
        val r = FlowsheetClipboard.paste(fs, clip, 48.0, 48.0)
        val copy = r.flowsheet.safety!!.devices.single { it.id == r.ids.single() }
        assertEquals(r.flowsheet.obj(r.ids.single())!!.tag, copy.tag)
        assertEquals(3.5e6, copy.setPressure)
        assertEquals("e", copy.protectedId) // the vessel it protects is still on the flowsheet
        assertNull(copy.result)
    }

    @Test
    fun aComponentTheFlowsheetDoesNotHaveRefusesThePasteAndNamesIt() {
        val clip = FlowsheetClipboard.copy(train, listOf("suc"), none, units)
        val other = Flowsheet(propertyPackage = "Peng-Robinson (PR)", compounds = listOf("Methane", "Ethane"))
        val e = assertFailsWith<EditRejected> { FlowsheetClipboard.paste(other, clip, 0.0, 0.0) }
        assertTrue("Propane" in e.message!!, e.message)
    }

    @Test
    fun theClipSurvivesJson() {
        val clip = FlowsheetClipboard.copy(train, train.objects.map { it.id }, none, units)
        val back = FlowsheetJson.decodeFromString(Clip.serializer(), FlowsheetJson.encodeToString(Clip.serializer(), clip))
        assertEquals(clip, back)
    }

    @Test
    fun aGroupMovesTogetherAndDeletesTogether() {
        val moved = FlowsheetClipboard.moveAll(train, listOf("k", "dis"), 24.0, 48.0)
        assertEquals(144.0 to 48.0, moved.obj("k")!!.let { it.x to it.y })
        assertEquals(264.0 to 48.0, moved.obj("dis")!!.let { it.x to it.y })
        assertEquals(0.0 to 0.0, moved.obj("suc")!!.let { it.x to it.y })
        val gone = FlowsheetClipboard.deleteAll(train, listOf("k", "dis"))
        assertEquals(listOf("suc", "e", "out"), gone.objects.map { it.id })
        assertEquals(1, gone.connections.size)
    }

    @Test
    fun aTagIsNeverGivenTwiceIgnoringCase() {
        // Tags are unique ignoring case (rename's rule): a compressor renamed k-100 must not get a copy tagged K-100.
        val fs = train.copy(objects = train.objects.map { if (it.id == "k") it.copy(tag = "k-100") else it })
        val clip = FlowsheetClipboard.copy(fs, listOf("k"), ResultView(null, fs.modelHash()), units)
        val tags = mutableListOf<String>()
        var f = fs
        repeat(2) { val r = FlowsheetClipboard.paste(f, clip, 48.0, 48.0); f = r.flowsheet; tags += f.obj(r.ids.single())!!.tag }
        assertEquals(listOf("K-101", "K-102"), tags)
        assertEquals(f.objects.size, f.objects.map { it.tag.lowercase() }.toSet().size)
    }

    @Test
    fun aReactorBringsItsReactionsToAnotherProject() {
        val rx = Reaction("rx1", "Ethane cracking", ReactionType.Conversion, mapOf("Ethane" to -1.0, "Methane" to 2.0), baseCompound = "Ethane", conversion = 0.5)
        val (withR, reactor) = FlowsheetEditing.add(train.copy(reactions = listOf(rx)), ObjectType.ConversionReactor, 600.0, 0.0)
        val fs = withR.copy(objects = withR.objects.map { if (it.id == reactor.id) it.copy(refs = mapOf(ReactorKeys.REACTIONS to "rx1")) else it })
        val clip = FlowsheetClipboard.copy(fs, listOf(reactor.id), ResultView(null, fs.modelHash()), units)
        // Another project with the components but no reactions: the reaction comes with the reactor, and the note says so.
        val other = Flowsheet(propertyPackage = "Peng-Robinson (PR)", compounds = train.compounds)
        val r = FlowsheetClipboard.paste(other, clip, 0.0, 0.0)
        assertEquals(listOf(rx), r.flowsheet.reactions)
        assertEquals("rx1", r.flowsheet.obj(r.ids.single())!!.refs[ReactorKeys.REACTIONS])
        assertTrue(r.notes.single().contains("Ethane cracking"), r.notes.toString())
        // On its own flowsheet the reaction is already there: not added twice, no note.
        val same = FlowsheetClipboard.paste(fs, clip, 48.0, 48.0)
        assertEquals(1, same.flowsheet.reactions.size)
        assertTrue(same.notes.isEmpty(), same.notes.toString())
        // A project without a component the reaction uses: refused, naming it.
        val lean = Flowsheet(propertyPackage = "Peng-Robinson (PR)", compounds = listOf("Methane"))
        assertTrue("Ethane" in assertFailsWith<EditRejected> { FlowsheetClipboard.paste(lean, clip, 0.0, 0.0) }.message!!)
    }
}
