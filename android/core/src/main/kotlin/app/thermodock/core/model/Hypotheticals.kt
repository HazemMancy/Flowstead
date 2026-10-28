/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.core.model

import kotlinx.serialization.Serializable

/**
 * A hypothetical (pseudo) component, as in HYSYS: the normal boiling point or the molecular weight, optionally the
 * specific gravity (60 °F/60 °F); the engine estimates the rest with DWSIM's petroleum correlations. Critical
 * temperature, critical pressure and acentric factor override the estimates. SI: K, Pa. Same as the engine's
 * Contracts.Hypothetical.
 */
@Serializable
data class Hypothetical(
    val name: String,
    val normalBoilingPoint: Double? = null,
    val molecularWeight: Double? = null,
    val specificGravity: Double? = null,
    val criticalTemperature: Double? = null,
    val criticalPressure: Double? = null,
    val acentricFactor: Double? = null,
) {
    /** What is wrong with the inputs, or null. Mirrors the engine validator's ranges. */
    fun problem(): String? {
        if (name.isBlank()) return "Give the hypothetical a name"
        if (normalBoilingPoint == null && molecularWeight == null) return "Give the normal boiling point or the molecular weight"
        fun out(v: Double?, lo: Double, hi: Double) = v != null && !(v >= lo && v <= hi)
        return when {
            out(normalBoilingPoint, 150.0, 1500.0) -> "Normal boiling point must be between 150 and 1500 K"
            out(molecularWeight, 16.0, 2000.0) -> "Molecular weight must be between 16 and 2000"
            out(specificGravity, 0.3, 1.6) -> "Specific gravity must be between 0.3 and 1.6"
            out(criticalTemperature, 150.0, 2000.0) -> "Critical temperature must be between 150 and 2000 K"
            out(criticalPressure, 1e5, 1e8) -> "Critical pressure must be between 1 and 1000 bar"
            out(acentricFactor, -0.5, 3.0) -> "Acentric factor must be between −0.5 and 3"
            criticalTemperature != null && normalBoilingPoint != null && criticalTemperature <= normalBoilingPoint ->
                "The critical temperature must be above the normal boiling point"
            else -> null
        }
    }

    companion object {
        /** Shown beside a hypothetical in component lists. */
        const val TAG = "Hypo"
    }
}
