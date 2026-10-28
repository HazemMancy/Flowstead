/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.data.engine.local

import app.flowstead.BuildConfig
import app.flowstead.core.api.CompoundInfo
import app.flowstead.core.api.DwsimFile
import app.flowstead.core.api.EngineHealth
import app.flowstead.core.api.ExportResult
import app.flowstead.core.api.HypotheticalProperties
import app.flowstead.core.api.ImportResult
import app.flowstead.core.api.PhaseEnvelope
import app.flowstead.core.api.PhaseEnvelopeRequest
import app.flowstead.core.api.PropertyPackageInfo
import app.flowstead.core.api.PvtRequest
import app.flowstead.core.api.PvtResult
import app.flowstead.core.api.SolveResult
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.Hypothetical
import app.flowstead.core.model.FlowsheetJson
import app.flowstead.data.engine.EngineClient
import app.flowstead.data.engine.EngineResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** The engine calls the app makes, answered by DWSIM on this phone (same results as the server). */
@Singleton
class LocalEngineClient @Inject constructor(private val engine: LocalEngine) : EngineClient {

    override suspend fun health(baseUrl: String, apiKey: String?): EngineResult<EngineHealth> = attempt {
        engine.call("ping")
        EngineHealth("ok", ENGINE_VERSION, engine.dwsimVersion ?: "unknown")
    }

    override suspend fun compounds(baseUrl: String, apiKey: String?, query: String, limit: Int): EngineResult<List<CompoundInfo>> =
        decode(ListSerializer(CompoundInfo.serializer())) {
            engine.call("searchCompounds", buildJsonObject {
                put("query", JsonPrimitive(query))
                put("limit", JsonPrimitive(limit))
            })
        }

    override suspend fun propertyPackages(baseUrl: String, apiKey: String?): EngineResult<List<PropertyPackageInfo>> =
        decode(ListSerializer(PropertyPackageInfo.serializer())) { engine.call("propertyPackages") }

    override suspend fun solve(baseUrl: String, apiKey: String?, flowsheet: Flowsheet): EngineResult<SolveResult> =
        decode(SolveResult.serializer()) { engine.call("solve", FlowsheetJson.encodeToJsonElement(Flowsheet.serializer(), flowsheet)) }

    override suspend fun exportDwsim(baseUrl: String, apiKey: String?, flowsheet: Flowsheet): EngineResult<ExportResult> =
        decode(ExportResult.serializer()) { engine.call("exportDwsim", FlowsheetJson.encodeToJsonElement(Flowsheet.serializer(), flowsheet)) }

    override suspend fun importDwsim(baseUrl: String, apiKey: String?, file: DwsimFile): EngineResult<ImportResult> =
        decode(ImportResult.serializer()) { engine.call("importDwsim", FlowsheetJson.encodeToJsonElement(DwsimFile.serializer(), file)) }

    override suspend fun estimateHypotheticals(baseUrl: String, apiKey: String?, hypotheticals: List<Hypothetical>): EngineResult<List<HypotheticalProperties>> =
        decode(ListSerializer(HypotheticalProperties.serializer())) {
            engine.call("estimateHypotheticals", FlowsheetJson.encodeToJsonElement(ListSerializer(Hypothetical.serializer()), hypotheticals))
        }

    override suspend fun compoundBasis(baseUrl: String, apiKey: String?, flowsheet: Flowsheet): EngineResult<List<app.flowstead.core.api.CompoundBasis>> =
        decode(ListSerializer(app.flowstead.core.api.CompoundBasis.serializer())) {
            engine.call("compoundBasis", FlowsheetJson.encodeToJsonElement(Flowsheet.serializer(), flowsheet))
        }

    override suspend fun phaseEnvelope(baseUrl: String, apiKey: String?, request: PhaseEnvelopeRequest): EngineResult<PhaseEnvelope> =
        decode(PhaseEnvelope.serializer()) {
            engine.call("phaseEnvelope", FlowsheetJson.encodeToJsonElement(PhaseEnvelopeRequest.serializer(), request))
        }

    override suspend fun pvt(baseUrl: String, apiKey: String?, request: PvtRequest): EngineResult<PvtResult> =
        decode(PvtResult.serializer()) {
            engine.call("pvt", FlowsheetJson.encodeToJsonElement(PvtRequest.serializer(), request))
        }

    override suspend fun interactionParameters(baseUrl: String, apiKey: String?, flowsheet: Flowsheet): EngineResult<List<app.flowstead.core.api.BinaryParameterInfo>> =
        decode(ListSerializer(app.flowstead.core.api.BinaryParameterInfo.serializer())) {
            engine.call("interactionParameters", FlowsheetJson.encodeToJsonElement(Flowsheet.serializer(), flowsheet))
        }

    override suspend fun pvtRegression(baseUrl: String, apiKey: String?, request: app.flowstead.core.api.PvtRegressionRequest): EngineResult<app.flowstead.core.api.PvtRegressionResult> =
        decode(app.flowstead.core.api.PvtRegressionResult.serializer()) {
            engine.call("pvtRegression", FlowsheetJson.encodeToJsonElement(app.flowstead.core.api.PvtRegressionRequest.serializer(), request))
        }

    override suspend fun relief(baseUrl: String, apiKey: String?, request: app.flowstead.core.api.ReliefRequest): EngineResult<app.flowstead.core.api.ReliefResult> =
        decode(app.flowstead.core.api.ReliefResult.serializer()) {
            engine.call("relief", FlowsheetJson.encodeToJsonElement(app.flowstead.core.api.ReliefRequest.serializer(), request))
        }

    override suspend fun depressuring(baseUrl: String, apiKey: String?, request: app.flowstead.core.api.DepressuringRequest): EngineResult<app.flowstead.core.api.DepressuringResult> =
        decode(app.flowstead.core.api.DepressuringResult.serializer()) {
            engine.call("depressuring", FlowsheetJson.encodeToJsonElement(app.flowstead.core.api.DepressuringRequest.serializer(), request))
        }

    private suspend fun <T> decode(serializer: KSerializer<T>, block: suspend () -> kotlinx.serialization.json.JsonElement): EngineResult<T> =
        attempt { FlowsheetJson.decodeFromJsonElement(serializer, block()) }

    private inline fun <T> attempt(block: () -> T): EngineResult<T> = try {
        EngineResult.Ok(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: LocalEngineException) {
        EngineResult.Failed("On-device engine: ${e.message}")
    } catch (e: UnsatisfiedLinkError) {
        EngineResult.Failed("On-device engine: could not load (${e.message})")
    } catch (e: Exception) {
        EngineResult.Failed("On-device engine: ${e.message ?: e::class.simpleName}")
    }

    private companion object {
        // The on-device engine ships inside the app, so it carries the app's version.
        const val ENGINE_VERSION = "${BuildConfig.VERSION_NAME} (on device)"
    }
}
