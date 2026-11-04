/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.api.MessageLevel
import app.flowstead.core.api.ObjectResult
import app.flowstead.core.api.ObjectStatus
import app.flowstead.core.api.PhaseResult
import app.flowstead.core.api.SolveResult
import app.flowstead.core.api.SolveStatus
import app.flowstead.core.api.SolverMessage
import app.flowstead.core.compounds.PubChem
import app.flowstead.core.compounds.PubChemCompound
import app.flowstead.core.export.Report
import app.flowstead.core.units.UnitSet
import kotlin.math.exp
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpreadsheetTest {
    private val results = mapOf(("K-100" to "power") to 250.0, ("1" to "temperature") to 40.0)
    private fun eval(vararg cells: Pair<String, String>) = SpreadsheetEval.evaluate(Spreadsheet(cells.toMap())) { t, k -> results[t to k] }

    @Test
    fun arithmeticReferencesRangesAndImports() {
        val v = eval(
            "A1" to "2", "A2" to "=A1*3+1", "A3" to "=(A1+A2)^2/4", "A4" to "=sum(A1:A3)", "B1" to "=[K-100.power]/1000",
            "B2" to "=sqrt(16)+max(1, 5, 3)+ln(exp(2))", "B3" to "Notes", "B4" to "=-A1+pi*0", "C1" to "=average(A1:A2)",
        )
        assertEquals(7.0, (v["A2"] as CellValue.Number).value)
        assertEquals(20.25, (v["A3"] as CellValue.Number).value)
        assertEquals(29.25, (v["A4"] as CellValue.Number).value)
        assertEquals(0.25, (v["B1"] as CellValue.Number).value)
        assertEquals(11.0, (v["B2"] as CellValue.Number).value, 1e-12)
        assertEquals("Notes", (v["B3"] as CellValue.Text).text)
        assertEquals(-2.0, (v["B4"] as CellValue.Number).value)
        assertEquals(4.5, (v["C1"] as CellValue.Number).value)
    }

    @Test
    fun errorsAreReportedNotGuessed() {
        val v = eval("A1" to "=1/0", "A2" to "=A3", "A3" to "=A2", "A4" to "=[V-9.duty]", "A5" to "=A1+1", "A6" to "=foo(1)", "A7" to "=sqrt(-1)")
        assertEquals("division by zero", (v["A1"] as CellValue.Error).reason)
        assertIs<CellValue.Error>(v["A2"]); assertIs<CellValue.Error>(v["A3"])
        assertTrue((v["A4"] as CellValue.Error).reason.contains("no solved duty for V-9"))
        assertIs<CellValue.Error>(v["A5"]) // an error propagates
        assertIs<CellValue.Error>(v["A6"]); assertIs<CellValue.Error>(v["A7"])
    }
}

class PidTuningTest {
    @Test
    fun recoversAFirstOrderPlusDeadTimeProcess() {
        // K = 2 %/%, τ = 50 s, θ = 10 s; a 5 % output step at t = 20 s, sampled every second.
        val k = 2.0; val tau = 50.0; val theta = 10.0; val du = 5.0; val y0 = 40.0
        val t = (0..400).map { it.toDouble() }
        val y = t.map { if (it <= 20 + theta) y0 else y0 + k * du * (1 - exp(-(it - 20 - theta) / tau)) }
        val m = assertNotNull(PidTuning.fit(t, y, 20.0, du))
        assertEquals(k, m.gain, 0.01)
        assertEquals(tau, m.timeConstant, 0.5)
        assertEquals(theta, m.deadTime, 0.5)
        assertEquals(-1, PidTuning.action(m)) // positive process gain: reverse acting
        // SIMC with τc = θ: Kc = τ/(K·2θ) = 1.25, τI = min(τ, 8θ) = 50; Ziegler–Nichols PID: 1.2τ/(Kθ) = 3, 2θ, θ/2.
        val s = PidTuning.settings(Fopdt(k, tau, theta)).associateBy { it.rule }
        assertEquals(1.25, s.getValue("SIMC PI").gain, 1e-12)
        assertEquals(50.0, s.getValue("SIMC PI").integralTime, 1e-12)
        assertEquals(3.0, s.getValue("Ziegler–Nichols PID").gain, 1e-12)
        assertEquals(20.0, s.getValue("Ziegler–Nichols PID").integralTime, 1e-12)
        assertEquals(5.0, s.getValue("Ziegler–Nichols PID").derivativeTime, 1e-12)
        // Cohen–Coon PI at θ/τ = 0.2: Kc = (τ/(Kθ))(0.9 + θ/12τ), τI = θ(30 + 3θ/τ)/(9 + 20θ/τ).
        assertEquals(2.5 * (0.9 + 0.2 / 12), s.getValue("Cohen–Coon PI").gain, 1e-12)
        assertEquals(10 * (30 + 0.6) / (9 + 4), s.getValue("Cohen–Coon PI").integralTime, 1e-12)
    }

