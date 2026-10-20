/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.data

import app.flowstead.FakeEngineClient
import app.flowstead.FakeProjectRepository
import app.flowstead.FakeSecretStore
import app.flowstead.FakeSettingsRepository
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.SimObject
import app.flowstead.core.model.Spec
import app.flowstead.core.model.SpecKey
import app.flowstead.core.model.modelHash
import app.flowstead.data.engine.EngineResult
import app.flowstead.data.queue.QueuedOutcome
import app.flowstead.data.queue.QueuedSolver
import app.flowstead.data.settings.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueuedSolverTest {
    private val repo = FakeProjectRepository()
    private val settings = FakeSettingsRepository(AppSettings(engineUrl = "https://engine.example.com", engineMode = app.flowstead.data.settings.EngineMode.Remote))
    private val engine = FakeEngineClient()
    private val solver = QueuedSolver(repo, settings, FakeSecretStore(), engine) { 7L }

    private val feed = Flowsheet(
        propertyPackage = "Peng-Robinson (PR)",
        compounds = listOf("Methane"),
        objects = listOf(
            SimObject(
                "f", "FEED", ObjectType.MaterialStream,
                specs = mapOf(
                    SpecKey.TEMPERATURE to Spec(300.0, "C"),
                    SpecKey.PRESSURE to Spec(1e6, "bara"),
                    SpecKey.MOLAR_FLOW to Spec(1.0, "kgmol/h"),
                ),
                composition = mapOf("Methane" to 1.0),
            ),
        ),
    )

    @Test
    fun solvesTheFlowsheetAsItIsWhenItRuns() = runTest {
        val id = repo.create("Q", feed)
        assertEquals(QueuedOutcome.Solved, solver.run(id))
        val stored = repo.observeProject(id).first()!!.solve!!
        assertEquals(feed.modelHash(), stored.modelHash)
        assertEquals(7L, stored.solvedAtMillis)
    }

    @Test
    fun stillOfflineRetries() = runTest {
        val id = repo.create("Q", feed)
        engine.solveResult = EngineResult.Failed("Cannot reach the engine", unreachable = true)
        assertEquals(QueuedOutcome.RetryLater, solver.run(id))
        assertNull(repo.observeProject(id).first()!!.solve)
    }

    @Test
    fun nothingToDoIsDropped() = runTest {
        assertEquals(QueuedOutcome.Dropped, solver.run("gone"))
        val incomplete = repo.create("Q", Flowsheet(objects = listOf(SimObject("s", "1", ObjectType.MaterialStream))))
        assertEquals(QueuedOutcome.Dropped, solver.run(incomplete))
        engine.solveResult = EngineResult.Failed("The engine rejected the API key")
        assertEquals(QueuedOutcome.Dropped, solver.run(repo.create("Q", feed)))
        assertEquals(1, engine.solved.size) // only the complete flowsheet reached the engine
    }
}
