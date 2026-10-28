/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.api.CompoundBasis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CompositionBasisTest {
    // Engine values (CompoundBasisTests): MW kg/kmol, ideal liquid density at 60 °F kg/m³.
    private val data = listOf(CompoundBasis("Methane", 16.043, 299.6), CompoundBasis("N-hexane", 86.177, 664.0)).associateBy { it.name }
    private val x = mapOf("Methane" to 0.5, "N-hexane" to 0.5)

    @Test
    fun massFractionsFollowMolecularWeights() {
        val w = CompositionBasis.MassFraction.fromMole(x, data)!!
        assertEquals(16.043 / (16.043 + 86.177), w.getValue("Methane"), 1e-12)
        // And back: the mole fractions come out as they went in.
        CompositionBasis.MassPercent.toMole(w.mapValues { it.value * 100 }, data)!!.forEach { (c, v) -> assertEquals(x.getValue(c), v, 1e-12) }
    }

    @Test
    fun liquidVolumeUsesMolarVolumesAt60F() {
        val v = CompositionBasis.VolumePercent.fromMole(x, data)!!
        val vm = 16.043 / 299.6
        val vh = 86.177 / 664.0
        assertEquals(100 * vm / (vm + vh), v.getValue("Methane"), 1e-9)
        CompositionBasis.VolumeFraction.toMole(v.mapValues { it.value / 100 }, data)!!.forEach { (c, y) -> assertEquals(x.getValue(c), y, 1e-12) }
    }

    @Test
    fun molePercentIsFractionsTimesAHundredAndMissingDataIsSaidSo() {
        assertEquals(mapOf("Methane" to 0.5, "N-hexane" to 0.5), CompositionBasis.MolePercent.toMole(mapOf("Methane" to 50.0, "N-hexane" to 50.0), null))
        assertNull(CompositionBasis.MassFraction.fromMole(x, null))
        assertEquals(listOf("N-hexane"), CompositionBasis.VolumeFraction.missing(listOf("Methane", "N-hexane"), mapOf("Methane" to data.getValue("Methane"))))
    }
}
