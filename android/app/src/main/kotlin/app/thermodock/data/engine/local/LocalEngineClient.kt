/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.data.engine.local

import app.thermodock.BuildConfig
import app.thermodock.core.api.CompoundInfo
import app.thermodock.core.api.DwsimFile
import app.thermodock.core.api.EngineHealth
import app.thermodock.core.api.ExportResult
import app.thermodock.core.api.HypotheticalProperties
import app.thermodock.core.api.ImportResult
import app.thermodock.core.api.PhaseEnvelope
import app.thermodock.core.api.PhaseEnvelopeRequest
import app.thermodock.core.api.PropertyPackageInfo
import app.thermodock.core.api.PvtRequest
import app.thermodock.core.api.PvtResult
import app.thermodock.core.api.SolveResult
import app.thermodock.core.model.Flowsheet
import app.thermodock.core.model.Hypothetical
import app.thermodock.core.model.FlowsheetJson
import app.thermodock.data.engine.EngineClient
import app.thermodock.data.engine.EngineResult
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

    override suspend fun phaseEnvelope(baseUrl: String, apiKey: String?, request: PhaseEnvelopeRequest): EngineResult<PhaseEnvelope> =
        decode(PhaseEnvelope.serializer()) {
            engine.call("phaseEnvelope", FlowsheetJson.encodeToJsonElement(PhaseEnvelopeRequest.serializer(), request))
        }

    override suspend fun pvt(baseUrl: String, apiKey: String?, request: PvtRequest): EngineResult<PvtResult> =
        decode(PvtResult.serializer()) {
            engine.call("pvt", FlowsheetJson.encodeToJsonElement(PvtRequest.serializer(), request))
        }

    override suspend fun interactionParameters(baseUrl: String, apiKey: String?, flowsheet: Flowsheet): EngineResult<List<app.thermodock.core.api.BinaryParameterInfo>> =
        decode(ListSerializer(app.thermodock.core.api.BinaryParameterInfo.serializer())) {
            engine.call("interactionParameters", FlowsheetJson.encodeToJsonElement(Flowsheet.serializer(), flowsheet))
        }

    override suspend fun pvtRegression(baseUrl: String, apiKey: String?, request: app.thermodock.core.api.PvtRegressionRequest): EngineResult<app.thermodock.core.api.PvtRegressionResult> =
        decode(app.thermodock.core.api.PvtRegressionResult.serializer()) {
            engine.call("pvtRegression", FlowsheetJson.encodeToJsonElement(app.thermodock.core.api.PvtRegressionRequest.serializer(), request))
        }

    override suspend fun relief(baseUrl: String, apiKey: String?, request: app.thermodock.core.api.ReliefRequest): EngineResult<app.thermodock.core.api.ReliefResult> =
        decode(app.thermodock.core.api.ReliefResult.serializer()) {
            engine.call("relief", FlowsheetJson.encodeToJsonElement(app.thermodock.core.api.ReliefRequest.serializer(), request))
        }

    override suspend fun depressuring(baseUrl: String, apiKey: String?, request: app.thermodock.core.api.DepressuringRequest): EngineResult<app.thermodock.core.api.DepressuringResult> =
        decode(app.thermodock.core.api.DepressuringResult.serializer()) {
            engine.call("depressuring", FlowsheetJson.encodeToJsonElement(app.thermodock.core.api.DepressuringRequest.serializer(), request))
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
