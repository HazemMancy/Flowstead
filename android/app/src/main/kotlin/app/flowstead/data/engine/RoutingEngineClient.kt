/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.data.engine

import app.flowstead.core.api.DwsimFile
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.Hypothetical
import app.flowstead.data.engine.local.LocalEngineClient
import javax.inject.Inject
import javax.inject.Singleton

/** The address that means "solve on this phone" (AppSettings.engineTarget). */
const val ON_DEVICE_ENGINE = "on-device"

/** Sends each call to the on-device engine or to the remote server, by the target address. */
@Singleton
class RoutingEngineClient @Inject constructor(
    private val remote: KtorEngineClient,
    private val local: LocalEngineClient,
) : EngineClient {
    private fun pick(baseUrl: String): EngineClient = if (baseUrl == ON_DEVICE_ENGINE) local else remote

    override suspend fun health(baseUrl: String, apiKey: String?) = pick(baseUrl).health(baseUrl, apiKey)
    override suspend fun compounds(baseUrl: String, apiKey: String?, query: String, limit: Int) = pick(baseUrl).compounds(baseUrl, apiKey, query, limit)
    override suspend fun propertyPackages(baseUrl: String, apiKey: String?) = pick(baseUrl).propertyPackages(baseUrl, apiKey)
    override suspend fun solve(baseUrl: String, apiKey: String?, flowsheet: Flowsheet) = pick(baseUrl).solve(baseUrl, apiKey, flowsheet)
    override suspend fun exportDwsim(baseUrl: String, apiKey: String?, flowsheet: Flowsheet) = pick(baseUrl).exportDwsim(baseUrl, apiKey, flowsheet)
    override suspend fun importDwsim(baseUrl: String, apiKey: String?, file: DwsimFile) = pick(baseUrl).importDwsim(baseUrl, apiKey, file)
    override suspend fun estimateHypotheticals(baseUrl: String, apiKey: String?, hypotheticals: List<Hypothetical>) =
        pick(baseUrl).estimateHypotheticals(baseUrl, apiKey, hypotheticals)
    override suspend fun phaseEnvelope(baseUrl: String, apiKey: String?, request: app.flowstead.core.api.PhaseEnvelopeRequest) =
        pick(baseUrl).phaseEnvelope(baseUrl, apiKey, request)
    override suspend fun pvt(baseUrl: String, apiKey: String?, request: app.flowstead.core.api.PvtRequest) =
        pick(baseUrl).pvt(baseUrl, apiKey, request)
    override suspend fun relief(baseUrl: String, apiKey: String?, request: app.flowstead.core.api.ReliefRequest) =
        pick(baseUrl).relief(baseUrl, apiKey, request)
    override suspend fun interactionParameters(baseUrl: String, apiKey: String?, flowsheet: app.flowstead.core.model.Flowsheet) =
        pick(baseUrl).interactionParameters(baseUrl, apiKey, flowsheet)
    override suspend fun pvtRegression(baseUrl: String, apiKey: String?, request: app.flowstead.core.api.PvtRegressionRequest) =
        pick(baseUrl).pvtRegression(baseUrl, apiKey, request)
    override suspend fun depressuring(baseUrl: String, apiKey: String?, request: app.flowstead.core.api.DepressuringRequest) =
        pick(baseUrl).depressuring(baseUrl, apiKey, request)
}
