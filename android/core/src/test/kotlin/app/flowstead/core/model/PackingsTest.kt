/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.api.MessageLevel
import app.flowstead.core.api.ObjectResult
import app.flowstead.core.api.ObjectStatus
import app.flowstead.core.api.SolveResult
import app.flowstead.core.api.SolveStatus
import app.flowstead.core.api.SolverMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Packed columns (D152): the choice in the column's refs and the engine's rating shown as it came back. */
class PackingsTest {
    private fun column(refs: Map<String, String>) = SimObject("t", "T-100", ObjectType.Absorber, refs = refs)

    @Test
    fun theCatalogueIsDwsimsLessThePackingsItCannotRate() {
        // DWSIM 10.2.15 has 161 packings; 12 have no packing factor and no Billet-Schultes constants.
        assertEquals(149, Packings.ALL.size)
        assertEquals(Packings.ALL.size, Packings.ALL.map { it.lowercase() }.toSet().size)
        assertTrue("Pall rings Metal 25 mm (Perry)" in Packings.RANDOM)
        assertTrue("Mellapak Sheet metal 250Y" in Packings.STRUCTURED)
        assertTrue("Raschig rings Carbon 25 mm" !in Packings.ALL)
    }

    @Test
    fun aPackedColumnShowsTheEnginesRatingAndNothingElse() {
        val o = column(mapOf(Packings.PACKING to "Pall rings Metal 25 mm (Perry)"))
        val fs = Flowsheet(objects = listOf(o))
        val props = mapOf(
            "packingRequiredDiameter" to 0.812, "packingDiameter" to 0.85, "packingFloodTarget" to 0.70, "packingFloodFraction" to 0.66,
            "packingLimitingStage" to 2.0, "packingFloodVelocity" to 0.41, "packingStages" to 10.0, "packingHetp" to 0.85,
            "packingHeight" to 8.5, "packingPressureDrop" to 2900.0, "packingMaxPressureGradient" to 410.0,
        )
        val solve = SolveResult(SolveStatus.Converged, objects = mapOf("t" to ObjectResult(ObjectStatus.Solved, properties = props,
            texts = mapOf("packing" to "Pall rings Metal 25 mm (Perry)", "packingModel" to "Robbins (1991) pressure drop", "packingSource" to "Perry 7th ed."))),
            messages = listOf(SolverMessage(MessageLevel.Warning, "T-100: Fraction of flood above 0.8.", "t")))
        val view = ResultView(StoredSolve(solve, fs.modelHash(), 0), fs.modelHash())
        val r = EquipmentSizing.size(fs, view, o, EquipmentSizingInput())!!
        fun line(label: String) = r.lines.single { it.label == label }
        assertEquals("Pall rings Metal 25 mm (Perry)", line("Packing").text)
        assertEquals(0.85, line("Diameter").si)
        assertEquals(66.0, line("Highest flood at this diameter, %").si!!, 1e-9)
        assertEquals(8.5, line("Bed height").si)
        assertEquals(2900.0, line("Pressure drop over the bed").si)
        // No wetting ratio came back: no line for it (never a number the engine did not return).
        assertTrue(r.lines.none { it.label.startsWith("Lowest liquid rate") })
        assertEquals(listOf("Fraction of flood above 0.8."), r.warnings)
        assertTrue(r.notes.first().contains("Robbins"))
        assertTrue(r.notes[1].contains("70 % of flood"))
    }

    @Test
    fun aColumnWithoutAPackingHasTraysAndRefsKeepTheNumbersInSi() {
        assertNull(Packings.of(column(emptyMap())))
        assertNull(Packings.of(column(mapOf(Packings.PACKING to " "))))
        val o = column(mapOf(Packings.DIAMETER to Packings.text(1.2), Packings.FLOOD to "-1"))
        assertEquals(1.2, Packings.number(o, Packings.DIAMETER))
        assertNull(Packings.number(o, Packings.FLOOD))
    }
}