    @Test
    fun anUnsettledResponseIsNotFitted() {
        val t = (0..100).map { it.toDouble() }
        assertNull(PidTuning.fit(t, t.map { it * 0.5 }, 10.0, 5.0)) // a ramp never settles
        assertNull(PidTuning.fit(t, t.map { 40.0 }, 10.0, 5.0)) // nothing moved
    }
}

class McCabeThieleTest {
    private val curve = McCabeThiele.constantAlpha(2.5, 2001)

    @Test
    fun minimumStagesAreFenskes() {
        // Fenske: N_min = ln[(xD/(1−xD))·((1−xB)/xB)] / ln α, the reboiler included.
        val fenske = ln((0.95 / 0.05) * (0.95 / 0.05)) / ln(2.5)
        assertEquals(fenske, McCabeThiele.minimumStages(curve, 0.95, 0.05), 0.05)
    }

    @Test
    fun minimumRefluxIsUnderwoodsForASaturatedLiquidFeed() {
        // Underwood with constant α and q = 1: R_min = [xD/zF − α(1 − xD)/(1 − zF)] / (α − 1) = 1.1.
        val underwood = (0.95 / 0.5 - 2.5 * 0.05 / 0.5) / 1.5
        assertEquals(underwood, McCabeThiele.minimumReflux(curve, 0.95, 0.5, 1.0)!!, 1e-3)
    }

    @Test
    fun stepsAtAWorkingReflux() {
        val r = assertNotNull(McCabeThiele.solve(curve, 0.95, 0.05, 0.5, 1.0, 1.5 * 1.1))
        assertTrue(r.theoreticalStages > r.minimumStages && r.theoreticalStages < 30, "${r.theoreticalStages}")
        assertTrue(r.feedStage in 2 until r.stages.size)
        assertTrue(r.stages.last().x <= 0.05)
        // Each stage sits on the equilibrium curve.
        r.stages.forEach { assertEquals(2.5 * it.x / (1 + 1.5 * it.x), it.y, 2e-3) }
        // Below the minimum reflux there is no column.
        assertTrue(McCabeThiele.solve(curve, 0.95, 0.05, 0.5, 1.0, 1.0)!!.stages.isEmpty())
    }

