/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.api.CompoundBasis

/**
 * How a composition is entered and shown (D78): mole, mass or ideal liquid volume (at 60 °F, as HYSYS's "liquid volume"),
 * each as fractions or percent. The flowsheet always stores mole fractions, which is what the engine takes; mass and
 * volume need each compound's molecular weight and standard liquid density, which come from the engine.
 */
enum class CompositionBasis(val label: String, val kind: Kind, val percent: Boolean) {
    MoleFraction("Mole fraction", Kind.Mole, false),
    MolePercent("Mole %", Kind.Mole, true),
    MassFraction("Mass fraction", Kind.Mass, false),
    MassPercent("Mass %", Kind.Mass, true),
    VolumeFraction("Liquid volume fraction", Kind.Volume, false),
    VolumePercent("Liquid volume %", Kind.Volume, true);

    enum class Kind { Mole, Mass, Volume }

    /** What the whole adds up to: 1, or 100 for percent. */
    val total: Double get() = if (percent) 100.0 else 1.0

    val needsEngineData: Boolean get() = kind != Kind.Mole

    /** The weight that turns a mole amount of [c] into this basis's amount: 1, its MW, or MW / ρ. Null if unknown. */
    private fun weight(c: String, data: Map<String, CompoundBasis>?): Double? = when (kind) {
        Kind.Mole -> 1.0
        Kind.Mass -> data?.get(c)?.molecularWeight?.takeIf { it > 0 }
        Kind.Volume -> data?.get(c)?.let { b -> b.stdLiquidDensity?.takeIf { it > 0 }?.let { b.molecularWeight / it } }
    }

    /** Compounds of [compounds] this basis can't be used for (no molecular weight or liquid density from the engine). */
    fun missing(compounds: List<String>, data: Map<String, CompoundBasis>?): List<String> = compounds.filter { weight(it, data) == null }

    /** Mole fractions shown in this basis, adding up to [total]; null if a weight is missing. */
    fun fromMole(x: Map<String, Double>, data: Map<String, CompoundBasis>?): Map<String, Double>? {
        val amounts = x.mapValues { (c, xi) -> weight(c, data)?.let { xi * it } ?: return null }
        val sum = amounts.values.sum().takeIf { it > 0 } ?: return x.mapValues { 0.0 }
        return amounts.mapValues { it.value / sum * total }
    }

    /**
     * Values entered in this basis as mole fractions. Mole fractions and mole percent keep their sum as typed (so an
     * unnormalised entry is shown as such); mass and volume are normalised, since only their ratios define the mixture.
     * Null if a weight is missing.
     */
    fun toMole(values: Map<String, Double>, data: Map<String, CompoundBasis>?): Map<String, Double>? {
        if (kind == Kind.Mole) return values.mapValues { it.value / total }
        val moles = values.mapValues { (c, v) -> weight(c, data)?.let { v / it } ?: return null }
        val sum = moles.values.sum().takeIf { it > 0 } ?: return values.mapValues { 0.0 }
        return moles.mapValues { it.value / sum }
    }
}
