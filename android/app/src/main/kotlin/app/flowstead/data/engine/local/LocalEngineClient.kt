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
import app.flowstead.core.api.ImportResult
import app.flowstead.core.api.PropertyPackageInfo
import app.flowstead.core.api.SolveResult
import app.flowstead.core.model.Flowsheet
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
