/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.api

import app.flowstead.core.model.DynamicEvent
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.Spec
import kotlinx.serialization.Serializable

/** A dynamic run from the steady state (D86); mirrors the engine's DynamicsRequest. */
@Serializable
data class DynamicsRequest(
    val flowsheet: Flowsheet,
    val duration: Double,
    val step: Double,
    val events: List<DynamicEvent> = emptyList(),
    val parameters: Map<String, Map<String, Spec>> = emptyMap(),
)

/** One calculated variable over time, SI. */
@Serializable
data class Trend(val objectId: String, val key: String, val values: List<Double>)

@Serializable
data class DynamicsResult(
    val status: SolveStatus,
    val messages: List<SolverMessage> = emptyList(),
    val times: List<Double> = emptyList(),
    val trends: List<Trend> = emptyList(),
    val elapsedMs: Long = 0,
)
