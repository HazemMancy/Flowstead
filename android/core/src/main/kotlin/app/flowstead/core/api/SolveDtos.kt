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

/**
 * Phase envelope of one composition; the flowsheet gives the package, compounds and options (D50). [qualities]: vapour
 * fractions for quality lines; [hydrates]: also the hydrate curve (D52); [pressure]: the stream's, for its hydrate temperature.
 */
@Serializable
data class PhaseEnvelopeRequest(
    val flowsheet: app.flowstead.core.model.Flowsheet,
    val composition: Map<String, Double>,
    val qualities: List<Double> = emptyList(),
    val hydrates: Boolean = false,
    val pressure: Double? = null,
    /** The stream's temperature, for the inhibitor it needs. */
    val temperature: Double? = null,
    /** [HydrateInhibitors] at [inhibitorWeightFraction] in the free water: the inhibited curve too. */
    val inhibitor: String? = null,
    val inhibitorWeightFraction: Double = 0.0,
)

/** Thermodynamic hydrate inhibitors (D52); same strings as the engine. */
object HydrateInhibitors {
    const val METHANOL = "methanol"
    const val MEG = "meg"
    const val DEG = "deg"
    const val TEG = "teg"
    val ALL = listOf(METHANOL, MEG, DEG, TEG)

    fun label(id: String): String = when (id) {
        METHANOL -> "Methanol"
        MEG -> "MEG"
        DEG -> "DEG"
        TEG -> "TEG"
        else -> id
    }
}

/** A line of constant vapour fraction (K, Pa). */
@Serializable
data class QualityLine(val quality: Double, val temperature: List<Double> = emptyList(), val pressure: List<Double> = emptyList())

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
    val qualityLines: List<QualityLine> = emptyList(),
    val hydrateTemperature: List<Double> = emptyList(),
    val hydratePressure: List<Double> = emptyList(),
    val hydrateStructure: List<String> = emptyList(),
    val hydrateTemperatureAtPressure: Double? = null,
    val hydrateStructureAtPressure: String? = null,
    /** With an inhibitor in the equilibrium (the water's activity): its curve, at its own pressures, and [hydrateDepression] (K) at the stream's pressure. */
    val inhibitedHydrateTemperature: List<Double> = emptyList(),
    val inhibitedHydratePressure: List<Double> = emptyList(),
    val inhibitedHydrateTemperatureAtPressure: Double? = null,
    val hydrateDepression: Double? = null,
    /** Weight fraction of the inhibitor (methanol if none chosen) the stream's free water needs; 0 when it is warm enough. */
    val requiredInhibitorFraction: Double? = null,
    val warnings: List<String> = emptyList(),
)

/** PVT experiments (D53); same strings as the engine. */
object PvtExperiments {
    const val SATURATION = "saturation"
    const val CCE = "cce"
    const val CVD = "cvd"
    const val DL = "dl"
    const val SEPARATOR = "separator"
}

@Serializable
data class SeparatorStage(val temperature: Double, val pressure: Double)

/** A PVT experiment on one composition at reservoir [temperature] (K); [pressures] (Pa) empty for the engine's default steps. */
@Serializable
data class PvtRequest(
    val flowsheet: app.flowstead.core.model.Flowsheet,
    val composition: Map<String, Double>,
    val experiment: String,
    val temperature: Double,
    val pressures: List<Double> = emptyList(),
    val stages: List<SeparatorStage> = emptyList(),
)

/** One pair of the package's kij table (D58): DWSIM's databank value (null: none, 0 used) and the value in use. */
@Serializable
data class BinaryParameterInfo(
    val a: String,
    val b: String,
    @SerialName("default") val databank: Double? = null,
    val kij: Double = 0.0,
    val user: Boolean = false,
)

/** A lab measurement to match (D53): [key] of [experiment] at [pressure], or a summary value when [pressure] is null. SI. */
@Serializable
data class PvtObservation(
    val experiment: String,
    val key: String,
    val value: Double,
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val pressure: Double? = null,
    val weight: Double = 1.0,
)

/** Tunes the plus fraction's Tc, Pc and ω (multipliers within ±[maxChange]) to [observations]; [tune] null: every hypothetical. */
@Serializable
data class PvtRegressionRequest(
    val flowsheet: app.flowstead.core.model.Flowsheet,
    val composition: Map<String, Double>,
    val temperature: Double,
    val observations: List<PvtObservation>,
    val tune: List<String>? = null,
    val maxChange: Double = 0.2,
    val maxIterations: Int = 12,
)

@Serializable
data class PvtMatch(val observation: PvtObservation, val before: Double? = null, val after: Double? = null)

/** The tuned hypotheticals (apply them to use them), the multipliers, each match before and after, and the RMS relative errors. */
@Serializable
data class PvtRegressionResult(
    val tuned: List<app.flowstead.core.model.Hypothetical> = emptyList(),
    /** With kij tuned (D58): the flowsheet's whole kij list to apply. */
    val interactionParameters: List<app.flowstead.core.model.BinaryParameter> = emptyList(),
    val multipliers: Map<String, Double> = emptyMap(),
    val matches: List<PvtMatch> = emptyList(),
    val errorBefore: Double? = null,
    val errorAfter: Double? = null,
    val iterations: Int = 0,
    val warnings: List<String> = emptyList(),
)

/** One PVT experiment's results: [table] has one list per column (in [columns] order), null where DWSIM gave nothing. */
@Serializable
data class PvtResult(
    val experiment: String,
    val temperature: Double = 0.0,
    val saturationPressure: Double? = null,
    /** "bubble" or "dew". */
    val saturationKind: String? = null,
    val columns: List<String> = emptyList(),
    val table: Map<String, List<Double?>> = emptyMap(),
    val summary: Map<String, Double> = emptyMap(),
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

/** A compound's molecular weight (kg/kmol) and ideal liquid density at 60 °F (kg/m³, null if the engine has none), D78. */
@Serializable
data class CompoundBasis(val name: String, val molecularWeight: Double, val stdLiquidDensity: Double? = null)
