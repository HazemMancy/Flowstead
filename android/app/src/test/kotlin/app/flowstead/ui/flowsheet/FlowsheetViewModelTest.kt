/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.flowsheet

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.flowstead.FakeDocumentIo
import app.flowstead.FakeEngineClient
import app.flowstead.FakeProjectRepository
import app.flowstead.FakeSecretStore
import app.flowstead.FakeSettingsRepository
import app.flowstead.FakeSolveQueue
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.advanceTimeBy
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
    private val settings = FakeSettingsRepository()
    private val engine = FakeEngineClient()
    private val queue = FakeSolveQueue()
    private val documents = FakeDocumentIo()

    private suspend fun vm(fs: Flowsheet = Flowsheet(propertyPackage = "Peng-Robinson (PR)", compounds = listOf("Methane"))): FlowsheetViewModel {
        val id = repo.create("Letdown", fs)
        return FlowsheetViewModel(SavedStateHandle(mapOf("projectId" to id)), repo, settings, FakeSecretStore(), engine, queue, documents) { 42L }
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
        // Halfway, level with the valve's outlet nozzle (a little below its centre) so the line runs straight.
        val nozzle = app.flowstead.core.model.Symbols.portPosition(ObjectType.Valve, Ports.find(ObjectType.Valve, PortSide.Outlet, 0)!!, 0.0, 0.0).second
        assertEquals(120.0 to nozzle, stream.x to stream.y)
    }

    private fun completeFeed(): Flowsheet {
        val feed = app.flowstead.core.model.SimObject(
            "f", "FEED", ObjectType.MaterialStream,
            specs = mapOf(
                SpecKey.TEMPERATURE to Spec(300.0, "C"),
                SpecKey.PRESSURE to Spec(5e6, "bara"),
                SpecKey.MOLAR_FLOW to Spec(10.0, "kgmol/h"),
            ),
            composition = mapOf("Methane" to 1.0),
        )
        return Flowsheet(propertyPackage = "Peng-Robinson (PR)", compounds = listOf("Methane"), objects = listOf(feed))
    }

    @Test
    fun solveRefusesWhileThereAreLocalIssues() = runTest {
        val vm = vm()
        vm.ui()
        vm.add(ObjectType.MaterialStream, 0.0, 0.0)
        vm.ui { it.flowsheet.objects.size == 1 }
        vm.solve()
        assertTrue(vm.messages.first().startsWith("Fix 1 issue(s) first"))
        assertTrue(engine.solved.isEmpty())
    }

    @Test
    fun solveNeedsAnEngineAddress() = runTest {
        val vm = vm(completeFeed())
        vm.ui()
        vm.solve()
        assertEquals("Set the engine address in Settings to solve", vm.messages.first())
    }

    @Test
    fun solveStoresTheResultAndEditsMakeItStale() = runTest {
        settings.setEngineUrl("https://engine.example.com")
        engine.solveResult = app.flowstead.data.engine.EngineResult.Ok(
            app.flowstead.core.api.SolveResult(
                app.flowstead.core.api.SolveStatus.Converged,
                elapsedMs = 1200,
                objects = mapOf("f" to app.flowstead.core.api.ObjectResult(
                    app.flowstead.core.api.ObjectStatus.Solved, properties = mapOf("vapourFraction" to 1.0))),
            ),
        )
        val vm = vm(completeFeed())
        vm.ui()
        vm.solve()
        assertEquals("Converged in 1.2 s", vm.messages.first())
        val solved = vm.ui { it.results.status != null }
        assertFalse(solved.results.stale)
        assertEquals(app.flowstead.core.model.DisplayValue.Calculated(1.0, stale = false), solved.results.value("f", "vapourFraction"))
        assertEquals(completeFeed(), engine.solved.single())

        vm.setSpec("f", SpecKey.TEMPERATURE, Spec(310.0, "C"))
        assertTrue(vm.ui { it.results.stale }.results.stale)
    }

    @Test
    fun activeSolverSolvesAfterEveryChangeAndHoldDoesNot() = runTest {
        settings.setEngineUrl("https://engine.example.com")
        engine.solveResult = app.flowstead.data.engine.EngineResult.Ok(
            app.flowstead.core.api.SolveResult(app.flowstead.core.api.SolveStatus.Converged, elapsedMs = 100),
        )
        val vm = vm(completeFeed())
        vm.ui()
        // Hold (the default): a change waits for Solve.
        vm.setSpec("f", SpecKey.TEMPERATURE, Spec(310.0, "C"))
        vm.ui { it.flowsheet.objects.single().specs[SpecKey.TEMPERATURE]?.si == 310.0 }
        advanceTimeBy(5_000)
        assertTrue(engine.solved.isEmpty())

        // Active: switching solves at once, then every change solves once the user pauses.
        vm.setSolverActive(true)
        assertTrue(vm.ui { it.results.status != null && !it.results.stale }.flowsheet.solverActive)
        assertEquals(1, engine.solved.size)
        vm.setSpec("f", SpecKey.TEMPERATURE, Spec(320.0, "C"))
        vm.setSpec("f", SpecKey.TEMPERATURE, Spec(330.0, "C"))
        advanceTimeBy(5_000)
        vm.ui { !it.results.stale }
        assertEquals(2, engine.solved.size)
        assertEquals(330.0, engine.solved.last().objects.single().specs[SpecKey.TEMPERATURE]?.si)
        // The mode is not part of the model: switching back to Hold keeps the results current.
        vm.setSolverActive(false)
        assertFalse(vm.ui { !it.flowsheet.solverActive }.results.stale)
    }

    @Test
    fun phaseEnvelopeUsesTheSolvedCompositionAndGoesStaleOnEdits() = runTest {
        settings.setEngineUrl("https://engine.example.com")
        val solvedX = mapOf("Methane" to 1.0)
        engine.solveResult = app.flowstead.data.engine.EngineResult.Ok(
            app.flowstead.core.api.SolveResult(
                app.flowstead.core.api.SolveStatus.Converged,
                objects = mapOf("f" to app.flowstead.core.api.ObjectResult(
                    app.flowstead.core.api.ObjectStatus.Solved,
                    phases = mapOf("overall" to app.flowstead.core.api.PhaseResult(composition = solvedX)))),
            ),
        )
        engine.envelopeResult = app.flowstead.data.engine.EngineResult.Ok(
            app.flowstead.core.api.PhaseEnvelope(bubbleTemperature = listOf(100.0, 150.0), bubblePressure = listOf(1e5, 2e6)),
        )
        val vm = vm(completeFeed())
        vm.ui()
        vm.solve()
        vm.ui { it.results.status != null }
        vm.phaseEnvelope("f")
        val traced = vm.ui { it.envelope?.envelope != null }.envelope!!
        assertEquals(solvedX, engine.envelopes.single().composition)
        assertFalse(traced.stale)
        vm.setSpec("f", SpecKey.TEMPERATURE, Spec(310.0, "C"))
        assertTrue(vm.ui { it.envelope?.stale == true }.envelope!!.stale)
    }

    @Test
    fun aReliefValveIsSizedFromTheSolvedVesselAndKeepsItsResult() = runTest {
        settings.setEngineUrl("https://engine.example.com")
        val sep = app.flowstead.core.model.SimObject("v", "V-100", ObjectType.Separator)
        // The separator needs its vapour and liquid outlets, or the flowsheet is not solved.
        val gas = app.flowstead.core.model.SimObject("g", "GAS", ObjectType.MaterialStream)
        val liquid = app.flowstead.core.model.SimObject("l", "LIQUID", ObjectType.MaterialStream)
        val fs = completeFeed().let {
            it.copy(objects = it.objects + sep + gas + liquid, connections = listOf(Connection("f", 0, "v", 0), Connection("v", 0, "g", 0), Connection("v", 1, "l", 0)))
        }
        engine.solveResult = app.flowstead.data.engine.EngineResult.Ok(
            app.flowstead.core.api.SolveResult(
                app.flowstead.core.api.SolveStatus.Converged,
                objects = mapOf("f" to app.flowstead.core.api.ObjectResult(
                    app.flowstead.core.api.ObjectStatus.Solved, properties = mapOf("temperature" to 300.0, "pressure" to 5e6, "massFlow" to 1.0),
                    phases = mapOf("overall" to app.flowstead.core.api.PhaseResult(composition = mapOf("Methane" to 1.0))))),
            ),
        )
        engine.reliefResult = app.flowstead.data.engine.EngineResult.Ok(app.flowstead.core.api.ReliefResult(orifice = "K", valves = 1))
        val vm = vm(fs)
        val messages = mutableListOf<String>()
        backgroundScope.launch { vm.messages.collect { messages += it } }
        vm.ui()
        vm.solve()
        withTimeoutOrNull(60_000) { vm.ui { it.results.status != null } } ?: error("Not solved; messages: $messages")
        vm.upsertReliefDevice(app.flowstead.core.model.ReliefDeviceDef(
            "d", "PSV-100", protectedId = "v", setPressure = 6e6,
            scenarios = listOf(app.flowstead.core.api.ReliefScenario(app.flowstead.core.api.ReliefScenarioKinds.BLOCKED_OUTLET)),
        ))
        vm.ui { it.flowsheet.safety != null }
        vm.runRelief("d")
        // Fails with the view model's messages (a problem or the engine's reason) rather than hanging.
        val sized = withTimeoutOrNull(60_000) { vm.ui { it.flowsheet.safety?.devices?.single()?.result != null } }
            ?: error("Not sized; messages: $messages")
        assertEquals("K", sized.flowsheet.safety!!.devices.single().result!!.orifice)
        // The engine got the feed's fluid and flow for the blocked outlet.
        val sent = engine.reliefs.single().device
        assertEquals(mapOf("Methane" to 1.0), sent.composition)
        assertEquals(1.0, sent.scenarios.single().massFlow)
        // The safety analysis is not part of the model: the results stay current.
        assertFalse(sized.results.stale)
    }

    @Test
    fun unreachableEngineQueuesTheSolve() = runTest {
        settings.setEngineUrl("https://engine.example.com")
        engine.solveResult = app.flowstead.data.engine.EngineResult.Failed("Cannot reach the engine: timeout", unreachable = true)
        val vm = vm(completeFeed())
        vm.ui()
        vm.solve()
        assertEquals("Engine unreachable. Queued: it will solve when the phone is back online", vm.messages.first())
        assertEquals(SolveState.Queued, vm.ui { it.solveState == SolveState.Queued }.solveState)
        vm.cancelQueuedSolve()
        assertEquals(SolveState.Idle, vm.ui { it.solveState == SolveState.Idle }.solveState)
    }

    @Test
    fun engineThatAnswersNoIsNotQueued() = runTest {
        settings.setEngineUrl("https://engine.example.com")
        engine.solveResult = app.flowstead.data.engine.EngineResult.Failed("The engine rejected the API key")
        val vm = vm(completeFeed())
        vm.ui()
        vm.solve()
        assertEquals("Not solved: The engine rejected the API key", vm.messages.first())
        assertTrue(queue.queued.value.isEmpty())
    }

    @Test
    fun resultsExportNeedsASolveThenWritesCsvAndXlsx() = runTest {
        val vm = vm(completeFeed())
        vm.ui()
        val csv = android.net.Uri.parse("content://test/results.csv")
        vm.exportResults(csv, ResultsFormat.Csv)
        assertEquals("Solve first: there are no results to export", vm.messages.first())

        settings.setEngineUrl("https://engine.example.com")
        engine.solveResult = app.flowstead.data.engine.EngineResult.Ok(
            app.flowstead.core.api.SolveResult(
                app.flowstead.core.api.SolveStatus.Converged,
                objects = mapOf("f" to app.flowstead.core.api.ObjectResult(
                    app.flowstead.core.api.ObjectStatus.Solved, properties = mapOf("temperature" to 300.0))),
            ),
        )
        vm.solve()
        vm.messages.first()
        vm.ui { it.results.status != null }
        vm.exportResults(csv, ResultsFormat.Csv)
        assertEquals("Results exported", vm.messages.first())
        val text = documents.written.getValue(csv).toString(Charsets.UTF_8)
        assertTrue(text.startsWith("Streams\r\nProject,Letdown\r\n"), text)
        assertTrue(text.contains("Property,FEED"), text)

        val xlsx = android.net.Uri.parse("content://test/results.xlsx")
        vm.exportResults(xlsx, ResultsFormat.Xlsx)
        assertEquals("Results exported", vm.messages.first())
        assertEquals(0x50, documents.written.getValue(xlsx)[0].toInt()) // zip
        assertEquals("Letdown results.xlsx", vm.exportFileName("xlsx", " results"))
    }

    @Test
    fun dwsimExportWritesTheEngineFile() = runTest {
        settings.setEngineUrl("https://engine.example.com")
        val vm = vm(completeFeed())
        vm.ui()
        val uri = android.net.Uri.parse("content://test/plant.dwxmz")
        vm.exportDwsim(uri)
        assertEquals("Exported DWSIM file", vm.messages.first())
        assertEquals(listOf<Byte>(0x50, 0x4B, 3, 4), documents.written.getValue(uri).toList())
    }

    @Test
    fun portsCanBeWiredFromTheEditor() = runTest {
        val vm = vm()
        vm.ui()
        vm.add(ObjectType.Valve, 240.0, 0.0)
        val valve = vm.ui { it.flowsheet.objects.size == 1 }.flowsheet.objects.single()
        val outlet = Ports.find(ObjectType.Valve, PortSide.Outlet, 0)!!
        vm.connectPort(valve.id, outlet, null)
        val fs = vm.ui { it.flowsheet.objects.size == 2 }.flowsheet
        val stream = fs.objects.single { it.type == ObjectType.MaterialStream }
        assertEquals(listOf(Connection(valve.id, 0, stream.id, 0)), fs.connections)
        assertEquals(360.0 to app.flowstead.core.model.Symbols.portPosition(ObjectType.Valve, outlet, 240.0, 0.0).second, stream.x to stream.y)

        vm.disconnectPort(valve.id, outlet)
        assertTrue(vm.ui { it.flowsheet.connections.isEmpty() }.flowsheet.objects.size == 2)
        vm.connectPort(valve.id, Ports.find(ObjectType.Valve, PortSide.Inlet, 0)!!, stream.id)
        assertEquals(listOf(Connection(stream.id, 0, valve.id, 0)), vm.ui { it.flowsheet.connections.isNotEmpty() }.flowsheet.connections)
    }
}
