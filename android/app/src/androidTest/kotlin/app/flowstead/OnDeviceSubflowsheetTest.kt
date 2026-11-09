/*
 * Flowstead (D132, D133, D135): sub-flowsheets solved by the engine on the device.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 *
 * The same checks as the browser test 4m, on the phone's engine:
 * (a) benchmark 07 (a recycle loop) with half its units in a sub-flowsheet gives the flat case's numbers;
 * (b) water heated from 25 to 150 °C at 5 bar in a sub-flowsheet on the steam tables, fed from and returned to a
 *     Peng–Robinson flowsheet: the duty is the IAPWS-IF97 region-1 enthalpy rise (computed here from the release's
 *     equations, checked on its verification values), the boundary streams cross at the same T, P and flow, mass is
 *     conserved, in three engine solves.
 */
package app.flowstead

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.flowstead.core.api.SolveResult
import app.flowstead.core.api.SolveStatus
import app.flowstead.core.model.Connection
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.FlowsheetJson
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.SimObject
import app.flowstead.core.model.Spec
import app.flowstead.core.model.SubFluid
import app.flowstead.core.model.SubPort
import app.flowstead.core.model.Subflowsheet
import app.flowstead.core.model.SubflowsheetSolve
import app.flowstead.core.model.Subflowsheets
import app.flowstead.data.engine.EngineResult
import app.flowstead.data.engine.local.EmbeddedLocalEngine
import app.flowstead.data.engine.local.LocalEngineClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class OnDeviceSubflowsheetTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
    private val client = LocalEngineClient(EmbeddedLocalEngine(context))

    private var solves = 0
    private val run: suspend (Flowsheet) -> SolveResult = { f ->
        solves++
        when (val r = client.solve("on-device", null, f)) {
            is EngineResult.Ok -> r.value
            is EngineResult.Failed -> error("engine: ${r.reason}")
        }
    }

    @Test
    fun groupedEqualsFlatOnBenchmark07() = runBlocking {
        assumeTrue("build without the on-device engine", BuildConfig.LOCAL_ENGINE)
        val input = FlowsheetJson.parseToJsonElement(testAssets.open("benchmarks/07-recycle-loop/input.json").bufferedReader().use { it.readText() }).jsonObject
        val flat = FlowsheetJson.decodeFromJsonElement(Flowsheet.serializer(), input.getValue("flowsheet"))
        val a = run(flat)
        // Half the units (the right-hand half), with the streams that only join them.
        val units = flat.objects.filter { !it.type.isStream }.sortedBy { it.x }
        val chosen = units.drop(units.size / 2).map { it.id }.toMutableSet()
        flat.objects.filter { it.type.isStream }.forEach { s ->
            val ends = flat.connections.filter { it.fromObject == s.id || it.toObject == s.id }.map { if (it.fromObject == s.id) it.toObject else it.fromObject }
            if (ends.isNotEmpty() && ends.all { it in chosen }) chosen += s.id
        }
        val (grouped, _) = Subflowsheets.group(flat, chosen, 0.0, 0.0)
        val b = SubflowsheetSolve.solve(grouped, run)
        assertEquals(SolveStatus.Converged, a.status); assertEquals(SolveStatus.Converged, b.status)
        var worst = 0.0; var compared = 0
        for ((id, r) in a.objects) for ((k, v) in r.properties) {
            val w = b.objects[id]?.properties?.get(k) ?: error("grouped case has no $k for $id")
            compared++
            worst = max(worst, abs(w - v) / max(abs(v), 1e-9))
        }
        assertTrue(compared > 50 && worst < 1e-7, "grouped vs flat: $compared values, worst $worst")
    }

    // IAPWS-IF97 region 1: h = R T τ γτ, π = p / 16.53 MPa, τ = 1386 K / T.
    private val i1 = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 3, 3, 3, 4, 4, 4, 5, 8, 8, 21, 23, 29, 30, 31, 32)
    private val j1 = intArrayOf(-2, -1, 0, 1, 2, 3, 4, 5, -9, -7, -1, 0, 1, 3, -3, 0, 1, 3, 17, -4, 0, 6, -5, -2, 10, -8, -11, -6, -29, -31, -38, -39, -40, -41)
    private val n1 = doubleArrayOf(0.14632971213167, -0.84548187169114, -0.37563603672040e1, 0.33855169168385e1, -0.95791963387872, 0.15772038513228,
        -0.16616417199501e-1, 0.81214629983568e-3, 0.28319080123804e-3, -0.60706301565874e-3, -0.18990068218419e-1, -0.32529748770505e-1,
        -0.21841717175414e-1, -0.52838357969930e-4, -0.47184321073267e-3, -0.30001780793026e-3, 0.47661393906987e-4, -0.44141845330846e-5,
        -0.72694996297594e-15, -0.31679644845054e-4, -0.28270797985312e-5, -0.85205128120103e-9, -0.22425281908000e-5, -0.65171222895601e-6,
        -0.14341729937924e-12, -0.40516996860117e-6, -0.12734301741641e-8, -0.17424871230634e-9, -0.68762131295531e-18, 0.14478307828521e-19,
        0.26335781662795e-22, -0.11947622640071e-22, 0.18228094581404e-23, -0.93537087292458e-25)
    private fun h1(t: Double, p: Double): Double {
        val pi = p / 16.53e6; val tau = 1386.0 / t
        var g = 0.0
        for (i in 0 until 34) g += n1[i] * (7.1 - pi).pow(i1[i]) * j1[i] * (tau - 1.222).pow(j1[i] - 1)
        return 0.461526 * t * tau * g // kJ/kg
    }

    @Test
    fun steamTablesBlockMatchesIapwsIf97() = runBlocking {
        assumeTrue("build without the on-device engine", BuildConfig.LOCAL_ENGINE)
        // The release's verification values (Table 5).
        listOf(Triple(300.0, 3e6, 115.331273), Triple(300.0, 80e6, 184.142828), Triple(500.0, 3e6, 975.542239)).forEach { (t, p, h) ->
            assertTrue(abs(h1(t, p) - h) < 1e-5, "IF97 region 1 at $t K, $p Pa: ${h1(t, p)} for $h")
        }
        val k = 273.15; val p = 5e5; val m = 1.0
        val fs = Flowsheet(
            propertyPackage = "Peng-Robinson (PR)", compounds = listOf("Water"),
            objects = listOf(
                SimObject("feed", "FEED", ObjectType.MaterialStream, specs = mapOf("temperature" to Spec(25 + k, "C"), "pressure" to Spec(p, "bara"), "massFlow" to Spec(m, "kg/s")), composition = mapOf("Water" to 1.0)),
                SimObject("prod", "PRODUCT", ObjectType.MaterialStream, 480.0, 0.0),
                SimObject("in", "IN", ObjectType.MaterialStream, parent = "blk"),
                SimObject("h", "H-100", ObjectType.Heater, 120.0, 0.0, parent = "blk", specs = mapOf("outletTemperature" to Spec(150 + k, "C"), "pressureDrop" to Spec(0.0, "bar"))),
                SimObject("out", "OUT", ObjectType.MaterialStream, 240.0, 0.0, parent = "blk"),
            ),
            connections = listOf(Connection("in", 0, "h", 0), Connection("h", 0, "out", 0)),
            subflowsheets = listOf(Subflowsheet("blk", "STEAM", 240.0, 0.0, fluid = SubFluid("Steam Tables (IAPWS-IF97)", listOf("Water")),
                ports = listOf(SubPort("in", "feed"), SubPort("out", "prod")))),
        )
        solves = 0
        val r = SubflowsheetSolve.solve(fs, run)
        assertEquals(SolveStatus.Converged, r.status, r.messages.joinToString { it.text })
        val prop = { id: String, key: String -> r.objects.getValue(id).properties.getValue(key) }
        val dutyRef = m * (h1(150 + k, p) - h1(25 + k, p)) * 1000
        assertTrue(abs(prop("h", "duty") - dutyRef) / dutyRef < 5e-4, "duty ${prop("h", "duty")} W; IAPWS-IF97 $dutyRef W")
        for ((a, b) in listOf("feed" to "in", "out" to "prod")) for (key in listOf("temperature", "pressure"))
            assertTrue(abs(prop(a, key) - prop(b, key)) <= 1e-9 * abs(prop(a, key)), "$b $key ${prop(b, key)} ≠ $a ${prop(a, key)}")
        assertTrue(abs(prop("in", "massFlow") - m) < 1e-6 && abs(prop("prod", "massFlow") - m) < 1e-6, "mass flows ${prop("in", "massFlow")}, ${prop("prod", "massFlow")}")
        assertTrue(abs(prop("out", "temperature") - (150 + k)) < 0.01, "OUT at ${prop("out", "temperature")} K")
        assertEquals(3, solves, "engine solves")
    }
}