    @Test
    fun operatingLinesMeetOnTheQLine() {
        // By hand, R = 3, xD = 0.95, zF = 0.5. A saturated liquid (q = 1): x = zF, y = (3·0.5 + 0.95)/4 = 0.6125.
        val liquid = assertNotNull(McCabeThiele.solve(curve, 0.95, 0.05, 0.5, 1.0, 3.0)).intersection!!
        assertEquals(0.5, liquid.first, 1e-12)
        assertEquals(0.6125, liquid.second, 1e-12)
        // A saturated vapour (q = 0): y = zF = 0.5, x = (0.5 − 0.95/4)/(3/4) = 0.35.
        val vapour = assertNotNull(McCabeThiele.solve(curve, 0.95, 0.05, 0.5, 0.0, 3.0)).intersection!!
        assertEquals(0.35, vapour.first, 1e-12)
        assertEquals(0.5, vapour.second, 1e-12)
        // The diagram draws the stripping line from the bottoms to that point.
        val plot = McCabeThiele.plot(McCabeThiele.solve(curve, 0.95, 0.05, 0.5, 0.0, 3.0)!!, 0.95, 0.05, 3.0, "A")
        val strip = plot.series.first { it.label == "Stripping line" }
        assertEquals(0.05, strip.x.first(), 1e-12)
        assertEquals(0.35, strip.x.last(), 1e-12)
        assertEquals(0.5, strip.y.last(), 1e-12)
    }
}

class TrayHydraulicsTest {
    @Test
    fun segmentGeometry() {
        // A chord at 0.77 D cuts off 12.3 % of the area: (θ − sin θ)/2π with θ = 2·asin(0.77).
        assertEquals(0.1232, TrayHydraulics.downcomerFraction(1.0, 0.77), 2e-4)
        assertEquals(0.77, TrayHydraulics.weirLength(1.0, TrayHydraulics.downcomerFraction(1.0, 0.77)), 1e-6)
    }

    @Test
    fun oneTrayByHand() {
        // A three-stage column (condenser, one tray, reboiler); the tray carries 100 mol/s of vapour (M 60, 3 kg/m³) and
        // 90 mol/s of liquid (M 70, 700 kg/m³, 20 mN/m).
        val p = mapOf(
            "vapourFlow" to listOf(0.0, 100.0, 0.0), "liquidFlow" to listOf(0.0, 90.0, 0.0),
            "vapourDensity" to listOf(3.0, 3.0, 3.0), "liquidDensity" to listOf(700.0, 700.0, 700.0),
            "vapourMolarMass" to listOf(60.0, 60.0, 60.0), "liquidMolarMass" to listOf(70.0, 70.0, 70.0),
            "surfaceTension" to listOf(0.02, 0.02, 0.02),
        )
        val layout = TrayLayout(diameter = 1.5)
        val r = assertNotNull(TrayHydraulics.rate(ObjectType.DistillationColumn, p, layout))
        val t = r.trays.single()
        val ac = Math.PI * 1.5 * 1.5 / 4; val ad = 0.12 * ac; val aa = ac - 2 * ad; val ah = 0.1 * aa
        val wv = 6.0; val wl = 6.3
        val how = 750 * Math.pow(wl / (700 * r.weirLength), 2.0 / 3) / 1000
        val hd = 51 * Math.pow(wv / 3 / ah / 0.84, 2.0) * 3 / 700 / 1000
        val hr = 12.5 / 700
        assertEquals(how, t.weirCrest, 1e-12)
        assertEquals(hd, t.dryPlate, 1e-12)
        assertEquals(hr, t.residual, 1e-12)
        assertEquals(hd + 0.05 + how + hr, t.totalHead, 1e-12)
        assertEquals((hd + 0.05 + how + hr) * 700 * 9.81, t.pressureDrop, 1e-9)
        val hdc = 166 * Math.pow(wl / (700 * 0.040 * r.weirLength), 2.0) / 1000
        assertEquals(0.05 + how + t.totalHead + hdc, t.downcomerBackup, 1e-12)
        assertEquals(ad * t.downcomerBackup * 700 / wl, t.downcomerResidence, 1e-9)
        // A typical sieve tray loses well under 1.5 kPa.
        assertTrue(t.pressureDrop in 300.0..1500.0, "${t.pressureDrop}")
    }
}

class FlowsheetToolsTest {
    private fun feed(id: String, tag: String = id) = SimObject(
        id, tag, ObjectType.MaterialStream,
        specs = mapOf(SpecKey.TEMPERATURE to Spec(300.0, "K"), SpecKey.PRESSURE to Spec(5e6, "Pa"), SpecKey.MOLAR_FLOW to Spec(10.0, "mol/s")),
        composition = mapOf("Methane" to 1.0),
    )

