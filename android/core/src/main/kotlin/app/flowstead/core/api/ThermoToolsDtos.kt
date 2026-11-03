/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.api

import kotlinx.serialization.Serializable

/** Thermodynamics tools (D100); same shapes as the engine's. SI throughout. */
@Serializable
data class XY(val x: Double, val y: Double)

object BinaryDiagramKinds {
    const val TXY = "txy"
    const val PXY = "pxy"
}

@Serializable
data class BinaryDiagramRequest(
    val flowsheet: app.flowstead.core.model.Flowsheet,
    val first: String,
    val second: String,
    val kind: String = BinaryDiagramKinds.TXY,
    val pressure: Double = 101325.0,
    val temperature: Double = 298.15,
    val steps: Int = 40,
    val liquidLiquid: Boolean = true,
)

@Serializable
data class BinaryDiagramResult(
    val kind: String,
    val bubble: List<XY> = emptyList(),
    val dew: List<XY> = emptyList(),
    val liquidLiquid1: List<XY> = emptyList(),
    val liquidLiquid2: List<XY> = emptyList(),
    val warnings: List<String> = emptyList(),
)

@Serializable
data class TernaryRequest(
    val flowsheet: app.flowstead.core.model.Flowsheet,
    val compounds: List<String>,
    val temperature: Double,
    val pressure: Double,
    val step: Double = 0.1,
)

@Serializable
data class TernaryPoint(
    val feed: List<Double>,
    val vapourFraction: Double = 0.0,
    val vapour: List<Double>? = null,
    val liquid1: List<Double>? = null,
    val liquid2: List<Double>? = null,
    val liquid2Fraction: Double? = null,
)

@Serializable
data class TernaryResult(
    val compounds: List<String> = emptyList(),
    val points: List<TernaryPoint> = emptyList(),
    val failed: Int = 0,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class CriticalPointRequest(val flowsheet: app.flowstead.core.model.Flowsheet, val composition: Map<String, Double>)

/** K, Pa, m³/mol. */
@Serializable
data class CriticalPoint(val temperature: Double, val pressure: Double, val molarVolume: Double)

@Serializable
data class CriticalPointResult(val points: List<CriticalPoint> = emptyList(), val warnings: List<String> = emptyList())

@Serializable
data class PackageComparisonRequest(
    val flowsheet: app.flowstead.core.model.Flowsheet,
    val composition: Map<String, Double>,
    val temperature: Double,
    val pressure: Double,
    val packages: List<String>,
)

@Serializable
data class PackageComparisonRow(val `package`: String, val result: ObjectResult? = null, val error: String? = null)

@Serializable
data class PackageComparisonResult(val rows: List<PackageComparisonRow> = emptyList())

object SweepVariables {
    const val TEMPERATURE = "temperature"
    const val PRESSURE = "pressure"
}

@Serializable
data class PropertySweepRequest(
    val flowsheet: app.flowstead.core.model.Flowsheet,
    val composition: Map<String, Double>,
    val variable: String = SweepVariables.TEMPERATURE,
    val from: Double,
    val to: Double,
    val points: Int = 25,
    val fixed: Double,
)

@Serializable
data class PropertySweepResult(val values: List<Double> = emptyList(), val results: List<ObjectResult?> = emptyList())
