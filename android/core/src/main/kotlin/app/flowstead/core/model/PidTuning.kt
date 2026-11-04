/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A first-order-plus-dead-time model of a loop: gain [gain] (% PV per % output), time constant and dead time (s). */
data class Fopdt(val gain: Double, val timeConstant: Double, val deadTime: Double)

/** PI or PID settings in the engine's form (D86): gain in % output per % of the PV span, times in s (0 = off). */
data class PidSettings(val rule: String, val gain: Double, val integralTime: Double, val derivativeTime: Double)

/**
 * PID tuning from an open-loop step test (D105), as HYSYS's and DWSIM's autotuners: the controller is put in manual, its
 * output stepped, and the engine's dynamic run records the measurement. A first-order-plus-dead-time model is fitted to
 * that response by Smith's two-point method (the times to 28.3 % and 63.2 % of the change: τ = 1.5 (t63 − t28),
 * θ = t63 − τ), and the settings follow from the textbook rules.
 */
object PidTuning {
    /**
     * Fits a FOPDT model to a step response: [time] (s) and [pv] in % of the measurement span, the step at
     * [stepTime] of [outputChange] % output. Null when the response hardly moved or never settled.
     */
    fun fit(time: List<Double>, pv: List<Double>, stepTime: Double, outputChange: Double): Fopdt? {
        if (time.size != pv.size || time.size < 5 || outputChange == 0.0) return null
        val before = pv.withIndex().filter { time[it.index] <= stepTime }.map { it.value }
        val y0 = before.lastOrNull() ?: pv.first()
        // The final value: the mean of the last tenth, which must have settled to within 2 % of the change.
        val tail = pv.takeLast(max(3, pv.size / 10))
        val yEnd = tail.average()
        val change = yEnd - y0
        if (abs(change) < 1e-6 || (tail.maxOrNull()!! - tail.minOrNull()!!) > 0.02 * abs(change) + 1e-9) return null
        fun crossing(fraction: Double): Double? {
            val target = y0 + fraction * change
            for (k in 1 until time.size) {
                if (time[k] <= stepTime) continue
                val a = (pv[k - 1] - target) * (if (change > 0) 1 else -1)
                val b = (pv[k] - target) * (if (change > 0) 1 else -1)
                if (a < 0 && b >= 0) {
                    val t0 = max(time[k - 1], stepTime)
                    return t0 + (time[k] - t0) * (-a) / (b - a)
                }
            }
            return null
        }
        val t28 = (crossing(0.283) ?: return null) - stepTime
        val t63 = (crossing(0.632) ?: return null) - stepTime
        val tau = 1.5 * (t63 - t28)
        val theta = max(0.0, t63 - tau)
        if (!(tau > 0)) return null
        return Fopdt(change / outputChange, tau, theta)
    }

    /** The controller action for this process: 1 direct (output rises with the measurement) when the gain is negative. */
    fun action(m: Fopdt): Int = if (m.gain < 0) 1 else -1

    /** The FOPDT response at time t after the step (for drawing the fit over the data). */
    fun response(m: Fopdt, y0: Double, outputChange: Double, t: Double): Double =
        if (t <= m.deadTime) y0 else y0 + m.gain * outputChange * (1 - kotlin.math.exp(-(t - m.deadTime) / m.timeConstant))

    /**
     * Settings by four rules. SIMC (Skogestad 2003) with τc = θ (or [closedLoopTime]); IMC PI (Rivera et al. 1986,
     * Kc = τ / (K (λ + θ)), τI = τ); Ziegler–Nichols' reaction curve PID; Cohen–Coon PI. Gains are positive, as the
     * engine takes them: the action (direct when the process gain is negative) is set apart; a dead time of zero is
     * taken as 1 % of τ.
     */
    fun settings(m: Fopdt, closedLoopTime: Double? = null): List<PidSettings> {
        val k = abs(m.gain)
        val tau = m.timeConstant
        val theta = max(m.deadTime, 0.01 * tau)
        val tc = closedLoopTime ?: theta
        val r = theta / tau
        return listOf(
            PidSettings("SIMC PI", tau / (k * (tc + theta)), min(tau, 4 * (tc + theta)), 0.0),
            PidSettings("IMC PI", tau / (k * (tc + theta)), tau, 0.0),
            PidSettings("Ziegler–Nichols PID", 1.2 * tau / (k * theta), 2 * theta, 0.5 * theta),
            PidSettings("Cohen–Coon PI", (1 / (k * r)) * (0.9 + r / 12), theta * (30 + 3 * r) / (9 + 20 * r), 0.0),
        )
    }

    /**
     * The measurement span the engine's controller works in (DynamicsSim): the given PV range, else the vessel height for a
     * level, 1 for a level fraction, 100 K for a temperature, else twice the setpoint.
     */
    fun span(fs: Flowsheet, controller: SimObject, setpoint: Double): Double {
        val params = Dynamics.setup(fs).parameters[controller.id].orEmpty()
        val lo = params[DynamicKey.PV_MINIMUM]?.si
        val hi = params[DynamicKey.PV_MAXIMUM]?.si
        if (lo != null && hi != null && hi > lo) return hi - lo
        val key = controller.refs[AdjustKeys.CONTROLLED_PROPERTY]
        val vessel = controller.refs[AdjustKeys.CONTROLLED_OBJECT]
        return when (key) {
            "liquidLevel" -> Dynamics.setup(fs).parameters[vessel].orEmpty()[DynamicKey.HEIGHT]?.si ?: (2 * abs(setpoint)).coerceAtLeast(1e-6)
            "levelFraction" -> 1.0
            "temperature" -> 100.0
            else -> max(abs(setpoint), 1e-6) * 2
        }
    }

    /**
     * The open-loop step test as a dynamic run: the controller to manual at the start, its output stepped by [step] %
     * at [stepTime] from [startOutput] (read from a first short run).
     */
    fun stepTest(fs: Flowsheet, controllerId: String, startOutput: Double, step: Double, stepTime: Double, duration: Double): app.flowstead.core.api.DynamicsRequest {
        val base = Dynamics.request(fs)
        val events = listOf(
            DynamicEvent(0.0, controllerId, DynamicKey.MODE, 0.0),
            DynamicEvent(stepTime, controllerId, "output", (startOutput + step).coerceIn(0.0, 100.0)),
        )
        return base.copy(duration = duration, events = events)
    }
}