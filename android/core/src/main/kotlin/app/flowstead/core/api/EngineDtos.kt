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

    /** Common oil & gas compounds with the shorthand engineers type (same aliases as the engine). */
    val compounds: List<Pair<String, List<String>>> = listOf(
        "Methane" to listOf("C1"), "Ethane" to listOf("C2"), "Propane" to listOf("C3"),
        "Isobutane" to listOf("iC4", "i-C4"), "N-butane" to listOf("nC4", "n-C4"),
        "Isopentane" to listOf("iC5", "i-C5"), "N-pentane" to listOf("nC5", "n-C5"), "Neopentane" to listOf("neoC5"),
        "N-hexane" to listOf("nC6"), "N-heptane" to listOf("nC7"), "N-octane" to listOf("nC8"),
        "N-nonane" to listOf("nC9"), "N-decane" to listOf("nC10"),
        "Nitrogen" to listOf("N2"), "Carbon dioxide" to listOf("CO2"), "Hydrogen sulfide" to listOf("H2S"),
        "Water" to listOf("H2O"), "Oxygen" to listOf("O2"), "Hydrogen" to listOf("H2"), "Carbon monoxide" to listOf("CO"),
        "Argon" to listOf("Ar"), "Helium-4" to listOf("He"), "Ammonia" to listOf("NH3"),
        "Ethylene" to listOf("C2="), "Propylene" to listOf("C3="), "Benzene" to emptyList(), "Toluene" to emptyList(),
        "Methanol" to listOf("MeOH"), "Ethanol" to listOf("EtOH"),
        "Ethylene glycol" to listOf("MEG"), "Diethylene glycol" to listOf("DEG"), "Triethylene glycol" to listOf("TEG"),
        "Methyl DiEthanolAmine" to listOf("MDEA"), "Diethanolamine" to listOf("DEA"),
    )

    /** Same ranking idea as the engine: alias or exact name first, then prefix, then substring. */
    fun search(query: String, limit: Int = 25): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return compounds.map { it.first }.take(limit)
        return compounds.mapNotNull { (name, aliases) ->
            val rank = when {
                aliases.any { it.equals(q, ignoreCase = true) } || name.equals(q, ignoreCase = true) ||
                    name.removePrefix("N-").equals(q, ignoreCase = true) -> 0
                name.startsWith(q, ignoreCase = true) -> 1
                name.contains(q, ignoreCase = true) -> 2
                else -> null
            }
            rank?.let { name to it }
        }.sortedWith(compareBy({ it.second }, { it.first.length })).map { it.first }.take(limit)
    }
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
