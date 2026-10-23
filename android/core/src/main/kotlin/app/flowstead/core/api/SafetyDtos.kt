/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.api

import app.flowstead.core.model.Flowsheet
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/** Relief scenarios (D55); same strings as the engine. */
object ReliefScenarioKinds {
    const val FIRE = "fire"
    const val BLOCKED_OUTLET = "blockedOutlet"
    const val CONTROL_VALVE_FAILURE = "controlValveFailure"
    const val THERMAL_EXPANSION = "thermalExpansion"
    const val CUSTOM = "custom"
    val ALL = listOf(FIRE, BLOCKED_OUTLET, CONTROL_VALVE_FAILURE, THERMAL_EXPANSION, CUSTOM)

    fun label(kind: String): String = when (kind) {
        FIRE -> "Fire"
        BLOCKED_OUTLET -> "Blocked outlet"
        CONTROL_VALVE_FAILURE -> "Control valve failure"
        THERMAL_EXPANSION -> "Thermal expansion"
        CUSTOM -> "Other (given flow)"
        else -> kind
    }
}

/** Relief valve designs (API 520 Part I). */
object ValveDesigns {
    const val CONVENTIONAL = "conventional"
    const val BALANCED_BELLOWS = "balancedBellows"
    const val PILOT = "pilot"
    val ALL = listOf(CONVENTIONAL to "Conventional", BALANCED_BELLOWS to "Balanced bellows", PILOT to "Pilot-operated")
}

/** Cylinder with 2:1 heads (m); [liquidLevel] from the bottom, [elevation] of the bottom above grade. */
@Serializable
data class VesselGeometry(
    val diameter: Double,
    val length: Double,
    val horizontal: Boolean = false,
    val liquidLevel: Double = 0.0,
    val elevation: Double = 0.0,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ReliefScenario(
    val kind: String,
    val name: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val overpressure: Double? = null,
    val environmentFactor: Double = 1.0,
    val adequateDrainage: Boolean = true,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val area: Double? = null,
    val wallTemperature: Double = 866.0,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val massFlow: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val composition: Map<String, Double>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val temperature: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val upstreamPressure: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val ratedCv: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val heatInput: Double? = null,
)

@Serializable
data class ReliefLine(
    val insideDiameter: Double,
    val length: Double,
    val roughness: Double = 4.57e-5,
    val fittingsLength: Double = 0.0,
    val elevationChange: Double = 0.0,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ReliefDevice(
    val id: String,
    val tag: String,
    val setPressure: Double,
    val backPressure: Double = 101325.0,
    val design: String = ValveDesigns.CONVENTIONAL,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val kd: Double? = null,
    val kb: Double = 1.0,
    val ruptureDisk: Boolean = false,
    val composition: Map<String, Double> = emptyMap(),
    val operatingTemperature: Double = 0.0,
    val operatingPressure: Double = 0.0,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val vessel: VesselGeometry? = null,
    val scenarios: List<ReliefScenario> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val inletLine: ReliefLine? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val outletLine: ReliefLine? = null,
)

@Serializable
data class ReliefRequest(val flowsheet: Flowsheet, val device: ReliefDevice)

@Serializable
data class ScenarioResult(
    val kind: String,
    val name: String,
    val massFlow: Double? = null,
    val phase: String? = null,
    val relievingPressure: Double = 0.0,
    val relievingTemperature: Double? = null,
    val requiredArea: Double? = null,
    val method: String? = null,
    val details: Map<String, Double> = emptyMap(),
    val error: String? = null,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class LineCheck(val pressureDrop: Double? = null, val limit: Double? = null, val fractionOfSet: Double? = null, val ok: Boolean = false, val note: String = "")

@Serializable
data class ReliefResult(
    val scenarios: List<ScenarioResult> = emptyList(),
    val governing: String? = null,
    val requiredArea: Double? = null,
    val orifice: String? = null,
    val orificeArea: Double? = null,
    val valves: Int = 0,
    val ratedFlow: Double? = null,
    val inlet: LineCheck? = null,
    val outlet: LineCheck? = null,
    val warnings: List<String> = emptyList(),
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DepressuringRequest(
    val flowsheet: Flowsheet,
    val composition: Map<String, Double>,
    val initialPressure: Double,
    val initialTemperature: Double,
    val vessel: VesselGeometry,
    val backPressure: Double = 101325.0,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val orificeDiameter: Double? = null,
    val dischargeCoefficient: Double = 0.84,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val targetPressure: Double? = null,
    val targetTime: Double = 900.0,
    val duration: Double = 1800.0,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val fire: ReliefScenario? = null,
    val heatTransferCoefficient: Double = 30.0,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val wallThickness: Double? = null,
)

@Serializable
data class DepressuringResult(
    val orificeDiameter: Double = 0.0,
    val orificeSized: Boolean = false,
    val targetPressure: Double = 0.0,
    val timeToTarget: Double? = null,
    val minimumTemperature: Double = 0.0,
    val minimumWallTemperature: Double = 0.0,
    val wallThickness: Double = 0.0,
    val peakFlow: Double = 0.0,
    val time: List<Double> = emptyList(),
    val pressure: List<Double> = emptyList(),
    val temperature: List<Double> = emptyList(),
    val wallTemperature: List<Double> = emptyList(),
    val massFlow: List<Double> = emptyList(),
    val inventory: List<Double> = emptyList(),
    val liquidVolumeFraction: List<Double> = emptyList(),
    val warnings: List<String> = emptyList(),
)
