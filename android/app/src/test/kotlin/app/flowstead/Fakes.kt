/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead

import app.flowstead.core.api.CompoundInfo
import app.flowstead.core.api.EngineHealth
import app.flowstead.core.api.PropertyPackageInfo
import app.flowstead.core.api.SolveResult
import app.flowstead.core.api.SolveStatus
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.StoredSolve
import app.flowstead.core.units.UnitSet
import app.flowstead.data.Project
import app.flowstead.data.ProjectRepository
import app.flowstead.data.ProjectSummary
import app.flowstead.data.RoomProjectRepository
import app.flowstead.data.engine.EngineClient
import app.flowstead.data.engine.EngineResult
import app.flowstead.data.settings.AppSettings
import app.flowstead.data.settings.SecretStore
import app.flowstead.data.settings.SettingsRepository
import app.flowstead.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class FakeProjectRepository : ProjectRepository {
    private data class Row(val id: String, val name: String, val updatedAt: Long, val flowsheet: Flowsheet, val solve: StoredSolve? = null)

    private val rows = MutableStateFlow<List<Row>>(emptyList())
    private var nextId = 1
    private var time = 1_000L

    override fun observeProjects(): Flow<List<ProjectSummary>> = rows.map { list ->
        list.sortedByDescending { it.updatedAt }.map { ProjectSummary(it.id, it.name, it.updatedAt, it.flowsheet.objects.size, it.solve != null) }
    }

    override fun observeProject(id: String): Flow<Project?> = rows.map { list ->
        list.firstOrNull { it.id == id }?.let { Project(it.id, it.name, it.flowsheet, it.updatedAt, it.solve) }
    }

    override suspend fun create(name: String, flowsheet: Flowsheet): String {
        val id = "p${nextId++}"
        rows.update { it + Row(id, RoomProjectRepository.cleanName(name), time++, flowsheet) }
        return id
    }

    override suspend fun duplicate(id: String): String? {
        val src = rows.value.firstOrNull { it.id == id } ?: return null
        return create("${src.name} (copy)", src.flowsheet)
    }

    override suspend fun rename(id: String, name: String) =
        rows.update { list -> list.map { if (it.id == id) it.copy(name = RoomProjectRepository.cleanName(name), updatedAt = time++) else it } }

    override suspend fun delete(id: String) = rows.update { list -> list.filterNot { it.id == id } }

    override suspend fun saveFlowsheet(id: String, flowsheet: Flowsheet) =
        rows.update { list -> list.map { if (it.id == id) it.copy(flowsheet = flowsheet, updatedAt = time++) else it } }

    override suspend fun saveSolve(id: String, solve: StoredSolve) =
        rows.update { list -> list.map { if (it.id == id) it.copy(solve = solve) else it } }
}

class FakeSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
    val state = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = state
    override suspend fun setEngineUrl(url: String) = state.update { it.copy(engineUrl = url) }
    override suspend fun setUnitSet(unitSet: UnitSet) = state.update { it.copy(unitSet = unitSet) }
    override suspend fun setThemeMode(mode: ThemeMode) = state.update { it.copy(themeMode = mode) }
    override suspend fun setDefaultAtmosphericPressure(pa: Double) = state.update { it.copy(defaultAtmosphericPressurePa = pa) }
}

class FakeSecretStore : SecretStore {
    private val key = MutableStateFlow<String?>(null)
    override val hasApiKey: Flow<Boolean> = key.map { it != null }
    override suspend fun apiKey(): String? = key.value
    override suspend fun setApiKey(key: String) { this.key.value = key.trim() }
    override suspend fun clearApiKey() { key.value = null }
}

class FakeEngineClient(var result: EngineResult<EngineHealth> = EngineResult.Ok(EngineHealth("ok", "0.1.0", "9.0.5"))) : EngineClient {
    val calls = mutableListOf<Pair<String, String?>>()
    var compoundResult: EngineResult<List<CompoundInfo>> = EngineResult.Ok(listOf(CompoundInfo("Methane")))
    var packageResult: EngineResult<List<PropertyPackageInfo>> = EngineResult.Ok(listOf(PropertyPackageInfo("Peng-Robinson (PR)", true)))

    override suspend fun health(baseUrl: String, apiKey: String?): EngineResult<EngineHealth> {
        calls += baseUrl to apiKey
        return result
    }

    override suspend fun compounds(baseUrl: String, apiKey: String?, query: String, limit: Int) = compoundResult

    override suspend fun propertyPackages(baseUrl: String, apiKey: String?) = packageResult

    var solveResult: EngineResult<SolveResult> = EngineResult.Ok(SolveResult(SolveStatus.Converged))
    val solved = mutableListOf<Flowsheet>()
    override suspend fun solve(baseUrl: String, apiKey: String?, flowsheet: Flowsheet): EngineResult<SolveResult> {
        solved += flowsheet
        return solveResult
    }
}
