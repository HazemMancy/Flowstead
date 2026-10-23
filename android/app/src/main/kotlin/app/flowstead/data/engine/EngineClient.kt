/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.data.engine

import app.flowstead.core.api.HypotheticalProperties
import app.flowstead.core.api.CompoundInfo
import app.flowstead.core.api.DwsimFile
import app.flowstead.core.api.ExportResult
import app.flowstead.core.api.ImportResult
import app.flowstead.core.api.EngineError
import app.flowstead.core.api.EngineHealth
import app.flowstead.core.api.PhaseEnvelope
import app.flowstead.core.api.PhaseEnvelopeRequest
import app.flowstead.core.api.PropertyPackageInfo
import app.flowstead.core.api.PvtRequest
import app.flowstead.core.api.ReliefRequest
import app.flowstead.core.api.ReliefResult
import app.flowstead.core.api.DepressuringRequest
import app.flowstead.core.api.DepressuringResult
import app.flowstead.core.api.PvtResult
import app.flowstead.core.api.SolveResult
import app.flowstead.core.model.Hypothetical
import app.flowstead.core.model.Flowsheet
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
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
    /** [unreachable]: no connection at all (offline, DNS, refused), as opposed to an engine that answered "no". */
    data class Failed(val reason: String, val unreachable: Boolean = false) : EngineResult<Nothing>
}

interface EngineClient {
    suspend fun health(baseUrl: String, apiKey: String?): EngineResult<EngineHealth>
    suspend fun compounds(baseUrl: String, apiKey: String?, query: String, limit: Int = 25): EngineResult<List<CompoundInfo>>
    suspend fun propertyPackages(baseUrl: String, apiKey: String?): EngineResult<List<PropertyPackageInfo>>
    suspend fun solve(baseUrl: String, apiKey: String?, flowsheet: Flowsheet): EngineResult<SolveResult>
    suspend fun exportDwsim(baseUrl: String, apiKey: String?, flowsheet: Flowsheet): EngineResult<ExportResult>
    suspend fun importDwsim(baseUrl: String, apiKey: String?, file: DwsimFile): EngineResult<ImportResult>
    /** The constants the engine's correlations give these hypotheticals. */
    suspend fun estimateHypotheticals(baseUrl: String, apiKey: String?, hypotheticals: List<Hypothetical>): EngineResult<List<HypotheticalProperties>> =
        EngineResult.Failed("This engine can't estimate hypotheticals")
    /** DWSIM's phase envelope of one composition with a flowsheet's package and compounds (D50, D52). */
    suspend fun phaseEnvelope(baseUrl: String, apiKey: String?, request: PhaseEnvelopeRequest): EngineResult<PhaseEnvelope> =
        EngineResult.Failed("This engine can't trace phase envelopes")
    /** A PVT laboratory experiment (D53). */
    suspend fun pvt(baseUrl: String, apiKey: String?, request: PvtRequest): EngineResult<PvtResult> =
        EngineResult.Failed("This engine can't run PVT experiments")
    /** Relief loads and sizing of one relief valve (D54–D56). */
    suspend fun relief(baseUrl: String, apiKey: String?, request: ReliefRequest): EngineResult<ReliefResult> =
        EngineResult.Failed("This engine can't size relief valves")
    /** A vessel blowdown (D57). */
    suspend fun depressuring(baseUrl: String, apiKey: String?, request: DepressuringRequest): EngineResult<DepressuringResult> =
        EngineResult.Failed("This engine can't simulate depressuring")
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

    override suspend fun exportDwsim(baseUrl: String, apiKey: String?, flowsheet: Flowsheet): EngineResult<ExportResult> = call {
        http.post("$baseUrl/export") {
            apiKey?.let { bearerAuth(it) }
            contentType(ContentType.Application.Json)
            setBody(flowsheet)
        }
    }

    override suspend fun importDwsim(baseUrl: String, apiKey: String?, file: DwsimFile): EngineResult<ImportResult> = call {
        http.post("$baseUrl/import") {
            apiKey?.let { bearerAuth(it) }
            contentType(ContentType.Application.Json)
            setBody(file)
        }
    }

    override suspend fun estimateHypotheticals(baseUrl: String, apiKey: String?, hypotheticals: List<Hypothetical>): EngineResult<List<HypotheticalProperties>> =
        call {
            http.post("$baseUrl/hypotheticals/estimate") {
                apiKey?.let { bearerAuth(it) }
                contentType(ContentType.Application.Json)
                setBody(hypotheticals)
            }
        }

    override suspend fun phaseEnvelope(baseUrl: String, apiKey: String?, request: PhaseEnvelopeRequest): EngineResult<PhaseEnvelope> =
        call {
            http.post("$baseUrl/phase-envelope") {
                apiKey?.let { bearerAuth(it) }
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun pvt(baseUrl: String, apiKey: String?, request: PvtRequest): EngineResult<PvtResult> =
        call {
            http.post("$baseUrl/pvt") {
                apiKey?.let { bearerAuth(it) }
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun relief(baseUrl: String, apiKey: String?, request: ReliefRequest): EngineResult<ReliefResult> =
        call {
            http.post("$baseUrl/relief") {
                apiKey?.let { bearerAuth(it) }
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun depressuring(baseUrl: String, apiKey: String?, request: DepressuringRequest): EngineResult<DepressuringResult> =
        call {
            http.post("$baseUrl/depressuring") {
                apiKey?.let { bearerAuth(it) }
                contentType(ContentType.Application.Json)
                setBody(request)
                // A blowdown with orifice sizing is many flashes.
                timeout { requestTimeoutMillis = 10 * 60_000L }
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
        EngineResult.Failed("Cannot reach the engine: ${e.message ?: e::class.simpleName}", unreachable = true)
    } catch (e: Exception) {
        EngineResult.Failed("Unexpected engine response: ${e.message ?: e::class.simpleName}")
    }

    private suspend fun describe(response: HttpResponse): String {
        val detail = runCatching { response.body<EngineError>().message }.getOrNull()
        val base = when (response.status) {
            HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> "The engine rejected the API key"
            HttpStatusCode.TooManyRequests -> "Too many requests; try again shortly"
            HttpStatusCode.NotFound -> "This address is not a Flowstead engine"
            HttpStatusCode.UnprocessableEntity -> "The engine could not use it"
            else -> "Engine error ${response.status.value}"
        }
        return if (detail.isNullOrBlank()) base else "$base: $detail"
    }
}
