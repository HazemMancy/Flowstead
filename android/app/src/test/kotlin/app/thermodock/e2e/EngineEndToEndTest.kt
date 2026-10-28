/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.e2e

import app.thermodock.core.api.SolveStatus
import app.thermodock.core.model.Flowsheet
import app.thermodock.core.model.FlowsheetJson
import app.thermodock.data.engine.EngineResult
import app.thermodock.data.engine.KtorEngineClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * App ↔ engine round trip against a real engine container (brief §4). Runs in CI's `e2e` job,
 * which starts the engine and sets THERMODOCK_E2E_URL / THERMODOCK_E2E_KEY; skipped elsewhere.
 */
class EngineEndToEndTest {
    private val url = System.getenv("THERMODOCK_E2E_URL")
    private val key = System.getenv("THERMODOCK_E2E_KEY")

    private fun client() = KtorEngineClient(
        HttpClient(OkHttp) {
            expectSuccess = false
            install(ContentNegotiation) { json(ThermoDockJsonForTests) }
            install(HttpTimeout) { requestTimeoutMillis = 120_000 }
        },
    )

    private fun benchmark(name: String): Flowsheet {
        val repo = System.getProperty("thermodock.repoDir") ?: error("thermodock.repoDir not set")
        val root = FlowsheetJson.parseToJsonElement(File(repo, "benchmarks/$name/input.json").readText()).jsonObject
        return FlowsheetJson.decodeFromJsonElement(Flowsheet.serializer(), root.getValue("flowsheet"))
    }

    @Test
    fun solvesTheTwoPhaseFlashBenchmarkThroughTheApi() = runBlocking<Unit> {
        assumeTrue("THERMODOCK_E2E_URL not set", !url.isNullOrBlank())
        val c = client()
        val health = assertIs<EngineResult.Ok<*>>(c.health(url, key))
        println("Engine: ${health.value}")

        val result = assertIs<EngineResult.Ok<app.thermodock.core.api.SolveResult>>(c.solve(url, key, benchmark("03-two-phase-flash"))).value
        assertEquals(SolveStatus.Converged, result.status, result.messages.joinToString { it.text })
        // Same value the benchmark harness pins on the engine side (expected.json, abs 1e-4).
        assertEquals(0.9460408, result.objects.getValue("feed").properties.getValue("vapourFraction"), 1e-4)
    }

    @Test
    fun dwsimFileRoundTripThroughTheApi() = runBlocking<Unit> {
        assumeTrue("THERMODOCK_E2E_URL not set", !url.isNullOrBlank())
        val c = client()
        val original = benchmark("06-compressor-aftercooler")
        val export = assertIs<EngineResult.Ok<app.thermodock.core.api.ExportResult>>(c.exportDwsim(url, key, original)).value
        assertEquals(SolveStatus.Converged, export.status)
        val back = assertIs<EngineResult.Ok<app.thermodock.core.api.ImportResult>>(
            c.importDwsim(url, key, app.thermodock.core.api.DwsimFile(export.data)),
        ).value.flowsheet
        assertEquals(original.propertyPackage, back.propertyPackage)
        assertEquals(original.objects.map { it.tag }.toSet(), back.objects.map { it.tag }.toSet() - back.objects.filter { it.tag.endsWith("-Q") }.map { it.tag }.toSet())
        val resolved = assertIs<EngineResult.Ok<app.thermodock.core.api.SolveResult>>(c.solve(url, key, back)).value
        assertEquals(SolveStatus.Converged, resolved.status, resolved.messages.joinToString { it.text })
    }

    @Test
    fun wrongKeyIsReportedInPlainLanguage() = runBlocking<Unit> {
        assumeTrue("THERMODOCK_E2E_URL not set", !url.isNullOrBlank())
        val r = client().health(url, "fs_wrong")
        assertEquals("The engine rejected the API key: Unknown or revoked API key.", assertIs<EngineResult.Failed>(r).reason)
    }

    private companion object {
        val ThermoDockJsonForTests = FlowsheetJson
    }
}
