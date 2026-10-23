/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.api.ObjectResult
import app.flowstead.core.api.ObjectStatus
import app.flowstead.core.api.PhaseResult
import app.flowstead.core.api.ReliefResult
import app.flowstead.core.api.ReliefScenario
import app.flowstead.core.api.ReliefScenarioKinds
import app.flowstead.core.api.SolveResult
import app.flowstead.core.api.SolveStatus
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
}
