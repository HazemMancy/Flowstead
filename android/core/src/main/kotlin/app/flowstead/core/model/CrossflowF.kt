/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * The LMTD correction F of an air cooler (D143): crossflow, both fluids unmixed, in [passes] tube passes arranged
 * counter-cross (the process enters the top pass, where the air leaves). Between passes the process fluid mixes in the
 * headers; the air does not mix (each column of air carries its own temperature up through the bundle).
 *
 * Each pass is the two-dimensional crossflow problem ∂T/∂x = −a(T − t), ∂t/∂y = b(T − t), integrated on a grid with
 * the trapezoidal rule in each cell (second order) and Richardson-extrapolated from two grids. The process temperatures
 * between passes are unknowns of a linear problem, found exactly by superposition. F is the counter-current NTU for the
 * same duty over the crossflow NTU (Bowman, Mueller & Nagle's definition), with P and R on the tube (process) side.
 */
object CrossflowF {
    /**
     * F for the process (tubes) going [tIn] → [tOut] against air [airIn] → [airOut]; null when no crossflow exchanger of
     * that many passes does the duty (the temperatures demand more than an infinite one gives).
     */
    fun f(tIn: Double, tOut: Double, airIn: Double, airOut: Double, passes: Int): Double? {
        val p = (tIn - tOut) / (tIn - airIn)
        val r = (airOut - airIn) / (tIn - tOut)
        if (!(p > 0 && p < 1) || !(r >= 0) || !r.isFinite() || passes < 1) return null
        if (r * p >= 1) return null
        if (r < 1e-9) return 1.0
        val counter = counterNtu(p, r)
        val ntu = ntuFor(p, r, passes) ?: return null
        return (counter / ntu).takeIf { it.isFinite() && it > 0 }?.coerceAtMost(1.0)
    }

    /** Counter-current NTU (tube side) for P and R. */
    fun counterNtu(p: Double, r: Double): Double = if (abs(r - 1) < 1e-9) p / (1 - p) else ln((1 - r * p) / (1 - p)) / (1 - r)

    /** The tube-side NTU that gives P, by bisection on the monotone P(NTU); null if even NTU 60 falls short. */
    fun ntuFor(p: Double, r: Double, passes: Int): Double? {
        var lo = 0.0
        var hi = 0.25
        while (effectiveness(hi, r, passes) < p) {
            lo = hi; hi *= 2
            if (hi > 60) return null
        }
        repeat(60) {
            val mid = 0.5 * (lo + hi)
            if (effectiveness(mid, r, passes) < p) lo = mid else hi = mid
            if (hi - lo < 1e-10 * hi) return 0.5 * (lo + hi)
        }
        return 0.5 * (lo + hi)
    }

    /** Tube-side effectiveness P for tube NTU [ntu] (UA/C of the process) and R = C process / C air. */
    fun effectiveness(ntu: Double, r: Double, passes: Int, airMixedBetweenPasses: Boolean = false): Double {
        val coarse = grid(ntu, r, passes, 48, airMixedBetweenPasses)
        val fine = grid(ntu, r, passes, 96, airMixedBetweenPasses)
        return (4 * fine - coarse) / 3
    }

    /**
     * P on an n × n grid a pass. Temperatures scaled so the process enters at 1 and the air at 0. The passes are marched
     * in the air's direction (pass N, at the bottom, first); the process inlet of passes 2..N are unknowns τ, and each
     * pass's process outlet is affine in them, so N + 1 marches give the linear system for τ, one more the answer.
     */
    private fun grid(ntu: Double, r: Double, passes: Int, n: Int, airMixed: Boolean): Double {
        val alpha = ntu / passes / n            // process side, per cell
        val beta = ntu * r / passes / n          // air side, per cell
        val k = 1.0 / (1 + 0.5 * (alpha + beta))
        val air = DoubleArray(n)
        val row = DoubleArray(n)

        /** Process outlet of every pass, for process inlets [tau] (index 0 is pass 1, inlet 1). */
        fun march(tau: DoubleArray): DoubleArray {
            air.fill(0.0)
            val outs = DoubleArray(passes)
            for (pass in passes - 1 downTo 0) {
                val forward = pass % 2 == 0
                if (airMixed) air.fill(air.average())
                var sum = 0.0
                for (j in 0 until n) {
                    var t = tau[pass]
                    for (s in 0 until n) {
                        val i = if (forward) s else n - 1 - s
                        val d = (t - air[i]) * k
                        t -= alpha * d
                        air[i] += beta * d
                    }
                    sum += t
                }
                outs[pass] = sum / n
            }
            return outs
        }
        if (passes == 1) return 1 - march(doubleArrayOf(1.0))[0]
        // τ = (1, x2..xN): outs(x) = base + M x; conditions x_{k+1} = out_k for k = 1..N−1.
        val m = passes - 1
        val tau = DoubleArray(passes).also { it[0] = 1.0 }
        val base = march(tau)
        val a = Array(m) { DoubleArray(m) }
        for (j in 0 until m) {
            tau.fill(0.0); tau[0] = 1.0; tau[j + 1] = 1.0
            val o = march(tau)
            for (i in 0 until m) a[i][j] = -(o[i] - base[i])
        }
        for (i in 0 until m) a[i][i] += 1.0
        val x = solve(a, DoubleArray(m) { base[it] })
        tau.fill(0.0); tau[0] = 1.0
        for (i in 0 until m) tau[i + 1] = x[i]
        return 1 - march(tau)[passes - 1]
    }

    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        for (c in 0 until n) {
            val p = (c until n).maxBy { abs(a[it][c]) }
            a[c] = a[p].also { a[p] = a[c] }; b[c] = b[p].also { b[p] = b[c] }
            for (r in c + 1 until n) {
                val f = a[r][c] / a[c][c]
                for (k in c until n) a[r][k] -= f * a[c][k]
                b[r] -= f * b[c]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) x[r] = (b[r] - (r + 1 until n).sumOf { a[r][it] * x[it] }) / a[r][r]
        return x
    }

    /**
     * Nusselt's exact solution for one pass, both fluids unmixed (Mason 1955; Baclic 1990), as the check:
     * P = 1/(b) Σₙ [1 − e^(−a) Σₘ≤ₙ aᵐ/m!][1 − e^(−b) Σₘ≤ₙ bᵐ/m!], a the tube NTU, b = a·R the air NTU.
     */
    fun onePassSeries(ntu: Double, r: Double): Double {
        val a = ntu
        val b = ntu * r
        var sa = 0.0
        var sb = 0.0
        var ta = 1.0
        var tb = 1.0
        var sum = 0.0
        for (n in 0 until 400) {
            if (n > 0) { ta *= a / n; tb *= b / n }
            sa += ta; sb += tb
            val term = (1 - exp(-a) * sa) * (1 - exp(-b) * sb)
            sum += term
            if (term < 1e-17 && n > a + b) break
        }
        return sum / b
    }
}
