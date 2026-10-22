/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.settings

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.flowstead.BuildConfig
import app.flowstead.core.update.UpdateCheck
import app.flowstead.core.units.EngUnit
import app.flowstead.core.units.Quantity
import app.flowstead.core.units.UnitSet
import app.flowstead.core.units.Units
import app.flowstead.data.engine.EngineClient
import app.flowstead.data.engine.EngineResult
import app.flowstead.data.settings.AppSettings
import app.flowstead.data.settings.EngineUrl
import app.flowstead.data.settings.SecretStore
import app.flowstead.data.settings.EngineMode
import app.flowstead.data.settings.SettingsRepository
import app.flowstead.data.update.AppUpdater
import app.flowstead.data.update.InstallState
import app.flowstead.data.update.UpdateChecker
import app.flowstead.ui.theme.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Testing : ConnectionState
    data class Connected(val summary: String) : ConnectionState
    data class Failed(val reason: String) : ConnectionState
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Done(val result: UpdateCheck) : UpdateState
    data class Failed(val reason: String) : UpdateState
}

data class SettingsUiState(
    val loaded: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val hasApiKey: Boolean = false,
    val urlError: String? = null,
    val patmError: String? = null,
    val connection: ConnectionState = ConnectionState.Idle,
    val update: UpdateState = UpdateState.Idle,
    val install: InstallState = InstallState.Idle,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val secrets: SecretStore,
    private val engine: EngineClient,
    private val updater: AppUpdater,
    private val updates: UpdateChecker,
) : ViewModel() {

    private val transient = MutableStateFlow(SettingsUiState())

    val state: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        secrets.hasApiKey,
        transient,
        updater.state,
    ) { settings, hasKey, t, install ->
        t.copy(loaded = true, settings = settings, hasApiKey = hasKey, install = install)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    /** Returns true when the URL was valid and saved. */
    fun saveEngineUrl(input: String): Boolean = when (val r = EngineUrl.normalise(input)) {
        is EngineUrl.Result.Invalid -> {
            transient.update { it.copy(urlError = r.reason) }
            false
        }
        is EngineUrl.Result.Ok -> {
            transient.update { it.copy(urlError = null, connection = ConnectionState.Idle) }
            viewModelScope.launch { settingsRepository.setEngineUrl(r.url) }
            true
        }
    }

    fun saveApiKey(key: String) {
        if (key.isBlank()) return
        viewModelScope.launch {
            secrets.setApiKey(key)
            transient.update { it.copy(connection = ConnectionState.Idle) }
        }
    }

    fun clearApiKey() = viewModelScope.launch { secrets.clearApiKey() }

    fun setEngineMode(mode: EngineMode) = viewModelScope.launch {
        transient.update { it.copy(connection = ConnectionState.Idle) }
        settingsRepository.setEngineMode(mode)
    }

    fun testConnection() = viewModelScope.launch {
        val url = settingsRepository.settings.first().engineTarget
        if (url.isBlank()) {
            transient.update { it.copy(connection = ConnectionState.Failed("Set the engine address first")) }
            return@launch
        }
        transient.update { it.copy(connection = ConnectionState.Testing) }
        val result = engine.health(url, secrets.apiKey())
        transient.update {
            it.copy(
                connection = when (result) {
                    is EngineResult.Ok -> ConnectionState.Connected(
                        "Engine ${result.value.engineVersion} · DWSIM ${result.value.dwsimVersion}",
                    )
                    is EngineResult.Failed -> ConnectionState.Failed(result.reason)
                },
            )
        }
    }

    fun checkForUpdates() = viewModelScope.launch {
        transient.update { it.copy(update = UpdateState.Checking) }
        val r = updates.check(BuildConfig.VERSION_NAME)
        transient.update {
            it.copy(update = r.fold({ c -> UpdateState.Done(c) }, { e -> UpdateState.Failed(e.message ?: "No connection") }))
        }
    }

    /** Downloads the release in the background, verifies it and opens Android's install prompt. */
    fun installUpdate(update: UpdateCheck.Available) = updater.start(update)

    fun retryInstall() = updater.install()

    fun installPermissionSettings(): Intent = updater.permissionSettings()

    fun selectPreset(preset: UnitSet) = viewModelScope.launch { settingsRepository.setUnitSet(preset) }

    fun setUnit(quantity: Quantity, unit: EngUnit) = viewModelScope.launch {
        val current = settingsRepository.settings.first().unitSet
        settingsRepository.setUnitSet(current.with(quantity, unit))
    }

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { settingsRepository.setThemeMode(mode) }

    /** [input] is in kPa (absolute). Typical sites sit between ~50 kPa (high altitude) and 105 kPa. */
    fun setAtmosphericPressureKPa(input: String): Boolean {
        val kpa = input.trim().replace(',', '.').toDoubleOrNull()
        if (kpa == null || kpa !in 30.0..110.0) {
            transient.update { it.copy(patmError = "Enter a value between 30 and 110 kPa") }
            return false
        }
        transient.update { it.copy(patmError = null) }
        viewModelScope.launch { settingsRepository.setDefaultAtmosphericPressure(kpa * 1e3) }
        return true
    }

    companion object {
        /** Quantities a user can pick units for, in the order shown. */
        val EDITABLE_QUANTITIES = listOf(
            Quantity.Temperature,
            Quantity.Pressure,
            Quantity.PressureDifference,
            Quantity.MolarFlow,
            Quantity.MassFlow,
            Quantity.VolumeFlow,
            Quantity.Power,
            Quantity.MassEnthalpy,
            Quantity.Density,
            Quantity.Viscosity,
        )

        fun unitsFor(q: Quantity): List<EngUnit> = Units.forQuantity(q)
    }
}
