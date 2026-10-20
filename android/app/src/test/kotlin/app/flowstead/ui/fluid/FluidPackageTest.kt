/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.fluid

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.flowstead.FakeEngineClient
import app.flowstead.FakeProjectRepository
import app.flowstead.FakeSecretStore
import app.flowstead.FakeSettingsRepository
import app.flowstead.FileCompoundLibrary
import app.flowstead.MainDispatcherRule
import app.flowstead.core.compounds.CompoundCatalogue
import app.flowstead.core.model.Flowsheet
import app.flowstead.ui.theme.FlowsteadTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class FluidPackageTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()

    private val repo = FakeProjectRepository()

    private suspend fun vm(): FluidPackageViewModel {
        val id = repo.create("Gas", Flowsheet(propertyPackage = "Peng-Robinson (PR)"))
        return FluidPackageViewModel(SavedStateHandle(mapOf("projectId" to id)), repo, FakeSettingsRepository(), FakeSecretStore(), FakeEngineClient(), FileCompoundLibrary())
    }

    @Test
    fun searchesTheWholeLibraryOfflineAndAdds() = runTest {
        val vm = vm()
        val start = vm.state.first { it.libraryLoaded && it.loaded }
        assertTrue(start.librarySize > 1400)
        assertEquals(CompoundCatalogue.COMMON, start.family)
        assertTrue(start.results.all { it.common })

        vm.setQuery("toluene")
        // Search is debounced: wait for the results of this query, not the previous list.
        val found = vm.state.first { it.query == "toluene" && it.results.firstOrNull()?.name == "Toluene" }
        assertEquals("Toluene", found.results.first().name)
        assertEquals(null, found.family, "typing leaves the Common filter")

        vm.setFamily("Aromatics")
        vm.setQuery("")
        // The debounced search settles on the full aromatic family (100+ compounds).
        val aromatics = vm.state.first { s -> s.family == "Aromatics" && s.query.isEmpty() && s.results.size > 20 && s.results.all { it.family == "Aromatics" } }
        assertTrue(aromatics.results.any { it.name == "Toluene" })

        vm.add("Toluene")
        assertEquals(listOf("Toluene"), vm.state.first { it.compounds.isNotEmpty() }.compounds)
    }

    @Test
    fun detailSheetShowsDatabaseConstantsInTheUnitSet() = runTest {
        val catalogue = FileCompoundLibrary().catalogue()
        val ui = FluidUi(loaded = true, libraryLoaded = true, librarySize = catalogue.all.size, family = null,
            families = catalogue.families, results = listOf(catalogue["Methane"]!!))
        var added: String? = null
        compose.setContent { FlowsteadTheme { FluidPackageContent(ui, onAdd = { added = it }) } }
        compose.onNodeWithTag("fluid_list").performScrollToNode(hasTestTag("compound_Methane"))
        compose.onNodeWithContentDescription("Details of Methane").performClick()
        compose.onNodeWithTag("compound_sheet").assertIsDisplayed()
        compose.onNodeWithText("74-82-8").assertExists()
        compose.onNodeWithText("-82.59 °C").assertExists() // Tc 190.56 K in the SI set (°C); may be below the fold
        compose.onNodeWithTag("add_from_sheet").performScrollTo().performClick()
        assertEquals("Methane", added)
    }
}
