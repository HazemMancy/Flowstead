/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.api.DepressuringResult
import app.flowstead.core.api.ObjectResult
import app.flowstead.core.api.ObjectStatus
import app.flowstead.core.api.PhaseResult
import app.flowstead.core.api.ReliefResult
import app.flowstead.core.api.ReliefScenario
import app.flowstead.core.api.ReliefScenarioKinds
import app.flowstead.core.api.SolveResult
import app.flowstead.core.api.SolveStatus
import app.flowstead.core.api.VesselGeometry
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
    fun aBlockedOutletRelievesEveryFeedOfTheVessel() {
        // Two feeds into V-100: 2 kg/s of propane (10 mol/s) and 1 kg/s of butane (5 mol/s); its gas leaves at 310 K, 7.9 bar.
        fun stream(t: Double, p: Double, w: Double, n: Double, x: Map<String, Double>) = ObjectResult(ObjectStatus.Solved,
            properties = mapOf("temperature" to t, "pressure" to p, "massFlow" to w, "molarFlow" to n), phases = mapOf("overall" to PhaseResult(composition = x)))
        val two = Flowsheet(compounds = listOf("Propane", "n-Butane"),
            objects = listOf(feed, SimObject("f2", "FEED 2", ObjectType.MaterialStream), sep, SimObject("g", "GAS", ObjectType.MaterialStream)),
            connections = listOf(Connection("f", 0, "v", 0), Connection("f2", 0, "v", 1), Connection("v", 0, "g", 0)))
        val r = ResultView(StoredSolve(SolveResult(SolveStatus.Converged, objects = mapOf(
            "f" to stream(300.0, 8e5, 2.0, 10.0, mapOf("Propane" to 1.0)),
            "f2" to stream(320.0, 8e5, 1.0, 5.0, mapOf("n-Butane" to 1.0)),
            "g" to stream(310.0, 7.9e5, 2.5, 13.0, mapOf("Propane" to 0.8, "n-Butane" to 0.2)),
        )), two.modelHash(), 0), two.modelHash())
        val req = Safety.request(two, r, ReliefDeviceDef("d", "PSV-100", protectedId = "v", setPressure = 18e5,
            scenarios = listOf(ReliefScenario(ReliefScenarioKinds.BLOCKED_OUTLET))))
        // Hand mixture: 10/15 propane, 5/15 butane; the whole 3 kg/s; the vessel's own state.
        assertEquals(2.0 / 3, req.composition.getValue("Propane"), 1e-12)
        assertEquals(1.0 / 3, req.composition.getValue("n-Butane"), 1e-12)
        assertEquals(3.0, req.scenarios.single().massFlow)
        assertEquals(310.0, req.operatingTemperature)
        assertEquals(7.9e5, req.operatingPressure)
    }

    @Test
    fun aBlowdownTakesTheDesignPressureToTheEngine() {
        // D142: the engine aims at the lower of 6.9 barg and half the design gauge pressure (API 521 §5.20.1).
        val c = DepressuringCase("b", "Blowdown", protectedId = "v", vessel = VesselGeometry(1.5, 4.0), designPressure = 13.01325e5)
        assertNull(Safety.depressuringProblem(fs, results, c))
        assertEquals(13.01325e5, Safety.depressuringRequest(fs, results, c).designPressure)
        assertNull(Safety.depressuringRequest(fs, results, c.copy(designPressure = null)).designPressure)
        assertEquals("The design pressure must be above the back pressure", Safety.depressuringProblem(fs, results, c.copy(designPressure = 101325.0)))
        assertEquals("half the design gauge pressure, the lower of the two (API 521)", Safety.targetBasisText("half the design pressure"))
        // An older case file (no design pressure) still reads.
        assertNull(FlowsheetJson.decodeFromString(DepressuringCase.serializer(), "{\"id\":\"b\",\"name\":\"B\"}").designPressure)
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
        assertEquals(app.flowstead.core.api.VesselGeometry(0.3, 5000.0, pipe = true), g)
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

    @Test
    fun aFixedVolumeSourceAndAFlowsheetValveGoToTheEngine() {
        // D149: V-100 (its contents from the solve, 8 bar) empties into pipe P-1 through FV-100 (rated Cv 50, xT 0.7) on the flowsheet.
        val pipe = SimObject("p", "P-1", ObjectType.PipeSegment, specs = mapOf(SpecKey.INNER_DIAMETER to Spec(0.2, "m"), SpecKey.LENGTH to Spec(1000.0, "m")))
        val valve = SimObject("fv", "FV-100", ObjectType.Valve, specs = mapOf(SpecKey.RATED_CV to Spec(50.0, ""), SpecKey.VALVE_XT to Spec(0.7, "")))
        val ro = SimObject("ro", "RO-100", ObjectType.OrificePlate, specs = mapOf(SpecKey.ORIFICE_DIAMETER to Spec(0.012, "m")))
        val f2 = fs.copy(objects = fs.objects + pipe + valve + ro)
        val r2 = ResultView(StoredSolve(solved, f2.modelHash(), 0), f2.modelHash())
        val c = PressurizationCase("z", "Equalise", source = PressurizationSources.VESSEL, sourceId = "v", sourceVessel = VesselGeometry(1.5, 4.0),
            pipeIds = listOf("p"), valve = PressurizationValves.FLOWSHEET, valveId = "fv", opening = 0.5)
        assertNull(Safety.pressurizationProblem(f2, r2, c))
        val s = Safety.pressurizationRequest(f2, r2, c).supply!!
        assertEquals(VesselGeometry(1.5, 4.0), s.vessel)
        assertEquals(8e5, s.pressure)
        assertEquals(50.0, s.cv)
        assertEquals(0.7, s.valveXt)
        assertEquals(0.5, s.opening) // a rated Cv takes the opening
        assertNull(s.heatTransferCoefficient) // wall heat by default
        assertEquals(0.0, Safety.pressurizationRequest(f2, r2, c.copy(sourceAdiabatic = true)).supply!!.heatTransferCoefficient)
        assertEquals(8.5e5, Safety.pressurizationRequest(f2, r2, c.copy(sourcePressure = 8.5e5)).supply!!.pressure)
        // An orifice plate gives its bore; a valve with no Cv (no rating, no solve) is refused.
        assertEquals(0.012, Safety.pressurizationRequest(f2, r2, c.copy(valveId = "ro")).supply!!.orificeDiameter)
        val bare = f2.copy(objects = f2.objects.map { if (it.id == "fv") it.copy(specs = emptyMap()) else it })
        val rb = ResultView(StoredSolve(solved, bare.modelHash(), 0), bare.modelHash())
        assertEquals("FV-100 has no Cv: give its rated Cv, or solve the flowsheet for the Cv it needs", Safety.pressurizationProblem(bare, rb, c))
        assertEquals("The source cannot be what it fills", Safety.pressurizationProblem(f2, r2, c.copy(sourceId = "p")))
        // A fixed volume needs a target to size an orifice to.
        assertEquals("Give the target pressure to size the orifice (or choose a valve or orifice)",
            Safety.pressurizationProblem(f2, r2, c.copy(valve = PressurizationValves.SIZE)))
        // The volume of a 1.5 m × 4 m vessel with 2:1 heads: π·1.5²/4·4 + π·1.5³/12.
        assertEquals(Math.PI * 1.5 * 1.5 / 4 * 4 + Math.PI * 1.5 * 1.5 * 1.5 / 12, Safety.volume(VesselGeometry(1.5, 4.0)), 1e-12)
    }
}
