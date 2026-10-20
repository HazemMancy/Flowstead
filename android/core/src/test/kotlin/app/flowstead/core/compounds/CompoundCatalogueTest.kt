/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.compounds

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CompoundCatalogueTest {
    private val catalogue by lazy {
        val dir = System.getProperty("flowstead.sharedDir") ?: error("flowstead.sharedDir not set")
        CompoundCatalogue.parse(File(dir, "compounds/catalogue.json").readText())
    }

    @Test
    fun theWholeDwsimLibraryIsThere() {
        assertTrue(catalogue.all.size > 1400, "only ${catalogue.all.size} compounds")
        assertEquals(catalogue.all.size, catalogue.all.map { it.name }.toSet().size, "names are unique")
        assertTrue(listOf("ChemSep", "CoolProp", "ChEDL Thermo").all { it in catalogue.databases })
        assertEquals(CompoundCatalogue.FAMILY_ORDER.first(), catalogue.families.first())
    }

    @Test
    fun constantsAreSi() {
        val methane = assertNotNull(catalogue["Methane"])
        assertEquals("CH4", methane.formula)
        assertEquals(190.56, assertNotNull(methane.criticalTemperatureK), 0.01)
        assertEquals(4.599e6, assertNotNull(methane.criticalPressurePa), 1.0)
        assertEquals(9.86e-5, assertNotNull(methane.criticalVolumeM3PerMol), 1e-8)
        assertEquals(-74_520.0, assertNotNull(methane.idealGasEnthalpyOfFormationJPerMol), 1.0)
        assertEquals("Paraffins", methane.family)
        assertEquals("H2O", catalogue["Water"]?.formula) // DWSIM writes HOH
        assertEquals("Aromatics", catalogue["Toluene"]?.family)
    }

    @Test
    fun searchRanksShorthandNamesAndCasFirst() {
        assertEquals("Carbon dioxide", catalogue.search("co2").first().name)
        assertEquals("N-butane", catalogue.search("nC4").first().name)
        assertEquals("N-butane", catalogue.search("butane").first().name)
        assertEquals("Triethylene glycol", catalogue.search("TEG").first().name)
        assertEquals("Water", catalogue.search("7732-18-5").first().name)
        assertTrue(catalogue.search("zzzz").isEmpty())
    }

    @Test
    fun filters() {
        val common = catalogue.search("", family = CompoundCatalogue.COMMON)
        assertTrue(common.all { it.common } && common.any { it.name == "Methane" })
        assertTrue(catalogue.search("", family = "Aromatics").all { it.family == "Aromatics" })
        assertTrue(catalogue.search("", database = "CoolProp").all { it.database == "CoolProp" })
        assertTrue(catalogue.search("").first().common, "common compounds lead an empty search")
    }
}
