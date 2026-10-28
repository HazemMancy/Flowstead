/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.core.model

import app.thermodock.core.api.DepressuringResult
import app.thermodock.core.api.ObjectResult
import app.thermodock.core.api.ObjectStatus
import app.thermodock.core.api.PhaseResult
import app.thermodock.core.api.ReliefResult
import app.thermodock.core.api.ReliefScenario
import app.thermodock.core.api.ReliefScenarioKinds
import app.thermodock.core.api.SolveResult
import app.thermodock.core.api.SolveStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SafetyTest {
    private val feed = SimObject("f", "FEED", ObjectType.MaterialStream)
    private val sep = SimObject("v", "V-100", ObjectType.Separator)
    private val fs = Flowsheet(compounds = listOf("Propane"), objects = listOf(feed, sep), connections = listOf(Connection("f", 0, "v", 0)))
    private val solved = SolveResult(
        SolveStatus.Converged,
        objects = mapOf("f" to ObjectResult(
            ObjectStatus.Solved, properties = mapOf("temperature" to 300.0, "pressure" to 8e5, "massFlow" to 2.0),
            phases = mapOf("overall" to PhaseResult(composition = mapOf("Propane" to 1.0))),
        )),
    )
    private val results = ResultView(StoredSolve(solved, fs.modelHash(), 0), fs.modelHash())

    @Test
    fun aValveTakesItsFluidFromTheVesselFeed() {
        val d = ReliefDeviceDef("d", "PSV-100", protectedId = "v", setPressure = 18e5,
            scenarios = listOf(ReliefScenario(ReliefScenarioKinds.BLOCKED_OUTLET)))
        assertNull(Safety.problem(fs, results, d))
        val req = Safety.request(fs, results, d)
        assertEquals(mapOf("Propane" to 1.0), req.composition)
        assertEquals(300.0, req.operatingTemperature)
        // A blocked outlet relieves the whole feed at its temperature.
        assertEquals(2.0, req.scenarios.single().massFlow)
        assertEquals(300.0, req.scenarios.single().temperature)
        assertEquals("Give the vessel's dimensions for the fire case",
            Safety.problem(fs, results, d.copy(scenarios = listOf(ReliefScenario(ReliefScenarioKinds.FIRE)))))
        assertEquals("Solve the flowsheet first: the fluid and its state come from the solve",
            Safety.problem(fs, ResultView(null, fs.modelHash()), d))
    }

    @Test
    fun editingAValveDropsItsResultButRenamingKeepsIt() {
        val d = ReliefDeviceDef("d", "PSV-100", protectedId = "v", setPressure = 18e5)
        var f = FlowsheetEditing.upsertReliefDevice(fs, d)
        f = FlowsheetEditing.setReliefResult(f, "d", ReliefResult(orifice = "K"), "h")
        assertEquals("K", FlowsheetEditing.upsertReliefDevice(f, d.copy(tag = "PSV-200")).safety!!.devices.single().result?.orifice)
        assertNull(FlowsheetEditing.upsertReliefDevice(f, d.copy(setPressure = 20e5)).safety!!.devices.single().result)
        // The safety analysis changes no result.
        assertEquals(fs.modelHash(), f.modelHash())
    }

    @Test
    fun depressuringPlotsDrawTheLiquidOnlyWhileThereIsSome() {
        val r = DepressuringResult(
            time = listOf(0.0, 10.0, 20.0, 30.0), pressure = listOf(3e6, 2e6, 1.5e6, 1e6), temperature = listOf(300.0, 290.0, 280.0, 275.0),
            liquidTemperature = listOf(300.0, 295.0, 290.0, null), wallTemperature = listOf(300.0, 299.0, 298.0, 297.0),
            wettedWallTemperature = listOf(300.0, 298.0, null, null), massFlow = listOf(2.0, 1.5, 1.0, 0.5),
            liquidVolumeFraction = listOf(0.2, 0.15, 0.1, 0.0),
        )
        val plots = Safety.depressuringPlots(r)
        val temps = plots.first { it.title == "Temperature" }.series
        assertEquals(listOf("Vapour", "Liquid", "Dry wall", "Wetted wall"), temps.map { it.label })
        assertEquals(listOf(0.0, 10.0, 20.0), temps[1].x)
        assertEquals(listOf(0.0, 10.0), temps[3].x)
        assertEquals(listOf("Pressure", "Temperature", "Liquid", "Flow"), plots.map { it.title })
    }

    @Test
    fun scenariosAreSuggestedFromTheFlowsheet() {
        // HP gas → FV-100 → feed → V-100 → gas out.
        val hp = SimObject("hp", "HP", ObjectType.MaterialStream)
        val fv = SimObject("fv", "FV-100", ObjectType.Valve, specs = mapOf(SpecKey.RATED_CV to Spec(120.0, "ratio")))
        val gas = SimObject("g", "GAS", ObjectType.MaterialStream)
        val sheet = Flowsheet(
            compounds = listOf("Propane"), objects = listOf(hp, fv, feed, sep, gas),
            connections = listOf(Connection("hp", 0, "fv", 0), Connection("fv", 0, "f", 0), Connection("f", 0, "v", 0), Connection("v", 0, "g", 0)),
        )
        val solve = SolveResult(
            SolveStatus.Converged,
            objects = mapOf(
                "hp" to ObjectResult(ObjectStatus.Solved, properties = mapOf("temperature" to 310.0, "pressure" to 40e5, "vapourFraction" to 1.0),
                    phases = mapOf("overall" to PhaseResult(composition = mapOf("Propane" to 1.0)))),
                "f" to ObjectResult(ObjectStatus.Solved, properties = mapOf("temperature" to 300.0, "pressure" to 8e5, "vapourFraction" to 0.4, "massFlow" to 2.0),
                    phases = mapOf("overall" to PhaseResult(composition = mapOf("Propane" to 1.0)))),
            ),
        )
        val view = ResultView(StoredSolve(solve, sheet.modelHash(), 0), sheet.modelHash())
        val s = Safety.suggest(sheet, view, "v")
        assertEquals(listOf(ReliefScenarioKinds.FIRE, ReliefScenarioKinds.BLOCKED_OUTLET, ReliefScenarioKinds.CONTROL_VALVE_FAILURE), s.map { it.scenario.kind })
        val cv = s[2].scenario
        assertEquals("FV-100 fails open", cv.name)
        assertEquals(40e5, cv.upstreamPressure)
        assertEquals(310.0, cv.temperature)
        assertEquals(120.0, cv.ratedCv)
        // Already listed scenarios are not suggested again.
        val d = ReliefDeviceDef("d", "PSV-100", protectedId = "v", scenarios = s.take(2).map { it.scenario })
        assertEquals(listOf("FV-100 fails open"), Safety.newSuggestions(sheet, view, d).map { it.scenario.name })
    }

    @Test
    fun aPipeSegmentIsProtectedWithItsOwnGeometryAndANetworkIsOneEquivalentPipe() {
        val pipe = SimObject("p", "L-100", ObjectType.PipeSegment, specs = mapOf(
            SpecKey.INNER_DIAMETER to Spec(0.3, "m"), SpecKey.LENGTH to Spec(5000.0, "m"), SpecKey.OUTER_DIAMETER to Spec(0.3239, "m"),
        ))
        val (g, wall) = Safety.pipeGeometry(pipe)!!
        assertEquals(app.thermodock.core.api.VesselGeometry(0.3, 5000.0, pipe = true), g)
        assertEquals(0.01195, wall!!, 1e-6)
        assertEquals(g, Safety.geometry(Flowsheet(objects = listOf(pipe)), "p", null))
        // Two lines: the equivalent pipe keeps their total volume and inside surface.
        val sections = listOf(PipeSection(0.3, 5000.0, 0.01), PipeSection(0.15, 2000.0, 0.007))
        val (eq, w) = Safety.network(sections)!!
        val v = sections.sumOf { Math.PI * it.insideDiameter * it.insideDiameter / 4 * it.length }
        val a = sections.sumOf { Math.PI * it.insideDiameter * it.length }
        assertEquals(v, Math.PI * eq.diameter * eq.diameter / 4 * eq.length, v * 1e-12)
        assertEquals(a, Math.PI * eq.diameter * eq.length, a * 1e-12)
        assertEquals((0.01 * Math.PI * 0.3 * 5000 + 0.007 * Math.PI * 0.15 * 2000) / a, w!!, 1e-12)
    }
}
