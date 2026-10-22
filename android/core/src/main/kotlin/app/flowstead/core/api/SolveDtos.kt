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
    /** Profiles along the unit, SI, keyed by [ProfileKeys]; every list the same length (D50). */
    val profiles: Map<String, List<Double>>? = null,
)

/** Profile series names; same strings as the engine's ProfileKeys. The first of each group is the x axis. */
object ProfileKeys {
    // Heat exchanger, from the hot inlet end: heat transferred (W), hot and cold temperatures (K).
    const val HEAT_FLOW = "heatFlow"
    const val HOT_TEMPERATURE = "hotTemperature"
    const val COLD_TEMPERATURE = "coldTemperature"
    // Pipe, from the inlet: distance (m), pressure (Pa), temperature (K), liquid holdup, mixture velocity (m/s).
    const val LENGTH = "length"
    const val PRESSURE = "pressure"
    const val TEMPERATURE = "temperature"
    const val LIQUID_HOLDUP = "liquidHoldup"
    const val VELOCITY = "velocity"
    // Column, top to bottom: stage number, temperature, pressure, vapour and liquid leaving (mol/s).
    const val STAGE = "stage"
    const val VAPOUR_FLOW = "vapourFlow"
    const val LIQUID_FLOW = "liquidFlow"
}

/** Phase envelope of one composition; the flowsheet gives the package, compounds and options (D50). */
@Serializable
data class PhaseEnvelopeRequest(val flowsheet: app.flowstead.core.model.Flowsheet, val composition: Map<String, Double>)

/** DWSIM's bubble and dew curves (K, Pa); the special points are null when DWSIM did not find them. */
@Serializable
data class PhaseEnvelope(
    val bubbleTemperature: List<Double> = emptyList(),
    val bubblePressure: List<Double> = emptyList(),
    val dewTemperature: List<Double> = emptyList(),
    val dewPressure: List<Double> = emptyList(),
    val criticalTemperature: Double? = null,
    val criticalPressure: Double? = null,
    val cricondenbarTemperature: Double? = null,
    val cricondenbarPressure: Double? = null,
    val cricondenthermTemperature: Double? = null,
    val cricondenthermPressure: Double? = null,
    val warnings: List<String> = emptyList(),
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
/** The constants the engine uses for a hypothetical (SI: K, Pa, m³/kmol); [estimated] names the fields it estimated. */
@Serializable
data class HypotheticalProperties(
    val name: String,
    val normalBoilingPoint: Double,
    val molecularWeight: Double,
    val specificGravity: Double,
    val criticalTemperature: Double,
    val criticalPressure: Double,
    val acentricFactor: Double,
    val criticalVolume: Double,
    val watsonK: Double,
    val formula: String = "",
    val estimated: List<String> = emptyList(),
)

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
