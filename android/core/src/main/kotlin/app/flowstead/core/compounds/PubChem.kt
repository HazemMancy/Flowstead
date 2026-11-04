/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.compounds

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/** A compound found on PubChem: its structure for the Joback estimate (D104). Numbers here are PubChem's, not estimates. */
data class PubChemCompound(val cid: Long, val title: String, val formula: String, val molecularWeight: Double?, val smiles: String)

/**
 * PubChem's PUG REST service (NCBI, free, no key): a name, CAS number or synonym to the compound's structure. Flowstead
 * takes only the structure and lets the engine estimate the constants by Joback, so every property number is the
 * engine's; PubChem's own title, formula and weight are shown to confirm the right compound was found.
 */
object PubChem {
    const val BASE = "https://pubchem.ncbi.nlm.nih.gov/rest/pug"

    /** PubChem renamed its SMILES properties in 2025 (SMILES / ConnectivitySMILES); both spellings are asked for and read. */
    private val PROPERTIES = listOf("Title", "MolecularFormula", "MolecularWeight", "SMILES", "IsomericSMILES", "CanonicalSMILES", "ConnectivitySMILES")

    fun url(query: String): String {
        val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        return "$BASE/compound/name/$q/property/${PROPERTIES.joinToString(",")}/JSON"
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** The compounds in a PUG REST property table, best match first; empty when PubChem found none. */
    fun parse(body: String): List<PubChemCompound> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val rows = (root["PropertyTable"] as? JsonObject)?.get("Properties")?.jsonArray ?: return emptyList()
        return rows.mapNotNull { r ->
            val o = r as? JsonObject ?: return@mapNotNull null
            fun text(key: String) = o[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            // The stereo-free forms are enough for Joback, which has no stereo groups.
            val smiles = text("SMILES") ?: text("IsomericSMILES") ?: text("CanonicalSMILES") ?: text("ConnectivitySMILES") ?: return@mapNotNull null
            PubChemCompound(
                cid = o["CID"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0,
                title = text("Title") ?: "",
                formula = text("MolecularFormula") ?: "",
                molecularWeight = text("MolecularWeight")?.toDoubleOrNull(),
                smiles = smiles,
            )
        }
    }

    /** PubChem's fault message ("No CID found…"), or null. */
    fun fault(body: String): String? = runCatching {
        json.parseToJsonElement(body).jsonObject["Fault"]?.jsonObject?.get("Message")?.jsonPrimitive?.contentOrNull
    }.getOrNull()
}
