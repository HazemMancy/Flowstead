/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead

import android.net.Uri
import app.flowstead.core.api.CompoundInfo
import app.flowstead.core.api.DwsimFile
import app.flowstead.core.api.ExportResult
import app.flowstead.core.api.ImportResult
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
import app.flowstead.data.files.DocumentIo
import app.flowstead.data.files.PickedDocument
import app.flowstead.data.queue.SolveQueue
import app.flowstead.data.settings.AppSettings
import app.flowstead.data.settings.EngineMode
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

/** Remote mode by default: tests drive the engine through FakeEngineClient with an address. */
class FakeSettingsRepository(initial: AppSettings = AppSettings(engineMode = EngineMode.Remote)) : SettingsRepository {
    val state = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = state
    override suspend fun setEngineUrl(url: String) = state.update { it.copy(engineUrl = url) }
    override suspend fun setEngineMode(mode: EngineMode) = state.update { it.copy(engineMode = mode) }
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

    var exportResult: EngineResult<ExportResult> = EngineResult.Ok(ExportResult("UEsDBA==", SolveStatus.Converged)) // "PK\u0003\u0004"
    override suspend fun exportDwsim(baseUrl: String, apiKey: String?, flowsheet: Flowsheet) = exportResult

    var importResult: EngineResult<ImportResult> = EngineResult.Ok(ImportResult(Flowsheet()))
    val imported = mutableListOf<DwsimFile>()
    override suspend fun importDwsim(baseUrl: String, apiKey: String?, file: DwsimFile): EngineResult<ImportResult> {
        imported += file
        return importResult
    }
}

class FakeSolveQueue : SolveQueue {
    val queued = MutableStateFlow<Set<String>>(emptySet())
    override fun enqueue(projectId: String) = queued.update { it + projectId }
    override fun cancel(projectId: String) = queued.update { it - projectId }
    override fun observeQueued(projectId: String): Flow<Boolean> = queued.map { projectId in it }
}

class FakeDocumentIo(var toRead: PickedDocument? = PickedDocument("Plant.dwxmz", byteArrayOf(0x50, 0x4B))) : DocumentIo {
    val written = mutableMapOf<Uri, ByteArray>()
    override suspend fun read(uri: Uri): PickedDocument = toRead ?: throw java.io.IOException("Permission denied")
    override suspend fun write(uri: Uri, bytes: ByteArray) { written[uri] = bytes }
}

/** The real bundled catalogue, read from the repo (no Android assets needed). */
class FileCompoundLibrary : app.flowstead.data.compounds.CompoundLibrary {
    private val catalogue by lazy {
        val repo = System.getProperty("flowstead.repoDir") ?: error("flowstead.repoDir not set")
        app.flowstead.core.compounds.CompoundCatalogue.parse(java.io.File(repo, "shared/compounds/catalogue.json").readText())
    }
    override suspend fun catalogue() = catalogue
}
