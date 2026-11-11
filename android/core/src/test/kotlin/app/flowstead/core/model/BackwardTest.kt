/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Backward specifications (D151): a product's conditions solved for by its feed upstream. */
class BackwardTest {
    private val x = mapOf("Methane" to 0.9, "Ethane" to 0.1)

    private fun line(feedSpecs: Map<String, Spec>, productSpecs: Map<String, Spec>, valve: Map<String, Spec> = mapOf(SpecKey.OUTLET_PRESSURE to Spec(40e5, "Pa")),
                     productX: Map<String, Double> = emptyMap(), feedX: Map<String, Double> = x) = Flowsheet(
        propertyPackage = "Peng-Robinson (PR)", compounds = listOf("Methane", "Ethane"),
        objects = listOf(
            SimObject("f", "WELL", ObjectType.MaterialStream, specs = feedSpecs, composition = feedX),
            SimObject("v", "CHOKE", ObjectType.Valve, specs = valve),
            SimObject("h", "E-100", ObjectType.Heater, specs = mapOf(SpecKey.DUTY to Spec(1e5, "W"))),
            SimObject("m", "MID", ObjectType.MaterialStream),
            SimObject("p", "SEP-FEED", ObjectType.MaterialStream, specs = productSpecs, composition = productX),
            SimObject("q", "Q", ObjectType.EnergyStream),
        ),
        connections = listOf(Connection("f", 0, "v", 0), Connection("v", 0, "m", 0), Connection("m", 0, "h", 0), Connection("h", 0, "p", 0), Connection("q", 0, "h", 1)),
    )

    @Test
    fun aProductTemperatureMakesItsFeedCompleteAndIsMetByTheFeedsTemperature() {
        val fs = line(mapOf(SpecKey.PRESSURE to Spec(70e5, "Pa"), SpecKey.MOLAR_FLOW to Spec(10.0, "mol/s")), mapOf(SpecKey.TEMPERATURE to Spec(291.15, "K")))
        val plan = Backward.plan(fs, fs.obj("p")!!)!!
        assertNull(plan.problem)
        assertEquals("WELL", plan.feed!!.tag)
        // Back through the heater, then the valve, to the feed.
        assertEquals(listOf("E-100", "CHOKE"), plan.units.map { it.tag })
        assertEquals(listOf(Backward.Target(SpecKey.TEMPERATURE, SpecKey.TEMPERATURE, 291.15)), plan.targets)
        // Without it the well lacks its temperature; with it, nothing is left to give anywhere.
        assertIs<SpecStatus.UnderSpecified>(Specification.status(fs.copy(objects = fs.objects.map { if (it.id == "p") it.copy(specs = emptyMap()) else it }), fs.obj("f")!!))
        assertEquals(SpecStatus.Complete, Specification.status(fs, fs.obj("f")!!))
        assertEquals(SpecStatus.Complete, Specification.status(fs, fs.obj("p")!!))
        assertTrue(Specification.problems(fs).isEmpty(), Specification.problems(fs).toString())
    }

    @Test
    fun aTargetTakesWhatTheFeedLacksAndPressureStartsFromTheDropsAlongTheLine() {
        // The feed has its temperature: the product's temperature is met by the feed's pressure (the valve's JT effect).
        val v = mapOf(SpecKey.PRESSURE_DROP to Spec(30e5, "Pa"))
        val fs = line(mapOf(SpecKey.TEMPERATURE to Spec(303.15, "K"), SpecKey.MOLAR_FLOW to Spec(10.0, "mol/s")),
            mapOf(SpecKey.PRESSURE to Spec(40e5, "Pa")), valve = v)
        val plan = Backward.plan(fs, fs.obj("p")!!)!!
        assertEquals(listOf(Backward.Target(SpecKey.PRESSURE, SpecKey.PRESSURE, 40e5)), plan.targets)
        // 40 bara at the product plus the valve's 30 bar drop: the feed starts at 70 bara.
        assertEquals(70e5, Backward.guess(plan, plan.targets.single()), 1e-6)
    }

    @Test
    fun flowAndCompositionGoFromTheProductToTheFeed() {
        val fs = line(mapOf(SpecKey.TEMPERATURE to Spec(303.15, "K"), SpecKey.PRESSURE to Spec(70e5, "Pa")),
            mapOf(SpecKey.MASS_FLOW to Spec(5.0, "kg/s")), productX = x, feedX = emptyMap())
        val applied = Backward.applied(fs).obj("f")!!
        assertEquals(5.0, applied.specs.getValue(SpecKey.MASS_FLOW).si)
        assertEquals(x, applied.composition)
        assertEquals(SpecStatus.Complete, Specification.status(fs, fs.obj("f")!!))
    }

    @Test
    fun aLineThatCantBeSolvedSaysWhy() {
        // Three state values on one line.
        var fs = line(mapOf(SpecKey.TEMPERATURE to Spec(303.15, "K"), SpecKey.PRESSURE to Spec(70e5, "Pa"), SpecKey.MOLAR_FLOW to Spec(10.0, "mol/s")),
            mapOf(SpecKey.TEMPERATURE to Spec(291.15, "K")))
        assertTrue((Specification.status(fs, fs.obj("p")!!) as SpecStatus.Invalid).problem.contains("over-specified"))
        // The flow on both ends.
        fs = line(mapOf(SpecKey.PRESSURE to Spec(70e5, "Pa"), SpecKey.MOLAR_FLOW to Spec(10.0, "mol/s")), mapOf(SpecKey.MOLAR_FLOW to Spec(10.0, "mol/s")))
        assertTrue((Specification.status(fs, fs.obj("p")!!) as SpecStatus.Invalid).problem.contains("give it on one of them"))
        // A product of a unit a backward specification can't cross (a separator's gas).
        val sep = Flowsheet(propertyPackage = "Peng-Robinson (PR)", compounds = listOf("Methane"), objects = listOf(
            SimObject("f", "F", ObjectType.MaterialStream), SimObject("s", "V-100", ObjectType.Separator),
            SimObject("g", "GAS", ObjectType.MaterialStream, specs = mapOf(SpecKey.TEMPERATURE to Spec(300.0, "K"))),
        ), connections = listOf(Connection("f", 0, "s", 0), Connection("s", 0, "g", 0)))
        assertTrue(!Backward.canTake(sep, sep.obj("g")!!))
        assertTrue((Specification.status(sep, sep.obj("g")!!) as SpecStatus.Invalid).problem.contains("Only the product of a valve"))
    }
}
