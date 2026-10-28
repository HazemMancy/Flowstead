/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.compounds

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One compound of DWSIM's databases (shared/compounds/catalogue.json, built by
 * engine/tools/compounds/build_catalogue.py). Constants are database values in SI; null where the
 * database has none.
 */
@Serializable
data class Compound(
    val name: String,
    val cas: String = "",
    val formula: String = "",
    /** DWSIM's structural formula when it differs from [formula], e.g. CH3(CH2)5CH3. */
    val structure: String = "",
    val smiles: String = "",
    val database: String = "",
    val family: String = "Other",
    val molecularWeight: Double? = null,
    val normalBoilingPointK: Double? = null,
    val meltingPointK: Double? = null,
    val criticalTemperatureK: Double? = null,
    val criticalPressurePa: Double? = null,
    val criticalVolumeM3PerMol: Double? = null,
    val criticalCompressibility: Double? = null,
    val acentricFactor: Double? = null,
    val dipoleMomentCoulombMetre: Double? = null,
    val idealGasEnthalpyOfFormationJPerMol: Double? = null,
    /** Engineering shorthand: CO2, nC4, TEG… */
    val aliases: List<String> = emptyList(),
    /** Oil & gas favourites, listed first. */
    val common: Boolean = false,
)

/** The whole compound library, searchable offline with the engine's ranking. */
class CompoundCatalogue(val all: List<Compound>) {
    private val byName = all.associateBy { it.name }

    /** Families in a fixed, engineer-friendly order; any unknown ones at the end. */
    val families: List<String> = FAMILY_ORDER.filter { f -> all.any { it.family == f } } +
        all.map { it.family }.distinct().filter { it !in FAMILY_ORDER }.sorted()

    val databases: List<String> = all.map { it.database }.distinct().sortedBy { dbRank(it) * 1000 + it.length }

    operator fun get(name: String): Compound? = byName[name]

    /**
     * Shorthand, exact name or CAS first, then formula, prefix and substring matches; ChemSep before
     * the other databases (DWSIM's own default). An empty query lists the common compounds first.
     */
    fun search(query: String, family: String? = null, database: String? = null, limit: Int = 200): List<Compound> {
        val q = query.trim()
        val pool = all.asSequence()
            .filter { family == null || (family == COMMON && it.common) || it.family == family }
            .filter { database == null || it.database == database }
        val ranked = if (q.isEmpty()) {
            pool.sortedWith(compareBy({ if (it.common) 0 else 1 }, { dbRank(it.database) }, { it.name.lowercase() }))
        } else {
            pool.mapNotNull { c -> rank(c, q)?.let { c to it } }
                .sortedWith(compareBy({ it.second }, { dbRank(it.first.database) }, { it.first.name.length }, { it.first.name }))
                .map { it.first }
        }
        return ranked.take(limit).toList()
    }

    private fun rank(c: Compound, q: String): Int? {
        val bare = if (c.name.startsWith("n-", ignoreCase = true)) c.name.substring(2) else c.name
        return when {
            c.aliases.any { it.equals(q, ignoreCase = true) } -> -1
            c.name.equals(q, ignoreCase = true) || bare.equals(q, ignoreCase = true) || c.cas == q -> 0
            c.formula.equals(q, ignoreCase = true) || c.structure.equals(q, ignoreCase = true) -> 1
            c.name.startsWith(q, ignoreCase = true) || bare.startsWith(q, ignoreCase = true) -> 2
            c.name.contains(q, ignoreCase = true) || c.aliases.any { it.startsWith(q, ignoreCase = true) } -> 3
            else -> null
        }
    }

    companion object {
        /** Pseudo-family used by the filter chips: the [Compound.common] set. */
        const val COMMON = "Common"

        val FAMILY_ORDER = listOf(
            "Paraffins", "Naphthenes", "Olefins & acetylenes", "Aromatics", "Hydrocarbons",
            "Inorganic & gases", "Oxygenates", "Nitrogen compounds", "Sulfur compounds",
            "Halogenated & refrigerants", "Electrolytes & salts", "Food & biomass", "Other",
        )

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String) = CompoundCatalogue(json.decodeFromString(ListSerializer(Compound.serializer()), text))

        private fun dbRank(db: String) = when (db) {
            "ChemSep" -> 0
            "CoolProp" -> 1
            "ChEDL Thermo" -> 2
            else -> 3
        }
    }
}
