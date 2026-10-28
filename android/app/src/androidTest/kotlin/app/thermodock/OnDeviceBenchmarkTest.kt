/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.thermodock.core.api.SolveStatus
import app.thermodock.core.export.DataSheets
import app.thermodock.core.export.HeatMaterialBalance
import app.thermodock.core.model.DocumentInfo
import app.thermodock.core.model.Flowsheet
import app.thermodock.core.model.FlowsheetEditing
import app.thermodock.core.model.FlowsheetJson
import app.thermodock.core.model.LineCriteria
import app.thermodock.core.model.PropertyTables
import app.thermodock.core.model.ResultView
import app.thermodock.core.model.SheetKeys
import app.thermodock.core.model.SizingCriteria
import app.thermodock.core.model.Spec
import app.thermodock.core.model.StoredSolve
import app.thermodock.core.model.modelHash
import app.thermodock.core.model.obj
import app.thermodock.core.units.UnitSet
import app.thermodock.ui.datasheet.DataSheetPdf
import app.thermodock.ui.datasheet.PfdPdf
import app.thermodock.data.engine.EngineResult
import app.thermodock.data.engine.local.EmbeddedLocalEngine
import app.thermodock.data.engine.local.LocalEngineClient
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
            if (case.startsWith("16-")) sampleDataSheets(flowsheet, r.value)
            if (case.startsWith("07-")) sampleProjectDocuments(flowsheet, r.value)
            val result = FlowsheetJson.encodeToJsonElement(app.thermodock.core.api.SolveResult.serializer(), r.value)
            expected["checks"]?.jsonArray?.forEach { check(case, it.jsonObject, result, failures) }
            expected["reference"]?.jsonArray?.forEach { ref ->
                if (ref.jsonObject["blocking"]?.jsonPrimitive?.boolean == true) check(case, ref.jsonObject, result, failures)
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /**
     * Renders the pipe data sheets of the line-sizing benchmark from the device's own solve and logs the PDF
     * (base64, tag ThermoDockPdf) so CI shows a real sample for review.
     */
    private fun sampleDataSheets(input: Flowsheet, solved: app.thermodock.core.api.SolveResult) {
        val fs = FlowsheetEditing.setPipeSize(input, "pg", "8", "40 / STD / 40S").copy(
            documentInfo = DocumentInfo(
                company = "Sample Engineering Ltd", project = "Gas Gathering Expansion", projectNumber = "P-0001",
                client = "Sample Client", site = "Onshore", preparedBy = "ThermoDock", checkedBy = "", approvedBy = "",
            ),
            sizingCriteria = SizingCriteria(
                gas = LineCriteria(maxVelocity = Spec(20.0, "m/s"), maxMomentumFlux = Spec(15000.0, "kg/m.s2"), maxFrictionGradient = Spec(50.0, "kPa/100m")),
                twoPhase = LineCriteria(erosionalC = Spec(100.0, "ratio"), maxMomentumFlux = Spec(10000.0, "kg/m.s2")),
            ),
        ).let { FlowsheetEditing.setSheetField(it, "pg", SheetKeys.SERVICE, "Export gas to pipeline") }
            .let { FlowsheetEditing.setSheetField(it, "pg", SheetKeys.DOCUMENT_NUMBER, "P-0001-PR-DS-001") }
        val stored = StoredSolve(solved, fs.modelHash(), System.currentTimeMillis())
        val sheets = listOf("pg", "pt").map { DataSheets.build("Lines", fs, stored, UnitSet.SI, fs.obj(it)!!, "2026-10-21", BuildConfig.VERSION_NAME) }
        logPdf("data-sheets", java.io.ByteArrayOutputStream().also { DataSheetPdf.write(sheets, it) }.toByteArray())
    }

    /** The recycle loop's PFD (with property tables) and heat and material balance, logged like the data sheets. */
    private fun sampleProjectDocuments(input: Flowsheet, solved: app.thermodock.core.api.SolveResult) {
        var fs = input.copy(
            documentInfo = DocumentInfo(
                company = "Sample Engineering Ltd", project = "Gas Plant Recycle Study", projectNumber = "P-0002",
                client = "Sample Client", site = "Onshore", preparedBy = "ThermoDock", pfdNumber = "P-0002-PR-PFD-001",
                hmbNumber = "P-0002-PR-HMB-001", revision = "A",
            ),
        )
        fs = FlowsheetEditing.setTable(fs, "feed", PropertyTables.defaults(fs, fs.obj("feed")!!))
        fs = FlowsheetEditing.setTable(fs, "gas", listOf("temperature", "pressure", "massFlow"))
        fs = FlowsheetEditing.setTable(fs, "chill", PropertyTables.available(fs, fs.obj("chill")!!))
        val stored = StoredSolve(solved, fs.modelHash(), System.currentTimeMillis())
        val view = ResultView(stored, fs.modelHash())
        val header = PfdPdf.Header("Recycle", fs.documentInfo!!, "2026-10-21", UnitSet.SI.name, fs.propertyPackage, "ThermoDock ${BuildConfig.VERSION_NAME}", false, true)
        logPdf("pfd", java.io.ByteArrayOutputStream().also { PfdPdf.write(fs, view, UnitSet.SI, header, it) }.toByteArray())
        val hmb = HeatMaterialBalance.build("Recycle", fs, stored, UnitSet.SI, "2026-10-21", BuildConfig.VERSION_NAME, HeatMaterialBalance.STREAMS_PER_PDF_SHEET)
        logPdf("hmb", java.io.ByteArrayOutputStream().also { DataSheetPdf.write(hmb, it) }.toByteArray())
    }

    private fun logPdf(name: String, bytes: ByteArray) {
        assertTrue(String(bytes, 0, 5, Charsets.US_ASCII) == "%PDF-", "$name is not a PDF")
        val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
        Log.i(PDF_TAG, "begin $name ${bytes.size} bytes")
        b64.chunked(3000).forEach { Log.i(PDF_TAG, it) }
        Log.i(PDF_TAG, "end $name")
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
        const val TAG = "ThermoDockOnDevice"
        const val PDF_TAG = "ThermoDockPdf"
    }
}
