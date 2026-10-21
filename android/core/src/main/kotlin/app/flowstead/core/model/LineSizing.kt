/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.units.Quantity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.sqrt

/** What a line carries, for choosing its sizing criteria. */
@Serializable
enum class LineService(val label: String) {
    @SerialName("gas") Gas("Gas"),
    @SerialName("liquid") Liquid("Liquid"),
    @SerialName("two_phase") TwoPhase("Two-phase"),
}

/**
 * The user's line-sizing limits for one service. Every limit is optional: an empty one is not checked.
 * Values are SI (m/s, kg/m·s², Pa/m); [Spec.unit] remembers the unit typed in.
 */
@Serializable
data class LineCriteria(
    val maxVelocity: Spec? = null,
    val minVelocity: Spec? = null,
    val maxMomentumFlux: Spec? = null,
    /** Frictional pressure drop per length (elevation excluded). */
    val maxFrictionGradient: Spec? = null,
    val maxMach: Spec? = null,
    /** API RP 14E erosional-velocity constant C (US customary: ft/s × √(lb/ft³)). */
    val erosionalC: Spec? = null,
) {
    val isEmpty: Boolean get() = listOf(maxVelocity, minVelocity, maxMomentumFlux, maxFrictionGradient, maxMach, erosionalC).all { it == null }
}

/** Sizing criteria for the flowsheet's lines, by service. Not part of the solved model. */
@Serializable
data class SizingCriteria(
    val gas: LineCriteria = LineCriteria(),
    val liquid: LineCriteria = LineCriteria(),
    val twoPhase: LineCriteria = LineCriteria(),
) {
    fun forService(s: LineService): LineCriteria = when (s) {
        LineService.Gas -> gas
        LineService.Liquid -> liquid
        LineService.TwoPhase -> twoPhase
    }

    fun with(s: LineService, c: LineCriteria): SizingCriteria = when (s) {
        LineService.Gas -> copy(gas = c)
        LineService.Liquid -> copy(liquid = c)
        LineService.TwoPhase -> copy(twoPhase = c)
    }
}

/**
 * One criterion compared with an engine result. [value] and [limit] are SI in [quantity];
 * [isMaximum] says whether the value must stay at or below the limit (else at or above).
 */
data class SizingCheck(
    val label: String,
    val quantity: Quantity,
    val value: Double,
    val limit: Double,
    val isMaximum: Boolean,
    val note: String? = null,
) {
    val passes: Boolean get() = if (isMaximum) value <= limit else value >= limit
}

object LineSizing {
    /** Pipe ref: "gas", "liquid", "two_phase"; absent = from the solved inlet and outlet. */
    const val SERVICE = "service"
    const val NPS = "nps"
    const val SCHEDULE = "schedule"

    /**
     * ρv² (SI) at which a velocity equals API RP 14E's erosional velocity v_e = C / √ρ (ft/s, lb/ft³).
     * v ≤ v_e ⇔ ρv² ≤ C² in lb/ft·s², and 1 lb/ft·s² = 0.45359237 / 0.3048 kg/m·s².
     */
    fun erosionalMomentumFlux(c: Double): Double = c * c * (0.45359237 / 0.3048)

    /** The pipe's service: the user's choice, else gas or liquid when both ends are single phase, else two-phase. */
    fun service(fs: Flowsheet, pipe: SimObject, results: ResultView): LineService? {
        pipe.refs[SERVICE]?.let { s -> LineService.entries.firstOrNull { it.serialName == s } }?.let { return it }
        val ends = Ports.of(pipe.type).filter { it.kind == PortKind.Material }.map { port ->
            fs.streamAt(pipe.id, port)?.let { (results.value(it.id, "vapourFraction") as? DisplayValue.Calculated)?.si }
        }
        if (ends.any { it == null }) return null
        return when {
            ends.all { it!! >= 1.0 - SINGLE_PHASE } -> LineService.Gas
            ends.all { it!! <= SINGLE_PHASE } -> LineService.Liquid
            else -> LineService.TwoPhase
        }
    }

    /** Checks for every criterion that is set and whose engine result exists. */
    fun checks(c: LineCriteria, pipeId: String, results: ResultView): List<SizingCheck> {
        fun r(key: String) = (results.value(pipeId, key) as? DisplayValue.Calculated)?.si
        return buildList {
            val vMax = r("maxVelocity")
            c.maxVelocity?.let { l -> vMax?.let { add(SizingCheck("Maximum velocity", Quantity.Velocity, it, l.si, true)) } }
            c.minVelocity?.let { l ->
                val lowest = listOfNotNull(r("inletVelocity"), r("outletVelocity")).minOrNull()
                lowest?.let { add(SizingCheck("Lowest velocity (inlet or outlet)", Quantity.Velocity, it, l.si, false)) }
            }
            val rv2 = r("maxMomentumFlux")
            c.maxMomentumFlux?.let { l -> rv2?.let { add(SizingCheck("Maximum ρv²", Quantity.MomentumFlux, it, l.si, true)) } }
            c.maxFrictionGradient?.let { l ->
                r("frictionPressureGradient")?.let { add(SizingCheck("ΔP per length (friction)", Quantity.PressureGradient, it, l.si, true)) }
            }
            c.maxMach?.let { l -> r("maxMachNumber")?.let { add(SizingCheck("Maximum Mach number", Quantity.Ratio, it, l.si, true)) } }
            c.erosionalC?.let { l ->
                rv2?.let {
                    val ratio = sqrt(it / erosionalMomentumFlux(l.si))
                    add(SizingCheck("Velocity / erosional velocity", Quantity.Ratio, ratio, 1.0, true, note = "API RP 14E, C = ${trim(l.si)}"))
                }
            }
        }
    }

    private const val SINGLE_PHASE = 1e-6
    private fun trim(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString()
    private val LineService.serialName: String get() = when (this) {
        LineService.Gas -> "gas"
        LineService.Liquid -> "liquid"
        LineService.TwoPhase -> "two_phase"
    }

    /** Service choices for a pipe's editor; null = automatic. */
    val SERVICE_OPTIONS: List<Pair<String?, String>> =
        listOf<Pair<String?, String>>(null to "Automatic (from the solved streams)") + LineService.entries.map { it.serialName to it.label }
}