    private val fs = Flowsheet(
        propertyPackage = "Peng-Robinson (PR)", compounds = listOf("Methane", "Ethane"),
        objects = listOf(feed("a", "1"), SimObject("v", "V-100", ObjectType.Valve, specs = mapOf(SpecKey.OUTLET_PRESSURE to Spec(2e6, "Pa"))), SimObject("b", "2", ObjectType.MaterialStream), feed("c", "1")),
        connections = listOf(Connection("a", 0, "v", 0), Connection("v", 0, "b", 0)),
        reactions = listOf(Reaction("r1", "Unused", ReactionType.Conversion, mapOf("Methane" to -1.0, "Ethane" to 0.5), "Methane", conversion = 0.5)),
    )

    private fun solved(f: Flowsheet, objects: Map<String, ObjectResult>, messages: List<SolverMessage> = emptyList()) =
        ResultView(StoredSolve(SolveResult(SolveStatus.Converged, dwsimVersion = "10.2.12", messages = messages, objects = objects), f.modelHash(), 0), f.modelHash())

    @Test
    fun theCheckFindsLooseEnds() {
        val results = solved(fs, mapOf("b" to ObjectResult(ObjectStatus.Solved, properties = mapOf("temperature" to 290.0))),
            listOf(SolverMessage(MessageLevel.Warning, "Liquid at the outlet", "v")))
        val items = FlowsheetCheck.run(fs, results)
        assertTrue(items.any { it.objectId == "c" && "not connected" in it.text })
        assertTrue(items.any { it.level == CheckLevel.Warning && "Tag 1 is used 2 times" in it.text })
        assertTrue(items.any { it.level == CheckLevel.Info && "Unused" in it.text })
        assertTrue(items.any { it.level == CheckLevel.Warning && it.objectId == "v" && "Liquid at the outlet" in it.text })
        assertTrue(FlowsheetCheck.run(Flowsheet(), ResultView.NONE).any { it.level == CheckLevel.Error && "No fluid package" in it.text })
    }

    @Test
    fun scenariosKeepSpecificationsAndResults() {
        val results = solved(fs, mapOf("b" to ObjectResult(ObjectStatus.Solved, properties = mapOf("temperature" to 290.0, "pressure" to 2e6))))
        val s = Scenarios.snapshot(fs, results, "Base", 1L)
        assertTrue(s.solved)
        assertEquals(290.0, s.values.getValue(Scenarios.key("b", "temperature")))
        val changed = fs.copy(objects = fs.objects.map { if (it.id == "v") it.copy(specs = mapOf(SpecKey.OUTLET_PRESSURE to Spec(1e6, "Pa"))) else it })
        val back = Scenarios.restore(changed, s)
        assertEquals(2e6, back.obj("v")!!.specs.getValue(SpecKey.OUTLET_PRESSURE).si)
        assertEquals(listOf("b" to "temperature", "b" to "pressure"), Scenarios.rows(fs, listOf(s)).filter { it.first == "b" })
        // Out-of-date results are not kept as a scenario's numbers.
        val stale = ResultView(StoredSolve(SolveResult(SolveStatus.Converged, dwsimVersion = "10.2.12", objects = mapOf("b" to ObjectResult(ObjectStatus.Solved, properties = mapOf("temperature" to 1.0)))), "old", 0), fs.modelHash())
        assertTrue(Scenarios.snapshot(fs, stale, "Old", 2L).values.isEmpty())
    }

    @Test
    fun toolsDoNotChangeTheModelHash() {
        val h = fs.modelHash()
        assertEquals(h, fs.copy(spreadsheet = Spreadsheet(mapOf("A1" to "1")), watch = listOf(WatchItem("b", "temperature")), scenarios = listOf(Scenario("x", 0, fs.objects))).modelHash())
        assertTrue(Readiness.of(fs.copy(scenarios = listOf(Scenario("x", 0, fs.objects)))).solvable.scenarios.isEmpty())
    }

