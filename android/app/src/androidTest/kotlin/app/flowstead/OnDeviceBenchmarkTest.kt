/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.flowstead.core.api.SolveStatus
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.FlowsheetJson
import app.flowstead.data.engine.EngineResult
import app.flowstead.data.engine.local.EmbeddedLocalEngine
import app.flowstead.data.engine.local.LocalEngineClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DWSIM on the device (decision D25): every benchmark solved by the embedded engine must meet the
 * same expected.json checks as the server (engine/tests BenchmarkTests), within the same tolerances.
 * Runs on an emulator in CI (android.yml, job "device").
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceBenchmarkTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
    private val engine = EmbeddedLocalEngine(context)
    private val client = LocalEngineClient(engine)

    private fun read(path: String) = testAssets.open(path).bufferedReader().use { it.readText() }

    @Test
    fun everyBenchmarkMeetsItsExpectedValues() = runBlocking {
        assumeTrue("build without the on-device engine", BuildConfig.LOCAL_ENGINE)
        val t0 = System.nanoTime()
        val health = client.health("on-device", null)
        assertTrue(health is EngineResult.Ok, "engine did not start: $health")
        Log.i(TAG, "engine ready in ${(System.nanoTime() - t0) / 1_000_000} ms: ${health.value}")

        val cases = testAssets.list("benchmarks")!!.filter { it.first().isDigit() }.sorted()
        assertTrue(cases.size >= 8, "benchmarks found: $cases")
        val failures = mutableListOf<String>()
        for (case in cases) {
            val input = FlowsheetJson.parseToJsonElement(read("benchmarks/$case/input.json")).jsonObject
            val flowsheet = FlowsheetJson.decodeFromJsonElement(Flowsheet.serializer(), input.getValue("flowsheet"))
            val t = System.nanoTime()
            val r = client.solve("on-device", null, flowsheet)
            val ms = (System.nanoTime() - t) / 1_000_000
            if (r !is EngineResult.Ok) { failures += "$case: ${(r as EngineResult.Failed).reason}"; continue }
            Log.i(TAG, "$case: ${r.value.status} in $ms ms (engine ${r.value.elapsedMs} ms)")
            val expected = FlowsheetJson.parseToJsonElement(read("benchmarks/$case/expected.json")).jsonObject
            val status = expected["status"]?.jsonPrimitive?.contentOrNull ?: "converged"
            if (r.value.status != SolveStatus.valueOf(status.replaceFirstChar { it.uppercase() })) {
                failures += "$case: status ${r.value.status}, expected $status (${r.value.messages.joinToString { it.text }})"
                continue
            }
            val result = FlowsheetJson.encodeToJsonElement(app.flowstead.core.api.SolveResult.serializer(), r.value)
            expected["checks"]?.jsonArray?.forEach { check(case, it.jsonObject, result, failures) }
            expected["reference"]?.jsonArray?.forEach { ref ->
                if (ref.jsonObject["blocking"]?.jsonPrimitive?.boolean == true) check(case, ref.jsonObject, result, failures)
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private fun check(case: String, c: JsonObject, result: JsonElement, failures: MutableList<String>) {
        val path = c.getValue("path").jsonPrimitive.content
        val expected = c.getValue("expected").jsonPrimitive.double
        var actual = lookup(result, path)
        fun paths(e: JsonElement?) = when (e) {
            is JsonArray -> e.map { it.jsonPrimitive.content }
            is JsonPrimitive -> listOf(e.content)
            else -> emptyList()
        }
        for (m in paths(c["minus"])) actual = actual?.let { a -> lookup(result, m)?.let { a - it } }
        for (p in paths(c["plus"])) actual = actual?.let { a -> lookup(result, p)?.let { a + it } }
        if (actual == null) { failures += "$case $path: missing"; return }
        val tol = c.getValue("tol").jsonObject
        val allowed = max(tol["abs"]?.jsonPrimitive?.doubleOrNull ?: 0.0, (tol["rel"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * abs(expected))
        if (abs(actual - expected) > allowed) failures += "$case $path: got $actual, expected $expected ± $allowed"
    }

    /** Same path rules as the engine harness: a composition key may contain dots. */
    private fun lookup(root: JsonElement, path: String): Double? {
        var parts = path.split('.')
        val at = parts.indexOf("composition")
        if (at >= 0 && at < parts.size - 1) parts = parts.take(at + 1) + parts.drop(at + 1).joinToString(".")
        var node: JsonElement? = root
        for (p in parts) node = (node as? JsonObject)?.get(p) ?: return null
        return (node as? JsonPrimitive)?.doubleOrNull
    }

    private companion object {
        const val TAG = "FlowsteadOnDevice"
    }
}
