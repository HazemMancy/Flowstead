/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ReactionType(val label: String) {
    @SerialName("conversion") Conversion("Conversion"),
    @SerialName("equilibrium") Equilibrium("Equilibrium"),
    @SerialName("kinetic") Kinetic("Kinetic"),
}

@Serializable
enum class ReactionPhase(val label: String) {
    @SerialName("mixture") Mixture("Mixture"),
    @SerialName("vapour") Vapour("Vapour"),
    @SerialName("liquid") Liquid("Liquid"),
}

/**
 * A reaction, as in the engine's Contracts.Reaction. Coefficients are negative for reactants.
 * Kinetic: r = A·exp(−E/RT)·Π c^order in mol/(m³·s), concentrations in mol/m³, E in J/mol.
 */
@Serializable
data class Reaction(
    val id: String,
    val name: String,
    val type: ReactionType,
    val stoichiometry: Map<String, Double> = emptyMap(),
    val baseCompound: String = "",
    val phase: ReactionPhase = ReactionPhase.Mixture,
    /** Conversion reactions: fraction of the base compound converted (0–1). */
    val conversion: Double? = null,
    /** Equilibrium reactions: temperature approach (K). */
    val approachTemperature: Double? = null,
    val forwardOrders: Map<String, Double> = emptyMap(),
    val reverseOrders: Map<String, Double> = emptyMap(),
    val forwardA: Double? = null,
    val forwardE: Double? = null,
    val reverseA: Double? = null,
    val reverseE: Double? = null,
) {
    /** "CH4 + 2 O2 → CO2 + 2 H2O" style, with compound names. */
    fun equation(): String {
        fun side(entries: List<Map.Entry<String, Double>>) = entries.joinToString(" + ") { (c, v) ->
            val n = kotlin.math.abs(v)
            (if (n == 1.0) "" else (if (n == kotlin.math.floor(n)) n.toLong().toString() else n.toString()) + " ") + c
        }
        val (reactants, products) = stoichiometry.entries.partition { it.value < 0 }
        val arrow = if (type == ReactionType.Equilibrium) " ⇌ " else " → "
        return side(reactants) + arrow + side(products)
    }
}

/** Keys reactors use in refs. Same strings as the engine's ReactorKeys. */
object ReactorKeys {
    /** Comma-separated ids of the reactions the reactor runs (not Gibbs). */
    const val REACTIONS = "reactions"
    /** adiabatic (default), isothermal or outlet_temperature. */
    const val MODE = "mode"
    val MODES = listOf("adiabatic" to "Adiabatic", "isothermal" to "Isothermal", "outlet_temperature" to "Outlet temperature")
}

/** Reactor layout and checks. Mirrors the engine's Reactors.cs and Validator.ValidateReactor / ValidateReactions. */
object Reactors {
    fun isReactor(t: ObjectType) = t == ObjectType.ConversionReactor || t == ObjectType.EquilibriumReactor ||
        t == ObjectType.GibbsReactor || t == ObjectType.Cstr || t == ObjectType.Pfr

    /** The reaction type a reactor runs, or null for Gibbs (no reactions). */
    fun reactionType(t: ObjectType): ReactionType? = when (t) {
        ObjectType.ConversionReactor -> ReactionType.Conversion
        ObjectType.EquilibriumReactor -> ReactionType.Equilibrium
        ObjectType.Cstr, ObjectType.Pfr -> ReactionType.Kinetic
        else -> null
    }

    fun reactionIds(o: SimObject): List<String> =
        o.refs[ReactorKeys.REACTIONS].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun reactions(fs: Flowsheet, o: SimObject): List<Reaction> = reactionIds(o).mapNotNull { fs.reaction(it) }

    /** Reactants of the reactor's reactions, whose conversions the engine reports. */
    fun reactants(fs: Flowsheet, o: SimObject): List<String> =
        reactions(fs, o).flatMap { r -> r.stoichiometry.filterValues { it < 0 }.keys }.distinct()

    /** What is wrong with a reaction, or null. */
    fun problem(fs: Flowsheet, r: Reaction): String? {
        if (r.stoichiometry.count { it.value < 0 } < 1 || r.stoichiometry.count { it.value > 0 } < 1) return "give at least one reactant and one product"
        r.stoichiometry.keys.firstOrNull { it !in fs.compounds }?.let { return "$it is not in the component list" }
        if (r.stoichiometry.values.any { !it.isFinite() || it == 0.0 }) return "coefficients must be non-zero numbers"
        if ((r.stoichiometry[r.baseCompound] ?: 0.0) >= 0) return "the base compound must be a reactant"
        return when (r.type) {
            ReactionType.Conversion -> when (val x = r.conversion) {
                null -> "give the conversion"
                else -> if (x <= 0 || x > 1) "conversion must be above 0 and at most 100 %" else null
            }
            ReactionType.Equilibrium -> if (r.phase == ReactionPhase.Mixture) "choose the vapour or liquid phase" else null
            ReactionType.Kinetic -> when {
                r.forwardA == null || r.forwardA <= 0 -> "give a positive pre-exponential factor A"
                r.forwardE == null -> "give the activation energy E"
                (r.reverseA ?: 0.0) < 0 -> "the reverse A can't be negative"
                else -> null
            }
        }
    }

    /** Null when nothing is wrong beyond what was added to [needs]. */
    fun check(fs: Flowsheet, o: SimObject, needs: MutableList<String>): SpecStatus? {
        val type = reactionType(o.type)
        if (type != null) {
            val ids = reactionIds(o)
            if (ids.isEmpty()) needs += "${type.label.lowercase()} reactions"
            for (id in ids) {
                val r = fs.reaction(id) ?: return SpecStatus.Invalid("A reaction it uses no longer exists")
                if (r.type != type) return SpecStatus.Invalid("${r.name} is a ${r.type.label.lowercase()} reaction")
                problem(fs, r)?.let { return SpecStatus.Invalid("${r.name}: $it") }
            }
        }
        val s = o.specs
        if (o.refs[ReactorKeys.MODE] == "outlet_temperature") {
            s[SpecKey.OUTLET_TEMPERATURE]?.let { if (it.si <= 0) return SpecStatus.Invalid("Outlet temperature must be above absolute zero") }
                ?: run { needs += "outlet temperature" }
        }
        s[SpecKey.PRESSURE_DROP]?.let { if (it.si < 0) return SpecStatus.Invalid("Pressure drop can't be negative") }
        if (o.type == ObjectType.Cstr || o.type == ObjectType.Pfr) {
            s[SpecKey.VOLUME]?.let { if (it.si <= 0) return SpecStatus.Invalid("Volume must be positive") } ?: run { needs += "volume" }
        }
        if (o.type == ObjectType.Pfr) {
            s[SpecKey.REACTOR_LENGTH]?.let { if (it.si <= 0) return SpecStatus.Invalid("Length must be positive") } ?: run { needs += "length" }
        }
        return null
    }
}
