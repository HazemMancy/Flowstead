/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.settings

import app.flowstead.FakeEngineClient
import app.flowstead.FakeSecretStore
import app.flowstead.FakeSettingsRepository
import app.flowstead.MainDispatcherRule
import app.flowstead.core.update.UpdateCheck
import app.flowstead.core.units.Quantity
import app.flowstead.core.units.UnitSet
import app.flowstead.core.units.Units
import app.flowstead.data.engine.EngineResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val settings = FakeSettingsRepository()
    private val secrets = FakeSecretStore()
    private val engine = FakeEngineClient()
    // Lazy: built after MainDispatcherRule has installed the test Main dispatcher.
    private var latest: Result<UpdateCheck> = Result.success(UpdateCheck.UpToDate("1.0.0"))
    private val vm by lazy { SettingsViewModel(settings, secrets, engine) { latest } }

    private suspend fun loaded() = vm.state.first { it.loaded }

    @Test
    fun rejectsPlainHttpAndKeepsOldUrl() = runTest {
        assertFalse(vm.saveEngineUrl("http://192.168.1.10:8080"))
        assertEquals("The engine must use HTTPS", loaded().urlError)
        assertEquals("", settings.state.value.engineUrl)
    }

    @Test
    fun bareHostGetsHttpsAndTrailingSlashIsDropped() = runTest {
        assertTrue(vm.saveEngineUrl(" engine.example.com:8443/ "))
        assertEquals("https://engine.example.com:8443", settings.state.value.engineUrl)
        assertNull(loaded().urlError)
    }

    @Test
    fun testConnectionNeedsAnAddress() = runTest {
        vm.testConnection().join()
        assertEquals(ConnectionState.Failed("Set the engine address first"), loaded().connection)
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun testConnectionSendsStoredKeyAndReportsVersions() = runTest {
        vm.saveEngineUrl("https://engine.example.com")
        vm.saveApiKey("  secret-key ")
        assertTrue(loaded().hasApiKey)
        vm.testConnection().join()
        assertEquals(ConnectionState.Connected("Engine 0.1.0 · DWSIM 9.0.5"), loaded().connection)
        assertEquals("https://engine.example.com" to "secret-key", engine.calls.single())
    }

    @Test
    fun failedConnectionShowsReason() = runTest {
        engine.result = EngineResult.Failed("The engine rejected the API key")
        vm.saveEngineUrl("https://engine.example.com")
        vm.testConnection().join()
        assertEquals(ConnectionState.Failed("The engine rejected the API key"), loaded().connection)
    }

    @Test
    fun choosingAUnitMakesTheSetCustom() = runTest {
        vm.setUnit(Quantity.Pressure, Units.barg).join()
        val set = settings.state.value.unitSet
        assertEquals(UnitSet.CUSTOM, set.name)
        assertEquals(Units.barg, set.unitFor(Quantity.Pressure))
        assertEquals(Units.C, set.unitFor(Quantity.Temperature))
        vm.selectPreset(UnitSet.FIELD).join()
        assertEquals(UnitSet.FIELD, settings.state.value.unitSet)
    }

    @Test
    fun atmosphericReferenceIsValidated() = runTest {
        assertFalse(vm.setAtmosphericPressureKPa("abc"))
        assertFalse(vm.setAtmosphericPressureKPa("500"))
        assertEquals("Enter a value between 30 and 110 kPa", loaded().patmError)
        assertTrue(vm.setAtmosphericPressureKPa("84,5"))
        assertEquals(84_500.0, settings.state.value.defaultAtmosphericPressurePa, 1e-9)
        assertNull(loaded().patmError)
    }

    @Test
    fun removingTheKey() = runTest {
        vm.saveApiKey("k")
        vm.clearApiKey().join()
        assertNull(secrets.apiKey())
        assertFalse(loaded().hasApiKey)
    }

    @Test
    fun checkForUpdatesReportsANewerRelease() = runTest {
        latest = Result.success(UpdateCheck.Available("1.1.0", "https://example/page", "https://example/f.apk"))
        vm.checkForUpdates()
        val update = vm.state.first { it.update is UpdateState.Done }.update as UpdateState.Done
        assertEquals("1.1.0", (update.result as UpdateCheck.Available).version)
    }

    @Test
    fun checkForUpdatesSaysWhyItFailed() = runTest {
        latest = Result.failure(IllegalStateException("No connection"))
        vm.checkForUpdates()
        assertEquals(UpdateState.Failed("No connection"), vm.state.first { it.update is UpdateState.Failed }.update)
    }
}
