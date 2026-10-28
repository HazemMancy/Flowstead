/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.data.queue

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.thermodock.core.model.Specification
import app.thermodock.core.model.StoredSolve
import app.thermodock.core.model.modelHash
import app.thermodock.data.Clock
import app.thermodock.data.ProjectRepository
import app.thermodock.data.engine.EngineClient
import app.thermodock.data.engine.EngineResult
import app.thermodock.data.settings.SecretStore
import app.thermodock.data.settings.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Solves waiting for a connection. A project queued while offline is solved once the phone is back
 * online, against the flowsheet as it is then; the result is stored like any other solve.
 */
interface SolveQueue {
    fun enqueue(projectId: String)
    fun cancel(projectId: String)
    fun observeQueued(projectId: String): Flow<Boolean>
}

@Singleton
class WorkManagerSolveQueue @Inject constructor(@ApplicationContext private val context: Context) : SolveQueue {
    private val work by lazy { WorkManager.getInstance(context) }

    override fun enqueue(projectId: String) {
        val request = OneTimeWorkRequestBuilder<SolveWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(SolveWorker.PROJECT_ID to projectId))
            .addTag(TAG)
            .build()
        work.enqueueUniqueWork(name(projectId), ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancel(projectId: String) {
        work.cancelUniqueWork(name(projectId))
    }

    override fun observeQueued(projectId: String): Flow<Boolean> =
        work.getWorkInfosForUniqueWorkFlow(name(projectId)).map { infos -> infos.any { !it.state.isFinished } }

    private fun name(projectId: String) = "solve-$projectId"

    private companion object {
        const val TAG = "queued-solve"
    }
}

/** What a queued solve did; kept apart from WorkManager so it can be tested on its own. */
enum class QueuedOutcome { Solved, RetryLater, Dropped }

class QueuedSolver @Inject constructor(
    private val repository: ProjectRepository,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val engine: EngineClient,
    private val clock: Clock,
) {
    suspend fun run(projectId: String): QueuedOutcome {
        val project = repository.observeProject(projectId).first() ?: return QueuedOutcome.Dropped
        val fs = project.flowsheet
        // Edited into an unsolvable state while waiting, or the engine address removed: nothing to send.
        if (Specification.problems(fs).isNotEmpty()) return QueuedOutcome.Dropped
        val url = settings.settings.first().engineTarget
        if (url.isBlank()) return QueuedOutcome.Dropped
        return when (val r = engine.solve(url, secrets.apiKey(), fs)) {
            is EngineResult.Ok -> {
                repository.saveSolve(projectId, StoredSolve(r.value, fs.modelHash(), clock.now()))
                QueuedOutcome.Solved
            }
            is EngineResult.Failed -> if (r.unreachable) QueuedOutcome.RetryLater else QueuedOutcome.Dropped
        }
    }
}

@HiltWorker
class SolveWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val solver: QueuedSolver,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(PROJECT_ID) ?: return Result.failure()
        return when (solver.run(id)) {
            QueuedOutcome.Solved -> Result.success()
            QueuedOutcome.RetryLater -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            QueuedOutcome.Dropped -> Result.failure()
        }
    }

    companion object {
        const val PROJECT_ID = "projectId"
        const val MAX_ATTEMPTS = 8
    }
}
