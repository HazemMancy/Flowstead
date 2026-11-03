/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.api

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/** Petroleum assays and a stream's distillation curves (D99); same shapes as the engine's. All SI (K, Pa). */
object DistillationCurveKinds {
    const val TBP = "tbp"
    const val D86 = "d86"
    const val D1160 = "d1160"
    const val D2887 = "d2887"
    const val EFV = "efv"

    fun label(kind: String): String = when (kind) {
        TBP -> "TBP"
        D86 -> "ASTM D86"
        D1160 -> "ASTM D1160"
        D2887 -> "ASTM D2887 (simulated)"
        EFV -> "EFV"
        else -> kind
    }
}

object AssayBases {
    const val VOLUME = "volume"
    const val MASS = "mass"
    const val MOLE = "mole"

    fun label(basis: String): String = when (basis) {
        MASS -> "mass %"
        MOLE -> "mole %"
        else -> "liquid volume %"
    }
}

@Serializable
data class AssayPoint(val percent: Double, val value: Double)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AssayRequest(
    val flowsheet: app.flowstead.core.model.Flowsheet,
    val name: String,
    val curveType: String = DistillationCurveKinds.TBP,
    val basis: String = AssayBases.VOLUME,
    val points: List<AssayPoint> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val gravity: List<AssayPoint> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val molecularWeights: List<AssayPoint> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val bulkSpecificGravity: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val bulkMolecularWeight: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val bulkBoilingPoint: Double? = null,
    val cuts: Int = 10,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val cutTemperatures: List<Double> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val lightEnds: Map<String, Double> = emptyMap(),
    val lightEndsBasis: String = AssayBases.VOLUME,
    val lightEndsInCurve: Boolean = false,
)

@Serializable
data class AssayCut(
    val name: String,
    /** Null for a bulk plus fraction (no curve to cut). */
    val startTemperature: Double? = null,
    val endTemperature: Double? = null,
    val normalBoilingPoint: Double,
    val specificGravity: Double,
    val molecularWeight: Double,
    val fraction: Double,
    val moleFraction: Double,
)

@Serializable
data class AssayResult(
    val hypotheticals: List<app.flowstead.core.model.Hypothetical> = emptyList(),
    val composition: Map<String, Double> = emptyMap(),
    val cuts: List<AssayCut> = emptyList(),
    val tbp: List<AssayPoint> = emptyList(),
    val specificGravity: Double = 0.0,
    val molecularWeight: Double = 0.0,
    val watsonK: Double = 0.0,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class PetroleumPropertiesRequest(
    val flowsheet: app.flowstead.core.model.Flowsheet,
    val composition: Map<String, Double>,
    val tbpBasis: String = AssayBases.VOLUME,
)

@Serializable
data class DistillationCurveResult(val kind: String, val basis: String, val pressure: Double, val points: List<AssayPoint> = emptyList())

@Serializable
data class PetroleumPropertiesResult(
    val curves: List<DistillationCurveResult> = emptyList(),
    /** [PetroleumKeys], SI; what DWSIM could not work out is absent. */
    val properties: Map<String, Double> = emptyMap(),
    val warnings: List<String> = emptyList(),
)

object PetroleumKeys {
    const val MOLECULAR_WEIGHT = "molecularWeight"
    const val SPECIFIC_GRAVITY = "specificGravity"
    const val API = "apiGravity"
    const val WATSON_K = "watsonK"
    const val MEABP = "meanAverageBoilingPoint"
    const val DENSITY_15C = "density15C"
    const val KIN_VISC_40C = "kinematicViscosity40C"
    const val KIN_VISC_100C = "kinematicViscosity100C"
    const val FLASH_CLOSED = "flashPointClosedCup"
    const val FLASH_OPEN = "flashPointOpenCup"
    const val POUR = "pourPoint"
    const val CLOUD = "cloudPoint"
    const val FREEZING = "freezingPoint"
    const val ANILINE = "anilinePoint"
    const val SMOKE = "smokePoint"
    const val REFRACTIVE = "refractiveIndex"
    const val CETANE = "cetaneIndex"
    const val CETANE_D4737 = "cetaneIndexD4737"
    const val TVP = "trueVapourPressure"
    const val RVP = "reidVapourPressure"
    const val SULFUR = "sulfurWeightPercent"

    /** Label, and what the value is: a temperature, pressure, density, kinematic viscosity, length or a plain number. */
    enum class Kind { Temperature, Pressure, Density, KinematicViscosity, Length, Number }

    val ROWS: List<Triple<String, String, Kind>> = listOf(
        Triple(SPECIFIC_GRAVITY, "Specific gravity 60/60 °F", Kind.Number),
        Triple(API, "API gravity", Kind.Number),
        Triple(WATSON_K, "Watson K", Kind.Number),
        Triple(MOLECULAR_WEIGHT, "Molecular weight", Kind.Number),
        Triple(MEABP, "Mean average boiling point", Kind.Temperature),
        Triple(DENSITY_15C, "Density at 15 °C", Kind.Density),
        Triple(KIN_VISC_40C, "Kinematic viscosity at 40 °C", Kind.KinematicViscosity),
        Triple(KIN_VISC_100C, "Kinematic viscosity at 100 °C", Kind.KinematicViscosity),
        Triple(FLASH_CLOSED, "Flash point, closed cup (API 2B7.1)", Kind.Temperature),
        Triple(FLASH_OPEN, "Flash point, open cup", Kind.Temperature),
        Triple(POUR, "Pour point (API 2B8.1)", Kind.Temperature),
        Triple(CLOUD, "Cloud point", Kind.Temperature),
        Triple(FREEZING, "Freezing point", Kind.Temperature),
        Triple(ANILINE, "Aniline point", Kind.Temperature),
        Triple(SMOKE, "Smoke point", Kind.Length),
        Triple(REFRACTIVE, "Refractive index", Kind.Number),
        Triple(CETANE, "Cetane index (API 2B13.1)", Kind.Number),
        Triple(CETANE_D4737, "Cetane index (ASTM D4737)", Kind.Number),
        Triple(TVP, "True vapour pressure at 37.8 °C", Kind.Pressure),
        Triple(RVP, "Reid vapour pressure", Kind.Pressure),
        Triple(SULFUR, "Sulfur, weight %", Kind.Number),
    )
}
