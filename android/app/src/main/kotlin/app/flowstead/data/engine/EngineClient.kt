/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.data.engine

import app.flowstead.core.api.EngineError
import app.flowstead.core.api.EngineHealth
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException

/** Outcome of an engine call, in words an engineer can act on. */
sealed interface EngineResult<out T> {
    data class Ok<T>(val value: T) : EngineResult<T>
    data class Failed(val reason: String) : EngineResult<Nothing>
}

interface EngineClient {
    suspend fun health(baseUrl: String, apiKey: String?): EngineResult<EngineHealth>
}

@Singleton
class KtorEngineClient @Inject constructor(private val http: HttpClient) : EngineClient {

    override suspend fun health(baseUrl: String, apiKey: String?): EngineResult<EngineHealth> = call {
        http.get("$baseUrl/health") { apiKey?.let { bearerAuth(it) } }
    }

    private suspend inline fun <reified T> call(block: () -> HttpResponse): EngineResult<T> = try {
        val response = block()
        if (response.status.isSuccess()) {
            EngineResult.Ok(response.body<T>())
        } else {
            EngineResult.Failed(describe(response))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpRequestTimeoutException) {
        EngineResult.Failed("The engine did not answer in time")
    } catch (e: SSLException) {
        EngineResult.Failed("Secure connection failed: ${e.message ?: "certificate not trusted"}")
    } catch (e: IOException) {
        EngineResult.Failed("Cannot reach the engine: ${e.message ?: e::class.simpleName}")
    } catch (e: Exception) {
        EngineResult.Failed("Unexpected engine response: ${e.message ?: e::class.simpleName}")
    }

    private suspend fun describe(response: HttpResponse): String {
        val detail = runCatching { response.body<EngineError>().message }.getOrNull()
        val base = when (response.status) {
            HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> "The engine rejected the API key"
            HttpStatusCode.TooManyRequests -> "Too many requests; try again shortly"
            HttpStatusCode.NotFound -> "This address is not a Flowstead engine"
            else -> "Engine error ${response.status.value}"
        }
        return if (detail.isNullOrBlank()) base else "$base: $detail"
    }
}
