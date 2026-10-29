/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.SimObject
import app.flowstead.core.model.legacyModelHash
import app.flowstead.core.model.modelHash
import app.flowstead.data.db.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class RoomProjectRepositoryTest {
    private var now = 1_000L
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val repo = RoomProjectRepository(db.projectDao()) { now++ }

    @After fun close() = db.close()

    @Test
    fun createListsNewestFirstWithObjectCount() = runTest {
        repo.create("First")
        val fs = Flowsheet(objects = listOf(SimObject("o1", "FEED", ObjectType.MaterialStream)))
        val second = repo.create("Second", fs)
        val list = repo.observeProjects().first()
        assertEquals(listOf("Second", "First"), list.map { it.name })
        assertEquals(1, list.first { it.id == second }.objectCount)
        assertFalse(list.first().everSolved)
    }

    @Test
    fun flowsheetRoundTripsThroughStorage() = runTest {
        val fs = Flowsheet(
            propertyPackage = "Peng-Robinson (PR)",
            compounds = listOf("Methane"),
            objects = listOf(SimObject("o1", "V-100", ObjectType.Separator, 1.0, 2.0)),
        )
        val id = repo.create("Sep", fs)
        val project = assertNotNull(repo.observeProject(id).first())
        assertEquals(fs, project.flowsheet)
        assertFalse(project.resultsCurrent)
    }

    @Test
    fun resultsSolvedBefore188StayCurrentAfterTheFirstMove() = runTest {
        // Solved by 1.8.7: the result carries the old hash, which counted positions.
        val fs = Flowsheet(
            propertyPackage = "Peng-Robinson (PR)", compounds = listOf("Methane"),
            objects = listOf(SimObject("o1", "V-100", ObjectType.Separator, 1.0, 2.0)),
        )
        val id = repo.create("Sep", fs)
        val old = app.flowstead.core.model.StoredSolve(
            app.flowstead.core.api.SolveResult(app.flowstead.core.api.SolveStatus.Converged), fs.legacyModelHash(), 1L,
        )
        repo.saveSolve(id, old)
        assertTrue(assertNotNull(repo.observeProject(id).first()).resultsCurrent)
        // The new stamp was saved when the project opened, so moving a symbol leaves the results current.
        val moved = fs.copy(objects = fs.objects.map { it.copy(x = 300.0, y = 84.0) })
        repo.saveFlowsheet(id, moved)
        val after = assertNotNull(repo.observeProject(id).first())
        assertEquals(moved, after.flowsheet)
        assertTrue(after.resultsCurrent)
    }

    @Test
    fun duplicateCopiesFlowsheetButNotResults() = runTest {
        val fs = Flowsheet(compounds = listOf("Water"))
        val id = repo.create("Steam heater", fs)
        val copy = assertNotNull(repo.duplicate(id))
        val p = assertNotNull(repo.observeProject(copy).first())
        assertEquals("Steam heater (copy)", p.name)
        assertEquals(fs, p.flowsheet)
        assertNull(repo.duplicate("missing"))
    }

    @Test
    fun renameSaveAndDelete() = runTest {
        val id = repo.create("Old")
        repo.rename(id, "  New\tname  ")
        assertEquals("New name", repo.observeProject(id).first()?.name)
        repo.saveFlowsheet(id, Flowsheet(objects = listOf(SimObject("o1", "K-100", ObjectType.Compressor))))
        assertEquals(1, repo.observeProjects().first().single().objectCount)
        repo.delete(id)
        assertNull(repo.observeProject(id).first())
    }

    @Test
    fun storedSolveIsCurrentUntilTheFlowsheetChanges() = runTest {
        val fs = Flowsheet(compounds = listOf("Methane"))
        val id = repo.create("Flash", fs)
        val solve = app.flowstead.core.model.StoredSolve(
            app.flowstead.core.api.SolveResult(app.flowstead.core.api.SolveStatus.Converged), fs.modelHash(), 7L,
        )
        repo.saveSolve(id, solve)
        val p = assertNotNull(repo.observeProject(id).first())
        assertEquals(solve, p.solve)
        assertTrue(p.resultsCurrent)
        assertTrue(repo.observeProjects().first().single().everSolved)

        repo.saveFlowsheet(id, fs.copy(compounds = listOf("Methane", "Ethane")))
        assertFalse(assertNotNull(repo.observeProject(id).first()).resultsCurrent)
        // A duplicate starts unsolved.
        assertNull(assertNotNull(repo.observeProject(assertNotNull(repo.duplicate(id))).first()).solve)
    }

    @Test
    fun cleanNameLimitsLength() {
        assertEquals(RoomProjectRepository.MAX_NAME, RoomProjectRepository.cleanName("x".repeat(200)).length)
        assertEquals("Untitled project", RoomProjectRepository.cleanName(" \n "))
    }
}
