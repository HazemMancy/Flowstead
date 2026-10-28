/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.api.PvtExperiments
import app.flowstead.core.api.PvtObservation
import app.flowstead.core.units.Quantity

/** The lab measurements the PVT regression can match (D53), each a PVT result value the engine reports. */
object PvtTuning {
    data class Kind(val id: String, val label: String, val experiment: String, val key: String, val quantity: Quantity, val atPressure: Boolean)

    val KINDS = listOf(
        Kind("psat", "Saturation pressure", PvtExperiments.SATURATION, "saturationPressure", Quantity.Pressure, atPressure = false),
        Kind("cceVrel", "CCE relative volume", PvtExperiments.CCE, "relativeVolume", Quantity.Ratio, atPressure = true),
        Kind("cceDensity", "CCE density", PvtExperiments.CCE, "density", Quantity.Density, atPressure = true),
        Kind("cceDropout", "CCE liquid dropout", PvtExperiments.CCE, "liquidDropout", Quantity.Fraction, atPressure = true),
        Kind("dlBo", "DL oil formation volume factor", PvtExperiments.DL, "oilFvf", Quantity.Ratio, atPressure = true),
        Kind("dlRs", "DL solution GOR (Sm³/m³)", PvtExperiments.DL, "solutionGor", Quantity.Ratio, atPressure = true),
    )

    fun kind(o: PvtObservation): Kind? = KINDS.firstOrNull { it.experiment == o.experiment && it.key == o.key }

    /** What stops the regression, or null. */
    fun problem(fs: Flowsheet, composition: Map<String, Double>?, observations: List<PvtObservation>): String? = when {
        composition == null -> "The stream has no composition yet"
        fs.hypotheticals.none { (composition[it.name] ?: 0.0) > 0 } -> "Tuning adjusts the plus fraction: add a hypothetical component to this fluid"
        observations.isEmpty() -> "Add at least one lab measurement"
        observations.any { kind(it)?.atPressure == true && it.pressure == null } -> "Give the pressure of each measurement"
        observations.any { it.value == 0.0 } -> "Measured values must not be zero"
        else -> null
    }

    /** The flowsheet with the tuned hypotheticals' critical constants (and the tuned kij, when any) in place, the rest kept. */
    fun apply(fs: Flowsheet, tuned: List<Hypothetical>, kij: List<BinaryParameter> = emptyList()): Flowsheet =
        fs.copy(interactionParameters = kij.ifEmpty { fs.interactionParameters }, hypotheticals = fs.hypotheticals.map { h -> tuned.firstOrNull { it.name == h.name }?.let { t -> h.copy(criticalTemperature = t.criticalTemperature, criticalPressure = t.criticalPressure, acentricFactor = t.acentricFactor) } ?: h })
}
