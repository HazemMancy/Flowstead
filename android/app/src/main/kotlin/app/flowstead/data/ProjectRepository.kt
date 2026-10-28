/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.data

import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.FlowsheetJson
import app.flowstead.core.model.StoredSolve
import app.flowstead.core.model.migrateResultHashes
import app.flowstead.core.model.modelHash
import app.flowstead.data.db.ProjectDao
import app.flowstead.data.db.ProjectEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class ProjectSummary(
    val id: String,
    val name: String,
    val updatedAt: Long,
    val objectCount: Int,
    val everSolved: Boolean,
)

data class Project(
    val id: String,
    val name: String,
    val flowsheet: Flowsheet,
    val updatedAt: Long,
    /** Last solve, if any. Check [resultsCurrent] before treating its values as up to date. */
    val solve: StoredSolve? = null,
) {
    /** True when the stored results belong to exactly this flowsheet. */
    val resultsCurrent: Boolean get() = solve != null && solve.modelHash == flowsheet.modelHash()
}

interface ProjectRepository {
    fun observeProjects(): Flow<List<ProjectSummary>>
    fun observeProject(id: String): Flow<Project?>
    suspend fun create(name: String, flowsheet: Flowsheet = Flowsheet.EMPTY): String
    suspend fun duplicate(id: String): String?
    suspend fun rename(id: String, name: String)
    suspend fun delete(id: String)
    suspend fun saveFlowsheet(id: String, flowsheet: Flowsheet)
    suspend fun saveSolve(id: String, solve: StoredSolve)
}

/** Clock seam so tests control timestamps. */
fun interface Clock {
    fun now(): Long
}

@Singleton
class RoomProjectRepository @Inject constructor(
    private val dao: ProjectDao,
    private val clock: Clock,
) : ProjectRepository {

    override fun observeProjects(): Flow<List<ProjectSummary>> = dao.observeSummaries().map { rows ->
        rows.map { ProjectSummary(it.id, it.name, it.updatedAt, it.objectCount, it.solvedHash != null) }
    }

    override fun observeProject(id: String): Flow<Project?> = dao.observe(id).map { e ->
        e?.let {
            // Results saved before positions left the model hash (1.8.8) stay current.
            val (flowsheet, solve) = migrateResultHashes(
                FlowsheetJson.decodeFromString(Flowsheet.serializer(), it.flowsheetJson),
                it.resultJson?.let { json -> runCatching { FlowsheetJson.decodeFromString(StoredSolve.serializer(), json) }.getOrNull() },
            )
            Project(id = it.id, name = it.name, flowsheet = flowsheet, updatedAt = it.updatedAt, solve = solve)
        }
    }

    override suspend fun create(name: String, flowsheet: Flowsheet): String {
        val id = UUID.randomUUID().toString()
        val now = clock.now()
        dao.insert(
            ProjectEntity(
                id = id,
                name = cleanName(name),
                createdAt = now,
                updatedAt = now,
                flowsheetJson = encode(flowsheet),
                objectCount = flowsheet.objects.size,
            ),
        )
        return id
    }

    override suspend fun duplicate(id: String): String? {
        val src = dao.get(id) ?: return null
        val newId = UUID.randomUUID().toString()
        val now = clock.now()
        // Results are not copied: the copy has to be solved on its own.
        dao.insert(
            src.copy(
                id = newId,
                name = cleanName("${src.name} (copy)"),
                createdAt = now,
                updatedAt = now,
                solvedHash = null,
                resultJson = null,
            ),
        )
        return newId
    }

    override suspend fun rename(id: String, name: String) = dao.rename(id, cleanName(name), clock.now())

    override suspend fun delete(id: String) = dao.delete(id)

    override suspend fun saveFlowsheet(id: String, flowsheet: Flowsheet) {
        val e = dao.get(id) ?: return
        dao.update(
            e.copy(flowsheetJson = encode(flowsheet), objectCount = flowsheet.objects.size, updatedAt = clock.now()),
        )
    }

    override suspend fun saveSolve(id: String, solve: StoredSolve) =
        dao.saveResult(id, FlowsheetJson.encodeToString(StoredSolve.serializer(), solve), solve.modelHash)

    private fun encode(fs: Flowsheet) = FlowsheetJson.encodeToString(Flowsheet.serializer(), fs)

    companion object {
        const val MAX_NAME = 80
        const val DEFAULT_NAME = "Untitled project"

        fun cleanName(name: String): String =
            name.trim().replace(Regex("\\s+"), " ").take(MAX_NAME).trim().ifEmpty { DEFAULT_NAME }
    }
}
