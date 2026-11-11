/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnthalpyBasesTest {
    private fun stream(specs: Map<String, Spec>) = SimObject("s", "1", ObjectType.MaterialStream, specs = specs, composition = mapOf("Methane" to 1.0))
    private val tp = mapOf(SpecKey.TEMPERATURE to Spec(300.0, "K"), SpecKey.PRESSURE to Spec(5e6, "Pa"), SpecKey.MOLAR_FLOW to Spec(10.0, "mol/s"))
    private val ph = mapOf(SpecKey.PRESSURE to Spec(5e6, "Pa"), SpecKey.MASS_ENTHALPY to Spec(-4.6e6, "J/kg"), SpecKey.MOLAR_FLOW to Spec(10.0, "mol/s"))
    private fun fs(specs: Map<String, Spec>, basis: String? = null) =
        Flowsheet(propertyPackage = "Peng-Robinson (PR)", compounds = listOf("Methane"), objects = listOf(stream(specs)), enthalpyBasis = basis)

    @Test
    fun byDefaultACaseIsOnHysysBasisUnlessItTypesAnEnthalpyOrEntropy() {
        assertEquals(EnthalpyBases.FORMATION, EnthalpyBases.effective(fs(tp)))
        assertEquals(EnthalpyBases.DWSIM, EnthalpyBases.effective(fs(ph)))
        assertEquals(EnthalpyBases.DWSIM, EnthalpyBases.effective(fs(mapOf("massEntropy" to Spec(11e3, "J/kg.K"), SpecKey.PRESSURE to Spec(5e6, "Pa")))))
        // A choice the user made wins either way.
        assertEquals(EnthalpyBases.FORMATION, EnthalpyBases.effective(fs(ph, EnthalpyBases.FORMATION)))
        assertEquals(EnthalpyBases.DWSIM, EnthalpyBases.effective(fs(tp, EnthalpyBases.DWSIM)))
    }

    @Test
    fun theEngineGetsFormationOrNothingAndTheStreamsThatGetSaturationPoints() {
        val sent = EnthalpyBases.forEngine(fs(tp), saturationFor = emptyList())
        assertEquals(EnthalpyBases.FORMATION, sent.enthalpyBasis)
        assertEquals(emptyList(), sent.saturationFor)
        assertNull(EnthalpyBases.forEngine(fs(tp, EnthalpyBases.DWSIM)).enthalpyBasis) // the engine reads anything else as DWSIM's
        assertNull(EnthalpyBases.forEngine(fs(tp)).saturationFor) // null: every stream, what an older engine does anyway
    }

    @Test
    fun theStreamsAskedForAreNotPartOfTheModelButTheBasisIs() {
        val f = fs(tp)
        assertEquals(f.modelHash(), f.copy(saturationFor = listOf("s")).modelHash())
        assertTrue(f.modelHash() != f.copy(enthalpyBasis = EnthalpyBases.DWSIM).modelHash())
        // Neither is written when unset, so every saved case reads and hashes as before.
        val json = FlowsheetJson.encodeToString(Flowsheet.serializer(), f)
        assertTrue("enthalpyBasis" !in json && "saturationFor" !in json, json)
    }
}
