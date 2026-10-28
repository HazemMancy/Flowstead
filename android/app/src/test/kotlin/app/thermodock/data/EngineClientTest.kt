/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.data

import app.thermodock.core.api.EngineHealth
import app.thermodock.data.engine.EngineResult
import app.thermodock.data.engine.KtorEngineClient
import app.thermodock.data.settings.EngineUrl
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EngineClientTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun client(status: HttpStatusCode, body: String, seenAuth: MutableList<String?> = mutableListOf()) =
        KtorEngineClient(
            HttpClient(MockEngine { req ->
                seenAuth += req.headers[HttpHeaders.Authorization]
                respond(body, status, jsonHeaders)
            }) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            },
        )

    @Test
    fun healthParsesAndSendsBearerKey() = runTest {
        val auth = mutableListOf<String?>()
        val c = client(HttpStatusCode.OK, """{"status":"ok","engineVersion":"0.1.0","dwsimVersion":"9.0.5","extra":1}""", auth)
        val r = c.health("https://e.example", "k123")
        assertEquals(EngineResult.Ok(EngineHealth("ok", "0.1.0", "9.0.5")), r)
        assertEquals(listOf<String?>("Bearer k123"), auth)
    }

    @Test
    fun unauthorisedIsExplained() = runTest {
        val r = client(HttpStatusCode.Unauthorized, """{"code":"bad_key","message":"Unknown key"}""").health("https://e.example", "x")
        assertEquals(EngineResult.Failed("The engine rejected the API key: Unknown key"), r)
    }

    @Test
    fun serverErrorWithoutBody() = runTest {
        val r = client(HttpStatusCode.BadGateway, "").health("https://e.example", null)
        assertIs<EngineResult.Failed>(r)
        assertEquals("Engine error 502", r.reason)
    }

    @Test
    fun urlNormalisation() {
        assertEquals(EngineUrl.Result.Ok("https://h.example:8443/api"), EngineUrl.normalise("https://h.example:8443/api/"))
        assertEquals(EngineUrl.Result.Ok("https://10.0.0.5"), EngineUrl.normalise("10.0.0.5"))
        assertEquals(EngineUrl.Result.Invalid("Enter the engine address"), EngineUrl.normalise("  "))
        assertEquals(EngineUrl.Result.Invalid("The engine must use HTTPS"), EngineUrl.normalise("ftp://h"))
        assertEquals(EngineUrl.Result.Invalid("Remove the query or fragment"), EngineUrl.normalise("https://h/?a=1"))
        assertIs<EngineUrl.Result.Invalid>(EngineUrl.normalise("https://"))
    }
}
