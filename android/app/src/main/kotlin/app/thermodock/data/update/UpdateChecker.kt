/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.data.update

import app.thermodock.core.update.UpdateCheck
import app.thermodock.core.update.Updates
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton

/** Where "Check for updates" asks; an interface so tests needn't reach GitHub. */
fun interface UpdateChecker {
    suspend fun check(installed: String): Result<UpdateCheck>
}

/**
 * Asks GitHub for the latest ThermoDock release, only when the user taps "Check for updates" (no
 * background checks; privacy policy). Nothing about the user or their projects is sent.
 */
@Singleton
class GithubUpdateChecker @Inject constructor(private val http: HttpClient) : UpdateChecker {
    override suspend fun check(installed: String): Result<UpdateCheck> = runCatching {
        suspend fun latest(url: String) = http.get(url) {
            header("Accept", "application/vnd.github+json")
            header("User-Agent", "Flowstead/$installed") // GitHub's API requires one
        }
        // The repository's new address first; its old one until GitHub has the rename (D66).
        val response = latest(Updates.LATEST_RELEASE_URL).takeIf { it.status.value != 404 } ?: latest(Updates.LATEST_RELEASE_URL_BEFORE_RENAME)
        if (!response.status.isSuccess()) error("GitHub answered ${response.status.value}")
        Updates.check(installed, Updates.parse(response.bodyAsText()))
    }.recoverCatching { e ->
        throw IllegalStateException(e.message ?: "No connection", e)
    }
}
