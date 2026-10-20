/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.flowsheet

import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.flowstead.FakeProjectRepository
import app.flowstead.FakeSettingsRepository
import app.flowstead.MainDispatcherRule
import app.flowstead.core.model.Connection
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.PortSide
import app.flowstead.core.model.Ports
import app.flowstead.core.model.Spec
import app.flowstead.core.model.SpecKey
import app.flowstead.core.model.SpecStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class FlowsheetViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val repo = FakeProjectRepository()

    private suspend fun vm(): FlowsheetViewModel {
        val id = repo.create("Letdown", Flowsheet(propertyPackage = "Peng-Robinson (PR)", compounds = listOf("Methane")))
        return FlowsheetViewModel(SavedStateHandle(mapOf("projectId" to id)), repo, FakeSettingsRepository())
    }

    private suspend fun FlowsheetViewModel.ui(predicate: (FlowsheetUi) -> Boolean = { true }): FlowsheetUi =
        state.first { it is FlowsheetUiState.Ready && predicate(it.ui) }.let { (it as FlowsheetUiState.Ready).ui }

    @Test
    fun addWireAndSpecifyAValve() = runTest {
        val vm = vm()
        vm.ui()
        vm.add(ObjectType.Valve, 0.0, 0.0)
        val valve = vm.ui { it.flowsheet.objects.size == 1 }.flowsheet.objects.single()
        assertEquals("VLV-100", valve.tag)

        // Drag from the outlet port to empty canvas creates the product stream.
        vm.wire(valve.id, Ports.find(ObjectType.Valve, PortSide.Outlet, 0)!!, Hit.Empty, 120.0, 0.0)
        val withProduct = vm.ui { it.flowsheet.objects.size == 2 }
        val product = withProduct.flowsheet.objects.first { it.type == ObjectType.MaterialStream }
        assertEquals(listOf(Connection(valve.id, 0, product.id, 0)), withProduct.flowsheet.connections)

        vm.setExclusiveSpec(valve.id, SpecKey.OUTLET_PRESSURE, Spec(2e6, "bara"), listOf(SpecKey.OUTLET_PRESSURE, SpecKey.PRESSURE_DROP))
        val status = vm.ui { SpecKey.OUTLET_PRESSURE in it.flowsheet.objects.first { o -> o.id == valve.id }.specs }.status[valve.id]
        // Still needs its inlet stream.
        assertEquals(listOf("inlet stream"), assertIs<SpecStatus.UnderSpecified>(status).needs)
    }

    @Test
    fun rejectedEditsBecomeMessagesNotCrashes() = runTest {
        val vm = vm()
        vm.ui()
        vm.add(ObjectType.MaterialStream, 0.0, 0.0)
        vm.add(ObjectType.MaterialStream, 48.0, 0.0)
        val objs = vm.ui { it.flowsheet.objects.size == 2 }.flowsheet.objects
        vm.rename(objs[1].id, objs[0].tag)
        assertEquals("Tag 1 is already used", vm.messages.first())
    }

    @Test
    fun undoRestoresThePreviousFlowsheet() = runTest {
        val vm = vm()
        assertFalse(vm.ui().canUndo)
        vm.add(ObjectType.Separator, 0.0, 0.0)
        val sep = vm.ui { it.flowsheet.objects.size == 1 }.flowsheet.objects.single()
        vm.move(sep.id, 240.0, 96.0)
        vm.ui { it.flowsheet.objects.single().x == 240.0 }
        vm.undo()
        assertEquals(0.0, vm.ui { it.flowsheet.objects.single().x == 0.0 }.flowsheet.objects.single().x)
        vm.undo()
        assertTrue(vm.ui { it.flowsheet.objects.isEmpty() }.flowsheet.objects.isEmpty())
    }

    @Test
    fun wireDroppedOnAnotherUnitInsertsAStream() = runTest {
        val vm = vm()
        vm.ui()
        vm.add(ObjectType.Valve, 0.0, 0.0)
        vm.add(ObjectType.Separator, 240.0, 0.0)
        val (valve, sep) = vm.ui { it.flowsheet.objects.size == 2 }.flowsheet.objects
        vm.wire(valve.id, Ports.find(ObjectType.Valve, PortSide.Outlet, 0)!!, Hit.OnObject(sep.id), 240.0, 0.0)
        val fs = vm.ui { it.flowsheet.objects.size == 3 }.flowsheet
        val stream = fs.objects.single { it.type == ObjectType.MaterialStream }
        assertEquals(setOf(Connection(valve.id, 0, stream.id, 0), Connection(stream.id, 0, sep.id, 0)), fs.connections.toSet())
        assertEquals(Offset(120f, 0f), Offset(stream.x.toFloat(), stream.y.toFloat()))
    }
}
