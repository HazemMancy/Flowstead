/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.data

import app.thermodock.FakeEngineClient
import app.thermodock.FakeProjectRepository
import app.thermodock.FakeSecretStore
import app.thermodock.FakeSettingsRepository
import app.thermodock.core.model.Flowsheet
import app.thermodock.core.model.ObjectType
import app.thermodock.core.model.SimObject
import app.thermodock.core.model.Spec
import app.thermodock.core.model.SpecKey
import app.thermodock.core.model.modelHash
import app.thermodock.data.engine.EngineResult
import app.thermodock.data.queue.QueuedOutcome
import app.thermodock.data.queue.QueuedSolver
import app.thermodock.data.settings.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueuedSolverTest {
    private val repo = FakeProjectRepository()
    private val settings = FakeSettingsRepository(AppSettings(engineUrl = "https://engine.example.com", engineMode = app.thermodock.data.settings.EngineMode.Remote))
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
