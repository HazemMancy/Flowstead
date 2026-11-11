/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrossflowFTest {
    @Test
    fun onePassMatchesNusseltsExactSolution() {
        // Kays & London, Compact Heat Exchangers (crossflow, both fluids unmixed): ε = 0.476 at NTU 1, Cr 1.
        assertEquals(0.476, CrossflowF.onePassSeries(1.0, 1.0), 5e-4)
        for ((ntu, r) in listOf(0.3 to 0.5, 1.0 to 1.0, 2.0 to 0.25, 3.0 to 1.0, 1.5 to 2.0, 4.0 to 0.75)) {
            assertEquals(CrossflowF.onePassSeries(ntu, r), CrossflowF.effectiveness(ntu, r, 1), 2e-6, "NTU $ntu, R $r")
        }
    }

    @Test
    fun passesInCounterSeriesMatchTheExactFormulaWhenTheAirMixes() {
        // N identical crossflow passes in counter-current series with both fluids mixed between them (TEMA/Pignotti):
        // P = (Z^N − 1)/(Z^N − R), Z = (1 − R·P₁)/(1 − P₁), P₁ one pass's (exact series) at NTU/N.
        for (n in 2..4) for ((ntu, r) in listOf(1.0 to 1.0, 2.5 to 0.6, 3.0 to 1.4)) {
            val p1 = CrossflowF.onePassSeries(ntu / n, r)
            val z = (1 - r * p1) / (1 - p1)
            val exact = if (r == 1.0) n * p1 / (1 + (n - 1) * p1) else (z.pow(n) - 1) / (z.pow(n) - r)
            assertEquals(exact, CrossflowF.effectiveness(ntu, r, n, airMixedBetweenPasses = true), 2e-6, "$n passes, NTU $ntu, R $r")
        }
    }

    @Test
    fun morePassesApproachCounterCurrent() {
        // The return headers reverse the process in each pass, so a column of air meets the coldest end of one pass under
        // the hottest end of the next ("inverted order"): with unmixed air this gives slightly less than mixed air would.
        val (ntu, r) = 2.0 to 1.0
        val counter = 1 - 1 / (1 + ntu) // counter-current at R 1: P = NTU/(1 + NTU)
        var last = 0.0
        for (n in 1..6) {
            val p = CrossflowF.effectiveness(ntu, r, n)
            if (n > 1) assertEquals(CrossflowF.effectiveness(ntu, r, n, airMixedBetweenPasses = true), p, 0.01 * p, "$n passes")
            assertTrue(p > last - 1e-9 && p < counter, "$n passes: P $p")
            last = p
        }
        assertEquals(counter, CrossflowF.effectiveness(ntu, r, 40), 0.01)
    }

    @Test
    fun fIsTheCounterCurrentNtuOverTheCrossflowNtu() {
        // A duty from a known crossflow exchanger: tube NTU 1.6, R 0.8, 2 passes; F·NTU must give the counter-current NTU.
        val ntu = 1.6
        val r = 0.8
        val p = CrossflowF.effectiveness(ntu, r, 2)
        val tIn = 150.0
        val tOut = tIn - p * (tIn - 35.0)
        val airOut = 35.0 + r * (tIn - tOut)
        val f = CrossflowF.f(tIn, tOut, 35.0, airOut, 2)!!
        assertEquals(CrossflowF.counterNtu(p, r), f * ntu, 1e-6)
        assertTrue(f < 1 && f > CrossflowF.f(tIn, tOut, 35.0, airOut, 1)!!)
        // Air that does not warm: any arrangement is counter-current. A duty past what an infinite exchanger gives: none.
        assertEquals(1.0, CrossflowF.f(150.0, 60.0, 35.0, 35.0, 1))
        assertNull(CrossflowF.f(150.0, 40.0, 35.0, 140.0, 1))
    }
}
