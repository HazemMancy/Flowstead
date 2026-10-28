/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.ui.fluid

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
import app.thermodock.FakeEngineClient
import app.thermodock.FakeProjectRepository
import app.thermodock.FakeSecretStore
import app.thermodock.FakeSettingsRepository
import app.thermodock.FileCompoundLibrary
import app.thermodock.MainDispatcherRule
import app.thermodock.core.compounds.CompoundCatalogue
import app.thermodock.core.model.Flowsheet
import app.thermodock.core.model.LiquidDensityMethods
import app.thermodock.core.model.VapourViscosityMethods
import app.thermodock.core.model.VolumeShiftMethods
import app.thermodock.ui.theme.ThermoDockTheme
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
        compose.setContent { ThermoDockTheme { FluidPackageContent(ui, onAdd = { added = it }) } }
        compose.onNodeWithTag("fluid_list").performScrollToNode(hasTestTag("compound_Methane"))
        compose.onNodeWithContentDescription("Details of Methane").performClick()
        compose.onNodeWithTag("compound_sheet").assertIsDisplayed()
        compose.onNodeWithText("74-82-8").assertExists()
        compose.onNodeWithText("-82.59 °C").assertExists() // Tc 190.56 K in the SI set (°C); may be below the fold
        compose.onNodeWithTag("add_from_sheet").performScrollTo().performClick()
        assertEquals("Methane", added)
    }

    @Test
    fun liquidDensityDefaultsToDwsimAndSwitchesToCostald() = runTest {
        val vm = vm()
        assertEquals(null, vm.state.first { it.loaded }.liquidDensity)
        vm.setLiquidDensity(LiquidDensityMethods.COSTALD)
        assertEquals(LiquidDensityMethods.COSTALD, vm.state.first { it.liquidDensity != null }.liquidDensity)
        vm.setLiquidDensity(null)
        assertEquals(null, vm.state.first { it.liquidDensity == null }.liquidDensity)

        var picked: String? = "unset"
        compose.setContent { ThermoDockTheme { FluidPackageContent(FluidUi(loaded = true), onLiquidDensity = { picked = it }) } }
        compose.onNodeWithTag("fluid_list").performScrollToNode(hasTestTag("liquid_density_costald"))
        compose.onNodeWithTag("liquid_density_costald").performClick()
        assertEquals(LiquidDensityMethods.COSTALD, picked)
    }

    @Test
    fun volumeShiftDefaultsToPenelouxAndSwitchesOff() = runTest {
        val vm = vm()
        assertEquals(null, vm.state.first { it.loaded }.volumeShift)
        vm.setVolumeShift(VolumeShiftMethods.NONE)
        assertEquals(VolumeShiftMethods.NONE, vm.state.first { it.volumeShift != null }.volumeShift)

        var picked: String? = "unset"
        compose.setContent { ThermoDockTheme { FluidPackageContent(FluidUi(loaded = true), onVolumeShift = { picked = it }) } }
        compose.onNodeWithTag("fluid_list").performScrollToNode(hasTestTag("volume_shift_none"))
        compose.onNodeWithTag("volume_shift_none").performClick()
        assertEquals(VolumeShiftMethods.NONE, picked)
    }

    @Test
    fun vapourViscosityDefaultsToDwsimAndSwitchesToLge() = runTest {
        val vm = vm()
        assertEquals(null, vm.state.first { it.loaded }.vapourViscosity)
        vm.setVapourViscosity(VapourViscosityMethods.LGE)
        assertEquals(VapourViscosityMethods.LGE, vm.state.first { it.vapourViscosity != null }.vapourViscosity)

        var picked: String? = "unset"
        compose.setContent { ThermoDockTheme { FluidPackageContent(FluidUi(loaded = true), onVapourViscosity = { picked = it }) } }
        compose.onNodeWithTag("fluid_list").performScrollToNode(hasTestTag("vapour_viscosity_lge"))
        compose.onNodeWithTag("vapour_viscosity_lge").performClick()
        assertEquals(VapourViscosityMethods.LGE, picked)
    }
}
