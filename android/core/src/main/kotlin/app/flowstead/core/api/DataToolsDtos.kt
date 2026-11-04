/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.api

import app.flowstead.core.model.BinaryParameter
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.Hypothetical
import app.flowstead.core.model.Reaction
import kotlinx.serialization.Serializable

// Compound and data tools (D104), the optimiser (D105) and the reaction finder (D106); the engine's DataTools.cs.

@Serializable
data class StructureCompoundRequest(val compound: Hypothetical)

@Serializable
data class JobackGroupCount(val id: Int, val name: String, val count: Int)

/** Joback's estimate. SI: K, Pa, m³/mol, J/mol; ideal-gas Cp = A + B·T + C·T² + D·T³ in J/(mol·K). */
@Serializable
data class StructureCompoundResult(
    val groups: List<JobackGroupCount> = emptyList(),
    val formula: String = "",
    val molecularWeight: Double = 0.0,
    val normalBoilingPoint: Double = 0.0,
    val criticalTemperature: Double = 0.0,
    val criticalPressure: Double = 0.0,
    val criticalVolume: Double = 0.0,
    val acentricFactor: Double = 0.0,
    val enthalpyOfFormation: Double = 0.0,
    val gibbsEnergyOfFormation: Double = 0.0,
    val idealGasCp: List<Double> = emptyList(),
    val warnings: List<String> = emptyList(),
)

object RegressionParameters {
    const val KIJ = "kij"
    const val NRTL = "nrtl"
    const val UNIQUAC = "uniquac"
}

/** A measured point: x and (optionally) y of the first compound, T in K, P in Pa. */
@Serializable
data class VlePoint(val x: Double, val y: Double? = null, val temperature: Double, val pressure: Double)

@Serializable
data class VleRegressionRequest(
    val flowsheet: Flowsheet,
    val first: String,
    val second: String,
    val kind: String = BinaryDiagramKinds.TXY,
    val points: List<VlePoint>,
    val parameters: String = RegressionParameters.KIJ,
    val alpha: Double? = null,
)

@Serializable
data class VleFitPoint(val x: Double, val yMeasured: Double? = null, val yCalculated: Double? = null, val measured: Double, val calculated: Double)

@Serializable
data class VleRegressionResult(
    val parameter: BinaryParameter,
    val before: List<VleFitPoint> = emptyList(),
    val after: List<VleFitPoint> = emptyList(),
    val deviationBefore: Double = 0.0,
    val deviationAfter: Double = 0.0,
    val vapourDeviationBefore: Double? = null,
    val vapourDeviationAfter: Double? = null,
    val evaluations: Int = 0,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class OptimiserVariable(val objectId: String, val key: String, val min: Double, val max: Double)

@Serializable
data class OptimiserTarget(val objectId: String, val property: String, val min: Double? = null, val max: Double? = null)

@Serializable
data class OptimiseRequest(
    val flowsheet: Flowsheet,
    val variables: List<OptimiserVariable>,
    /** The objective is the sum of these results. */
    val objective: List<OptimiserTarget>,
    val maximise: Boolean = false,
    val constraints: List<OptimiserTarget> = emptyList(),
    val maxSolves: Int = 120,
)

@Serializable
data class OptimiserStep(val values: List<Double>, val objective: Double? = null, val feasible: Boolean = false)

@Serializable
data class OptimiseResult(
    val values: List<Double> = emptyList(),
    val objective: Double? = null,
    val feasible: Boolean = false,
    val converged: Boolean = false,
    val solves: Int = 0,
    val constraintValues: List<Double?> = emptyList(),
    val history: List<OptimiserStep> = emptyList(),
    val solution: SolveResult? = null,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class ReactionFinderRequest(val flowsheet: Flowsheet)

@Serializable
data class SuggestedReactionInfo(val category: String, val name: String, val equation: String, val reaction: Reaction, val note: String? = null)

@Serializable
data class ReactionFinderResult(val reactions: List<SuggestedReactionInfo> = emptyList(), val warnings: List<String> = emptyList())
