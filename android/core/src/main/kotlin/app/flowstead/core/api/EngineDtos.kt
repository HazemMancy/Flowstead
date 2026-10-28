/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.api

import kotlinx.serialization.Serializable

/** `GET /health` (shared/openapi.yaml). */
@Serializable
data class EngineHealth(
    val status: String,
    val engineVersion: String,
    val dwsimVersion: String,
)

/** Error body the engine returns with any 4xx/5xx. */
@Serializable
data class EngineError(
    val code: String,
    val message: String,
)

/** `GET /compounds` item. */
@Serializable
data class CompoundInfo(
    val name: String,
    val cas: String = "",
    val formula: String = "",
    val molecularWeight: Double = Double.NaN,
    val normalBoilingPointK: Double = Double.NaN,
    val database: String = "",
)

/** `GET /property-packages` item. */
@Serializable
data class PropertyPackageInfo(val name: String, val mvp: Boolean)

/** What the app can offer without an engine connection. Names match DWSIM / the engine exactly. */
/** Property packages offered before the engine has answered (the MVP set, in display order). */
object OfflineCatalogue {
    val propertyPackages = listOf(
        "Peng-Robinson (PR)",
        "Soave-Redlich-Kwong (SRK)",
        "Peng-Robinson / Lee-Kesler (PR/LK)",
        "NRTL",
        "UNIQUAC",
        "Steam Tables (IAPWS-IF97)",
        "GERG-2008",
        "CoolProp",
    )
}

/** A DWSIM file (.dwxmz, or uncompressed .dwxml), base64-encoded, for POST /import. */
@Serializable
data class DwsimFile(val data: String)

/** POST /export: the solved flowsheet as .dwxmz bytes (base64), plus what could not be carried over. */
@Serializable
data class ExportResult(val data: String, val status: SolveStatus, val warnings: List<String> = emptyList())

/** POST /import: the mapped flowsheet and everything left out or changed; show every warning. */
@Serializable
data class ImportResult(val flowsheet: app.flowstead.core.model.Flowsheet, val warnings: List<String> = emptyList())
