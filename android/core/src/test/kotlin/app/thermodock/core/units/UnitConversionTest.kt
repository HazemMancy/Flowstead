/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.core.units

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UnitConversionTest {

    @Serializable
    private data class Vector(val quantity: Quantity, val unit: String, val value: Double, val si: Double, val patmPa: Double? = null)

    @Serializable
    private data class Vectors(val description: String, val relativeTolerance: Double, val vectors: List<Vector>)

    private val vectors: Vectors by lazy {
        val dir = System.getProperty("thermodock.sharedDir") ?: error("thermodock.sharedDir not set")
        Json.decodeFromString(File(dir, "units/test-vectors.json").readText())
    }

    private fun close(expected: Double, actual: Double, rel: Double) =
        abs(expected - actual) <= rel * max(1.0, abs(expected))

    @Test
    fun sharedVectorsConvertBothWays() {
        val failures = vectors.vectors.mapNotNull { v ->
            val unit = Units.byId(v.unit)
            assertEquals(v.quantity, unit.quantity, "quantity of ${v.unit}")
            val ctx = UnitContext(v.patmPa ?: StandardConditions.DEFAULT_PATM_PA)
            val toSi = unit.toSi(v.value, ctx)
            val back = unit.fromSi(v.si, ctx)
            when {
                !close(v.si, toSi, vectors.relativeTolerance) -> "${v.value} ${v.unit} -> $toSi, expected ${v.si}"
                !close(v.value, back, vectors.relativeTolerance) -> "${v.si} SI -> $back ${v.unit}, expected ${v.value}"
                else -> null
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun everyUnitIsCoveredByASharedVector() {
        val covered = vectors.vectors.map { it.unit }.toSet()
        val uncovered = Units.allUnits.map { it.id }.filterNot { it in covered || it in SI_ONLY_ALLOWED_UNTESTED }
        assertTrue(uncovered.isEmpty(), "Units without a shared test vector: $uncovered")
    }

    @Test
    fun everyUnitRoundTrips() {
        val ctx = UnitContext(95_000.0)
        for (u in Units.allUnits) for (x in listOf(-123.456, 0.0, 1.0, 98_765.4321)) {
            assertTrue(close(x, u.fromSi(u.toSi(x, ctx), ctx), 1e-12), "${u.id} round trip of $x")
        }
    }

    @Test
    fun gaugeAndAbsoluteDifferByAtmosphericReference() {
        val ctx = UnitContext(101_325.0)
        assertEquals(1.01325, convert(0.0, Units.barg, Units.bara, ctx), 1e-12)
        assertEquals(0.0, convert(1.01325, Units.bara, Units.barg, ctx), 1e-12)
        val altitude = UnitContext(84_000.0)
        assertEquals(10.84, convert(10.0, Units.barg, Units.bara, altitude), 1e-12)
        assertEquals(convert(10.0, Units.psig, Units.psia, altitude) * Conversions.PSI_PA,
            10.0 * Conversions.PSI_PA + 84_000.0, 1e-6)
    }

    @Test
    fun differencesIgnoreOffsets() {
        assertEquals(18.0, convert(10.0, Units.dC, Units.dF), 1e-12)
        assertEquals(1.0, convert(100.0, Units.dkPa, Units.dbar), 1e-12)
    }

    @Test
    fun crossQuantityConversionIsRejected() {
        assertFailsWith<IllegalArgumentException> { convert(1.0, Units.C, Units.kPa) }
    }

    @Test
    fun nonPositiveAtmosphereIsRejected() {
        assertFailsWith<IllegalArgumentException> { UnitContext(0.0) }
        assertFailsWith<IllegalArgumentException> { UnitContext(Double.NaN) }
    }

    @Test
    fun standardVolumesMatchIndustryValues() {
        // 23.645 m³/kmol at 15 °C, 1 atm; 379.48 scf/lbmol at 60 °F, 14.696 psia.
        assertEquals(23.645, StandardConditions.metricMolarVolume * 1e3, 1e-3)
        assertEquals(379.48, StandardConditions.fieldMolarVolume * Conversions.LB_KG * 1e3 / Conversions.FT3_M3, 1e-2)
        // 1 MMSCFD ≈ 49.80 kgmol/h
        assertEquals(49.80, convert(1.0, Units.mmscfd, Units.kgmolPerH), 5e-3)
    }

    @Test
    fun presetsCoverEveryQuantityWithMatchingUnits() {
        for (set in UnitSet.PRESETS) for (q in Quantity.entries) {
            assertEquals(q, set.unitFor(q).quantity, "${set.name}/$q")
            assertTrue(q in set.units, "${set.name} misses $q")
        }
    }

    @Test
    fun customSetFallsBackToSiForBadEntries() {
        val bad = UnitSet("Custom", mapOf(Quantity.Temperature to "kPa", Quantity.Pressure to "nope"))
        assertEquals(Units.K, bad.unitFor(Quantity.Temperature))
        assertEquals(Units.Pa, bad.unitFor(Quantity.Pressure))
        assertEquals("barg", UnitSet.SI.with(Quantity.Pressure, Units.barg).unitFor(Quantity.Pressure).id)
        assertFailsWith<IllegalArgumentException> { UnitSet.SI.with(Quantity.Pressure, Units.C) }
    }

    private companion object {
        /** SI base units are identity conversions; they need no vector of their own. */
        val SI_ONLY_ALLOWED_UNTESTED = Quantity.entries.map { it.siUnitId }.toSet() +
            setOf("kJ/kg.K", "cp:Btu/lb.F")
    }
}
