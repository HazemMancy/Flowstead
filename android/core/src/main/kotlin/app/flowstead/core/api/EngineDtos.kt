/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.api

import kotlinx.serialization.Serializable

/** `GET /health` (shared/openapi.yaml). */
@Serializable
data class EngineHealth(
    val status: String,
    val engineVersion: String,
    val dwsimVersion: String,
)

/** Error body the engine returns with any 4xx/5xx. */
@Serializable
data class EngineError(
    val code: String,
    val message: String,
)
