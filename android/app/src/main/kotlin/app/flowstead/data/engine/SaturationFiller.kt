/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.data.engine

import app.flowstead.core.api.ObjectStatus
import app.flowstead.core.model.EnthalpyBases
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.Readiness
import app.flowstead.core.model.StoredSolve
import app.flowstead.core.model.modelHash
import app.flowstead.core.model.obj
import app.flowstead.data.ProjectRepository
import app.flowstead.data.settings.SecretStore
import app.flowstead.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bubble and dew points on demand (D153, as the web's D121). Each is a DWSIM flash, four a stream, and they were most of a
 * solve's time, so a solve leaves them out. This asks the engine for the streams a screen shows (a stream's editor, the
 * results, a table that lists them, a document) and adds them to the stored solve. The engine reuses the solved
 * flowsheet, so only those flashes run. A stream is asked for once per solve; a solve saved meanwhile is never overwritten.
 */
@Singleton
class SaturationFiller @Inject constructor(
    private val repository: ProjectRepository,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val engine: EngineClient,
) {
    private val mutex = Mutex()
    private val asked = mutableSetOf<String>()

    /** Fills [ids] (null: every solved material stream) of [projectId]'s current solve; returns the solve as now stored. */
    suspend fun fill(projectId: String, ids: Collection<String>? = null): StoredSolve? = mutex.withLock {
        val project = repository.observeProject(projectId).first() ?: return@withLock null
        val fs = project.flowsheet
        val solve = project.solve ?: return@withLock null
        if (solve.modelHash != fs.modelHash()) return@withLock solve // out of date: the next solve comes first
        val streams = solve.result.objects.filter { (id, r) -> r.status == ObjectStatus.Solved && fs.obj(id)?.type == ObjectType.MaterialStream }.keys
        val need = (ids ?: streams).filter { id ->
            id in streams && KEYS.none { it in solve.result.objects.getValue(id).properties } && asked.add("$projectId|${solve.modelHash}|$id")
        }
        if (need.isEmpty()) return@withLock solve
        val url = settings.settings.first().engineTarget
        if (url.isBlank()) return@withLock solve
        val r = engine.solve(url, secrets.apiKey(), EnthalpyBases.forEngine(Readiness.of(fs).solvable, saturationFor = need))
        if (r !is EngineResult.Ok) return@withLock solve
        val latest = repository.observeProject(projectId).first()?.solve
        if (latest?.solvedAtMillis != solve.solvedAtMillis || latest.modelHash != solve.modelHash) return@withLock latest
        val objects = latest.result.objects + need.mapNotNull { id -> r.value.objects[id]?.let { id to it } }
        val merged = latest.copy(result = latest.result.copy(objects = objects))
        repository.saveSolve(projectId, merged)
        merged
    }

    companion object {
        /** The properties a solve leaves out. */
        val KEYS = listOf("bubblePointTemperature", "dewPointTemperature", "bubblePointPressure", "dewPointPressure")
    }
}
