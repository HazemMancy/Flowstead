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
        // A product of a unit a backward specification can't cross (a mixer's outlet: which feed would change?).
        val mix = Flowsheet(propertyPackage = "Peng-Robinson (PR)", compounds = listOf("Methane"), objects = listOf(
            SimObject("f", "F", ObjectType.MaterialStream), SimObject("f2", "F2", ObjectType.MaterialStream), SimObject("x", "MIX-100", ObjectType.Mixer),
            SimObject("g", "OUT", ObjectType.MaterialStream, specs = mapOf(SpecKey.TEMPERATURE to Spec(300.0, "K"))),
        ), connections = listOf(Connection("f", 0, "x", 0), Connection("f2", 0, "x", 1), Connection("x", 0, "g", 0)))
        assertTrue(!Backward.canTake(mix, mix.obj("g")!!))
        assertTrue((Specification.status(mix, mix.obj("g")!!) as SpecStatus.Invalid).problem.contains("Only the product of a valve"))
    }

    // ---------- widened (D154) ----------

    private fun separator(feedSpecs: Map<String, Spec>, gasSpecs: Map<String, Spec>, gasX: Map<String, Double> = emptyMap(), type: ObjectType = ObjectType.Separator) = Flowsheet(
        propertyPackage = "Peng-Robinson (PR)", compounds = listOf("Methane", "Ethane"),
        objects = listOf(
            SimObject("f", "WELL", ObjectType.MaterialStream, specs = feedSpecs, composition = x),
            SimObject("v", "CHOKE", ObjectType.Valve, specs = mapOf(SpecKey.OUTLET_PRESSURE to Spec(40e5, "Pa"))),
            SimObject("m", "MID", ObjectType.MaterialStream),
            SimObject("s", "V-100", type, specs = if (type == ObjectType.Splitter) mapOf(SpecKey.SPLIT_1 to Spec(0.3, "")) else emptyMap()),
            SimObject("g", "GAS", ObjectType.MaterialStream, specs = gasSpecs, composition = gasX),
            SimObject("l", "LIQ", ObjectType.MaterialStream),
        ),
        connections = listOf(Connection("f", 0, "v", 0), Connection("v", 0, "m", 0), Connection("m", 0, "s", 0), Connection("s", 0, "g", 0), Connection("s", 1, "l", 0)),
    )

    @Test
    fun aSeparatorsProductFindsTheFeedsTemperatureAndFlowAcrossTheChoke() {
        val fs = separator(mapOf(SpecKey.PRESSURE to Spec(70e5, "Pa")), mapOf(SpecKey.TEMPERATURE to Spec(263.15, "K"), SpecKey.MOLAR_FLOW to Spec(90.0, "mol/s")))
        val plan = Backward.plan(fs, fs.obj("g")!!)!!
        assertNull(plan.problem)
        assertEquals("f", plan.feed?.id)
        assertEquals(listOf("V-100", "CHOKE"), plan.units.map { it.tag })
        // The separator does not keep the flow: a target on the feed's flow, not a copy.
        assertEquals(emptyMap(), plan.copied)
        assertEquals(setOf(SpecKey.TEMPERATURE to SpecKey.TEMPERATURE, SpecKey.MOLAR_FLOW to SpecKey.MOLAR_FLOW), plan.targets.map { it.productKey to it.feedKey }.toSet())
        assertEquals(180.0, Backward.guess(plan, plan.targets.first { it.feedKey == SpecKey.MOLAR_FLOW }), 1e-9) // twice the product's
        // The feed counts as complete for the readiness checks, and the product as given.
        assertIs<SpecStatus.Complete>(Specification.status(fs, fs.obj("g")!!))
        assertTrue(Readiness.of(fs).anyReady)
    }

    @Test
    fun aSplitterKeepsTheCompositionButNotTheFlow_AndASeparatorKeepsNeither() {
        val feed = mapOf(SpecKey.PRESSURE to Spec(70e5, "Pa"), SpecKey.TEMPERATURE to Spec(300.0, "K"))
        // A splitter: the composition copies through it to the feed, the flow is a target.
        val tee = separator(feed, mapOf(SpecKey.MOLAR_FLOW to Spec(30.0, "mol/s")), gasX = x, type = ObjectType.Splitter)
            .let { it.copy(objects = it.objects.map { o -> if (o.id == "f") o.copy(composition = emptyMap()) else o }) }
        val tp = Backward.plan(tee, tee.obj("g")!!)!!
        assertNull(tp.problem)
        assertEquals(x, tp.composition)
        assertEquals(listOf(SpecKey.MOLAR_FLOW), tp.targets.map { it.feedKey })
        // A separator changes the composition: refused, with the unit named.
        val sep = separator(feed, emptyMap(), gasX = x).let { it.copy(objects = it.objects.map { o -> if (o.id == "f") o.copy(composition = emptyMap()) else o }) }
        assertTrue(Backward.plan(sep, sep.obj("g")!!)!!.problem!!.contains("V-100 changes the composition"))
        // Its outlets are each one phase: no vapour-fraction target.
        val vf = separator(mapOf(SpecKey.PRESSURE to Spec(70e5, "Pa"), SpecKey.MOLAR_FLOW to Spec(100.0, "mol/s")), mapOf(SpecKey.VAPOUR_FRACTION to Spec(1.0, "")))
        assertTrue(Backward.plan(vf, vf.obj("g")!!)!!.problem!!.contains("one phase"))
        // A second feed into the separator: which one would change? Refused.
        val two = sep.copy(objects = sep.objects + SimObject("f2", "F2", ObjectType.MaterialStream), connections = sep.connections + Connection("f2", 0, "s", 1))
        assertTrue(Backward.plan(two, two.obj("g")!!)!!.problem!!.contains("more than one feed"))
    }

    @Test
    fun eachSideOfAHeatExchangerIsItsOwnLine_AndAnEnthalpyTargetVariesTheTemperature() {
        val fs = Flowsheet(propertyPackage = "Peng-Robinson (PR)", compounds = listOf("Methane", "Ethane"), objects = listOf(
            SimObject("a", "HOT", ObjectType.MaterialStream, specs = mapOf(SpecKey.TEMPERATURE to Spec(400.0, "K"), SpecKey.PRESSURE to Spec(30e5, "Pa"), SpecKey.MASS_FLOW to Spec(5.0, "kg/s")), composition = x),
            SimObject("b", "COLD", ObjectType.MaterialStream, specs = mapOf(SpecKey.PRESSURE to Spec(30e5, "Pa"), SpecKey.MASS_FLOW to Spec(5.0, "kg/s")), composition = x),
            SimObject("e", "E-100", ObjectType.HeatExchanger, specs = mapOf(SpecKey.DUTY to Spec(3e5, "W"))),
            SimObject("ao", "HOT OUT", ObjectType.MaterialStream),
            SimObject("bo", "COLD OUT", ObjectType.MaterialStream, specs = mapOf(SpecKey.MASS_ENTHALPY to Spec(1e4, "J/kg"))),
        ), connections = listOf(Connection("a", 0, "e", 0), Connection("b", 0, "e", 1), Connection("e", 0, "ao", 0), Connection("e", 1, "bo", 0)))
        val plan = Backward.plan(fs, fs.obj("bo")!!)!!
        assertNull(plan.problem)
        assertEquals("b", plan.feed?.id) // side B's own feed, not side A's
        assertEquals(listOf(Backward.Target(SpecKey.MASS_ENTHALPY, SpecKey.TEMPERATURE, 1e4)), plan.targets)
        // An exchanger that fixes an outlet temperature leaves nothing to find.
        val fixed = fs.copy(objects = fs.objects.map { if (it.id == "e") it.copy(specs = mapOf(SpecKey.COLD_OUTLET_TEMPERATURE to Spec(330.0, "K"))) else it })
        assertTrue(Backward.plan(fixed, fixed.obj("bo")!!)!!.problem!!.contains("fixes its outlet temperature"))
    }
}
