/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.projects

import app.flowstead.FakeProjectRepository
import app.flowstead.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val repo = FakeProjectRepository()
    // Lazy: built after MainDispatcherRule has installed the test Main dispatcher.
    private val vm by lazy { ProjectsViewModel(repo) }

    private suspend fun names() = vm.state.first { !it.loading }.projects.map { it.name }

    @Test
    fun initialStateIsLoading() {
        assertTrue(vm.state.value.loading)
    }

    @Test
    fun loadsAnEmptyList() = runTest {
        assertEquals(emptyList(), names())
    }

    @Test
    fun createOpensTheNewProjectAndListsIt() = runTest {
        vm.create("  HP   separator check ")
        assertEquals(ProjectsEffect.OpenProject("p1"), vm.effects.first())
        assertEquals(listOf("HP separator check"), names())
    }

    @Test
    fun blankNameFallsBackToDefault() = runTest {
        vm.create("   ")
        assertEquals(listOf("Untitled project"), names())
    }

    @Test
    fun duplicateRenameDelete() = runTest {
        val id = repo.create("Compressor train")
        vm.duplicate(id)
        assertEquals(setOf("Compressor train", "Compressor train (copy)"), names().toSet())
        vm.rename(id, "K-100 train")
        assertTrue("K-100 train" in names())
        vm.delete(id)
        assertEquals(listOf("Compressor train (copy)"), names())
    }

    @Test
    fun duplicatingAMissingProjectReportsIt() = runTest {
        vm.duplicate("nope")
        assertEquals(ProjectsEffect.Message("That project no longer exists"), vm.effects.first())
    }
}
