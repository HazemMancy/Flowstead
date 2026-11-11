/*
 * Flowstead (D133): the sub-flowsheet solve, with a stand-in engine that counts its solves.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 *
 * The stand-in returns, for a feed stream, its specs as results, and for a heater, its outlet at the heater's outlet
 * temperature and the inlet's pressure, flow and composition (enough to follow what crosses each boundary). The real
 * engine's numbers for these cases are checked in the browser test (4m) and on the device.
 */
package app.flowstead.core.model

import app.flowstead.core.api.ObjectResult
import app.flowstead.core.api.ObjectStatus
import app.flowstead.core.api.PhaseResult
import app.flowstead.core.api.SolveResult
import app.flowstead.core.api.SolveStatus
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SubflowsheetSolveTest {
    private val solved = ArrayList<Flowsheet>()

    private fun stream(t: Double, p: Double, f: Double, x: Map<String, Double>) = ObjectResult(ObjectStatus.Solved,
        properties = mapOf("temperature" to t, "pressure" to p, "molarFlow" to f, "vapourFraction" to 0.0),
        phases = mapOf("overall" to PhaseResult(composition = x)))

    private val fake: suspend (Flowsheet) -> SolveResult = { f ->
        solved += f
        assertTrue(f.subflowsheets.isEmpty() && f.objects.all { it.parent == null }, "the engine got a block")
        val out = HashMap<String, ObjectResult>()
        f.objects.filter { it.type == ObjectType.MaterialStream && f.connections.none { c -> c.toObject == it.id } && it.specs.isNotEmpty() }.forEach { s ->
            out[s.id] = stream(s.specs.getValue("temperature").si, s.specs.getValue("pressure").si, s.specs.getValue("molarFlow").si, s.composition)
        }
        repeat(4) {
            f.objects.filter { it.type == ObjectType.Heater }.forEach { h ->
                val inlet = f.connections.firstOrNull { it.toObject == h.id }?.let { out[it.fromObject] } ?: return@forEach
                val o = f.connections.first { it.fromObject == h.id }.toObject
                out[o] = stream(h.specs.getValue("outletTemperature").si, inlet.properties.getValue("pressure"), inlet.properties.getValue("molarFlow"), inlet.phases!!.getValue("overall").composition)
                out[h.id] = ObjectResult(ObjectStatus.Solved)
            }
        }
        SolveResult(SolveStatus.Converged, objects = out)
    }

    private val water = mapOf("Water" to 1.0)
    private val steamCase = Flowsheet(
        propertyPackage = "Peng-Robinson (PR)", compounds = listOf("Water"),
        objects = listOf(
            SimObject("feed", "FEED", ObjectType.MaterialStream, specs = mapOf("temperature" to Spec(298.15, "C"), "pressure" to Spec(5e5, "bara"), "molarFlow" to Spec(55.5, "mol/s")), composition = water),
            SimObject("prod", "PRODUCT", ObjectType.MaterialStream),
            SimObject("in", "IN", ObjectType.MaterialStream, parent = "blk"),
            SimObject("h", "H-100", ObjectType.Heater, parent = "blk", specs = mapOf("outletTemperature" to Spec(423.15, "C"))),
            SimObject("out", "OUT", ObjectType.MaterialStream, parent = "blk"),
        ),
        connections = listOf(Connection("in", 0, "h", 0), Connection("h", 0, "out", 0)),
        subflowsheets = listOf(Subflowsheet("blk", "STEAM", fluid = SubFluid("Steam Tables (IAPWS-IF97)", listOf("Water")),
            ports = listOf(SubPort("in", "feed"), SubPort("out", "prod")))),
    )

    @Test
    fun ownPackageSolvesEachPartOnItsPackageInThreeSolves() = runBlocking {
        val r = SubflowsheetSolve.solve(steamCase, fake)
        // The main part without PRODUCT, the block on the steam tables, then the main part with PRODUCT.
        assertEquals(listOf("Peng-Robinson (PR)", "Steam Tables (IAPWS-IF97)", "Peng-Robinson (PR)"), solved.map { it.propertyPackage })
        assertEquals(SolveStatus.Converged, r.status)
        // IN crosses at FEED's T, P and flow; PRODUCT at OUT's.
        val p = { id: String, k: String -> r.objects.getValue(id).properties.getValue(k) }
        assertEquals(listOf(298.15, 5e5, 55.5), listOf(p("in", "temperature"), p("in", "pressure"), p("in", "molarFlow")))
        assertEquals(listOf(423.15, 5e5, 55.5), listOf(p("prod", "temperature"), p("prod", "pressure"), p("prod", "molarFlow")))
        assertTrue(r.messages.any { it.text == "Fluid packages: 2 parts, 3 engine solves" })
    }

    @Test
    fun onOnePackageTheEngineGetsTheFlatCaseOnce() = runBlocking {
        val one = steamCase.copy(subflowsheets = steamCase.subflowsheets.map { it.copy(fluid = null) })
        val r = SubflowsheetSolve.solve(one, fake)
        assertEquals(1, solved.size)
        assertEquals(setOf("FEED", "H-100", "PRODUCT"), solved.single().objects.map { it.tag }.toSet())
        // The inner streams get their outer streams' results.
        assertEquals(r.objects.getValue("feed"), r.objects.getValue("in"))
        assertEquals(r.objects.getValue("prod"), r.objects.getValue("out"))
    }

    @Test
    fun aLoopBetweenPackagesWithoutAnEstimateAsksForOne() {
        // PRODUCT comes back into the block's feed: the block feeds itself through the main part.
        val loop = steamCase.copy(
            objects = steamCase.objects.filter { it.id != "feed" } + SimObject("ret", "RETURN", ObjectType.MaterialStream),
            connections = steamCase.connections,
            subflowsheets = steamCase.subflowsheets.map { it.copy(ports = listOf(SubPort("in", "prod2"), SubPort("out", "prod"))) },
        ).let { f -> f.copy(objects = f.objects + SimObject("prod2", "PROD2", ObjectType.MaterialStream)) }
        val e = assertFailsWith<EditRejected> { runBlocking { SubflowsheetSolve.solve(loop, fake) } }
        assertTrue("estimate" in e.message!! || "no result" in e.message!!, e.message)
    }

    @Test
    fun aToolGetsThePartOnItsObjectsPackage() {
        assertEquals("Steam Tables (IAPWS-IF97)", SubflowsheetSolve.forObject(steamCase, "h").propertyPackage)
        assertEquals("Peng-Robinson (PR)", SubflowsheetSolve.forObject(steamCase, "feed").propertyPackage)
        assertTrue(SubflowsheetSolve.forObject(steamCase, "h").objects.none { it.id == "feed" })
    }
}
