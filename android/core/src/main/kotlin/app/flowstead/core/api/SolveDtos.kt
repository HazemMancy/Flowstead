/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `POST /solve` response (shared/openapi.yaml). All values SI. */
@Serializable
data class SolveResult(
    val status: SolveStatus,
    val elapsedMs: Long = 0,
    val iterations: Int? = null,
    val engineVersion: String = "",
    val dwsimVersion: String = "",
    val messages: List<SolverMessage> = emptyList(),
    val objects: Map<String, ObjectResult> = emptyMap(),
)

@Serializable
enum class SolveStatus {
    @SerialName("converged") Converged,
    @SerialName("failed") Failed,
    @SerialName("invalid") Invalid,
    @SerialName("timeout") Timeout,
}

@Serializable
data class SolverMessage(val level: MessageLevel, val text: String, val objectId: String? = null)

@Serializable
enum class MessageLevel {
    @SerialName("info") Info,
    @SerialName("warning") Warning,
    @SerialName("error") Error,
}

@Serializable
data class ObjectResult(
    val status: ObjectStatus,
    val error: String? = null,
    val properties: Map<String, Double> = emptyMap(),
    val phases: Map<String, PhaseResult>? = null,
    /** Text results such as a pipe's "flowRegime". */
    val texts: Map<String, String>? = null,
)

@Serializable
enum class ObjectStatus {
    @SerialName("solved") Solved,
    @SerialName("error") Error,
    @SerialName("not_solved") NotSolved,
}

@Serializable
data class PhaseResult(
    val properties: Map<String, Double> = emptyMap(),
    val composition: Map<String, Double> = emptyMap(),
)

/** Phase keys the engine uses, in display order. */
object Phases {
    const val OVERALL = "overall"
    const val VAPOUR = "vapour"
    const val LIQUID1 = "liquid1"
    const val LIQUID2 = "liquid2"
    val ORDER = listOf(OVERALL, VAPOUR, LIQUID1, LIQUID2)

    fun label(key: String) = when (key) {
        OVERALL -> "Overall"
        VAPOUR -> "Vapour"
        LIQUID1 -> "Liquid"
        LIQUID2 -> "Aqueous / 2nd liquid"
        else -> key
    }
}
