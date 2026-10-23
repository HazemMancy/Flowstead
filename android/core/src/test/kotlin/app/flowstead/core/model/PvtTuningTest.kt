/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.api.PvtExperiments
import app.flowstead.core.api.PvtObservation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PvtTuningTest {
    private val plus = Hypothetical("C12+", normalBoilingPoint = 490.0, molecularWeight = 170.0)
    private val fs = Flowsheet(compounds = listOf("Methane", "C12+"), hypotheticals = listOf(plus))
    private val x = mapOf("Methane" to 0.6, "C12+" to 0.4)
    private val psat = PvtObservation(PvtExperiments.SATURATION, "saturationPressure", 2.1e7)

    @Test
    fun tuningNeedsAPlusFractionAndPressuresForTableValues() {
        assertNull(PvtTuning.problem(fs, x, listOf(psat)))
        assertEquals("Tuning adjusts the plus fraction: add a hypothetical component to this fluid", PvtTuning.problem(fs, mapOf("Methane" to 1.0), listOf(psat)))
        assertEquals("Give the pressure of each measurement", PvtTuning.problem(fs, x, listOf(PvtObservation(PvtExperiments.CCE, "relativeVolume", 1.2))))
        assertEquals("Add at least one lab measurement", PvtTuning.problem(fs, x, emptyList()))
    }

    @Test
    fun applyingSetsOnlyTheCriticalConstants() {
        val tuned = plus.copy(normalBoilingPoint = 999.0, criticalTemperature = 700.0, criticalPressure = 1.6e6, acentricFactor = 0.55)
        val h = PvtTuning.apply(fs, listOf(tuned)).hypotheticals.single()
        assertEquals(490.0, h.normalBoilingPoint)
        assertEquals(700.0, h.criticalTemperature)
        assertEquals(0.55, h.acentricFactor)
    }

    @Test
    fun kijIsSetPerPairAndGoesWithItsCompounds() {
        var f = FlowsheetEditing.setInteractionParameter(fs, "C12+", "Methane", 0.05)
        assertEquals(listOf(BinaryParameter("C12+", "Methane", 0.05)), f.interactionParameters)
        // The same pair either way round replaces it; null goes back to DWSIM's.
        f = FlowsheetEditing.setInteractionParameter(f, "Methane", "C12+", 0.07)
        assertEquals(0.07, f.interactionParameters.single().kij)
        assertEquals(emptyList(), FlowsheetEditing.setInteractionParameter(f, "Methane", "C12+", null).interactionParameters)
        // Removing a compound removes its pairs.
        assertEquals(emptyList(), FlowsheetEditing.setCompounds(f, listOf("Methane")).interactionParameters)
        // Tuned kij are applied with the constants.
        assertEquals(0.09, PvtTuning.apply(f, listOf(plus), listOf(BinaryParameter("Methane", "C12+", 0.09))).interactionParameters.single().kij)
    }
}
