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
    override suspend fun compoundBasis(baseUrl: String, apiKey: String?, flowsheet: app.flowstead.core.model.Flowsheet) =
        pick(baseUrl).compoundBasis(baseUrl, apiKey, flowsheet)
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
    override suspend fun dynamics(baseUrl: String, apiKey: String?, request: app.flowstead.core.api.DynamicsRequest) =
        pick(baseUrl).dynamics(baseUrl, apiKey, request)
    override suspend fun structureCompound(baseUrl: String, apiKey: String?, request: app.flowstead.core.api.StructureCompoundRequest) =
        pick(baseUrl).structureCompound(baseUrl, apiKey, request)
    override suspend fun regressVle(baseUrl: String, apiKey: String?, request: app.flowstead.core.api.VleRegressionRequest) =
        pick(baseUrl).regressVle(baseUrl, apiKey, request)
    override suspend fun optimise(baseUrl: String, apiKey: String?, request: app.flowstead.core.api.OptimiseRequest) =
        pick(baseUrl).optimise(baseUrl, apiKey, request)
    override suspend fun findReactions(baseUrl: String, apiKey: String?, request: app.flowstead.core.api.ReactionFinderRequest) =
        pick(baseUrl).findReactions(baseUrl, apiKey, request)
    override suspend fun binaryDiagram(baseUrl: String, apiKey: String?, request: app.flowstead.core.api.BinaryDiagramRequest) =
        pick(baseUrl).binaryDiagram(baseUrl, apiKey, request)
    override suspend fun ternary(baseUrl: String, apiKey: String?, request: app.flowstead.core.api.TernaryRequest) =
        pick(baseUrl).ternary(baseUrl, apiKey, request)
    override suspend fun criticalPoints(baseUrl: String, apiKey: String?, request: app.flowstead.core.api.CriticalPointRequest) =
        pick(baseUrl).criticalPoints(baseUrl, apiKey, request)
    override suspend fun comparePackages(baseUrl: String, apiKey: String?, request: app.flowstead.core.api.PackageComparisonRequest) =
        pick(baseUrl).comparePackages(baseUrl, apiKey, request)
    override suspend fun propertySweep(baseUrl: String, apiKey: String?, request: app.flowstead.core.api.PropertySweepRequest) =
        pick(baseUrl).propertySweep(baseUrl, apiKey, request)
    override suspend fun assay(baseUrl: String, apiKey: String?, request: app.flowstead.core.api.AssayRequest) =
        pick(baseUrl).assay(baseUrl, apiKey, request)
    override suspend fun petroleumProperties(baseUrl: String, apiKey: String?, request: app.flowstead.core.api.PetroleumPropertiesRequest) =
        pick(baseUrl).petroleumProperties(baseUrl, apiKey, request)
}
