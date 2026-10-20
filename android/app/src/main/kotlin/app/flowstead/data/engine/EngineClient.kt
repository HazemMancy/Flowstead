/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.data.engine

import app.flowstead.core.api.CompoundInfo
import app.flowstead.core.api.EngineError
import app.flowstead.core.api.EngineHealth
import app.flowstead.core.api.PropertyPackageInfo
import app.flowstead.core.api.SolveResult
import app.flowstead.core.model.Flowsheet
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
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
    suspend fun compounds(baseUrl: String, apiKey: String?, query: String, limit: Int = 25): EngineResult<List<CompoundInfo>>
    suspend fun propertyPackages(baseUrl: String, apiKey: String?): EngineResult<List<PropertyPackageInfo>>
    suspend fun solve(baseUrl: String, apiKey: String?, flowsheet: Flowsheet): EngineResult<SolveResult>
}

@Singleton
class KtorEngineClient @Inject constructor(private val http: HttpClient) : EngineClient {

    override suspend fun health(baseUrl: String, apiKey: String?): EngineResult<EngineHealth> = call {
        http.get("$baseUrl/health") { apiKey?.let { bearerAuth(it) } }
    }

    override suspend fun compounds(baseUrl: String, apiKey: String?, query: String, limit: Int): EngineResult<List<CompoundInfo>> =
        call {
            http.get("$baseUrl/compounds") {
                apiKey?.let { bearerAuth(it) }
                parameter("query", query)
                parameter("limit", limit)
            }
        }

    override suspend fun propertyPackages(baseUrl: String, apiKey: String?): EngineResult<List<PropertyPackageInfo>> = call {
        http.get("$baseUrl/property-packages") { apiKey?.let { bearerAuth(it) } }
    }

    override suspend fun solve(baseUrl: String, apiKey: String?, flowsheet: Flowsheet): EngineResult<SolveResult> = call {
        http.post("$baseUrl/solve") {
            apiKey?.let { bearerAuth(it) }
            contentType(ContentType.Application.Json)
            setBody(flowsheet)
        }
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