    @Test
    fun reportHasEverySection() {
        val results = solved(fs, mapOf("b" to ObjectResult(ObjectStatus.Solved, properties = mapOf("temperature" to 290.0), phases = mapOf("overall" to PhaseResult(composition = mapOf("Methane" to 1.0))))))
        val md = Report.markdown("Letdown", fs, results, UnitSet.SI, "2026-11-04", "3.2.0")
        listOf("# Letdown", "## Fluid", "## Streams", "## Equipment", "## Flowsheet check", "| Temperature (°C) |").forEach { assertTrue(it in md, it) }
        assertTrue("16.85" in md) // 290 K in °C, the engine's number in the user's unit
    }

    @Test
    fun parametersAndSuggestedReactions() {
        val withPair = FlowsheetEditing.upsertBinaryParameter(fs, BinaryParameter("Methane", "Ethane", 0.0, a12 = 100.0, a21 = 200.0, alpha12 = 0.3))
        assertEquals(1, withPair.interactionParameters.size)
        // Setting kij keeps the pair's NRTL values; given the other way round they are swapped to match.
        val kij = FlowsheetEditing.setInteractionParameter(withPair, "Ethane", "Methane", 0.01).interactionParameters.single()
        assertEquals(Triple("Ethane", 200.0, 100.0), Triple(kij.a, kij.a12, kij.a21))
        assertEquals(0.01, kij.kij)
        val added = FlowsheetEditing.addSuggestedReaction(fs, fs.reactions.single())
        assertEquals(listOf("Unused", "Unused'"), added.reactions.map { it.name })
        assertTrue(added.reactions[1].id != "r1")
    }

    @Test
    fun structureHypotheticals() {
        assertNull(Hypothetical("MyHexane", smiles = "CCCCCC").problem())
        assertNull(Hypothetical("G", jobackGroups = mapOf(1 to 2, 2 to 4)).problem())
        assertNotNull(Hypothetical("G", jobackGroups = mapOf(42 to 1)).problem())
        assertNotNull(Hypothetical("Plain").problem())
        val json = FlowsheetJson.encodeToString(Hypothetical.serializer(), Hypothetical("G", jobackGroups = mapOf(1 to 2)))
        assertTrue("\"jobackGroups\":{\"1\":2}" in json, json)
    }
}

class PubChemTest {
    @Test
    fun readsBothSpellingsOfItsSmiles() {
        val old = """{"PropertyTable":{"Properties":[{"CID":1140,"MolecularFormula":"C7H8","MolecularWeight":"92.14","IsomericSMILES":"CC1=CC=CC=C1","Title":"Toluene"}]}}"""
        val new = """{"PropertyTable":{"Properties":[{"CID":702,"MolecularFormula":"C2H6O","MolecularWeight":"46.07","SMILES":"CCO","ConnectivitySMILES":"CCO","Title":"Ethanol"}]}}"""
        assertEquals(PubChemCompound(1140, "Toluene", "C7H8", 92.14, "CC1=CC=CC=C1"), PubChem.parse(old).single())
        assertEquals("CCO", PubChem.parse(new).single().smiles)
        assertTrue(PubChem.parse("""{"Fault":{"Code":"PUGREST.NotFound","Message":"No CID found"}}""").isEmpty())
        assertEquals("No CID found", PubChem.fault("""{"Fault":{"Code":"PUGREST.NotFound","Message":"No CID found"}}"""))
        assertTrue(PubChem.url("methyl ethyl ketone").endsWith("/compound/name/methyl%20ethyl%20ketone/property/Title,MolecularFormula,MolecularWeight,SMILES,IsomericSMILES,CanonicalSMILES,ConnectivitySMILES/JSON"))
    }
}
