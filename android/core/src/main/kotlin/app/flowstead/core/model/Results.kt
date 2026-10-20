/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.api.ObjectStatus
import app.flowstead.core.api.Phases
import app.flowstead.core.api.SolveResult
import app.flowstead.core.api.SolveStatus
import app.flowstead.core.units.Quantity
import kotlinx.serialization.Serializable

/** A solve result together with the exact flowsheet it belongs to. */
@Serializable
data class StoredSolve(
    val result: SolveResult,
    /** SHA-256 of the flowsheet JSON that was solved. */
    val modelHash: String,
    val solvedAtMillis: Long,
)

/** Result property ids → display quantity and label. Keys match the engine's ObjectResult.properties. */
object ResultKeys {
    val STREAM = listOf(
        "temperature", "pressure", "vapourFraction", "molarFlow", "massFlow", "volumeFlow",
        "massEnthalpy", "massEntropy", "massHeatCapacity", "density", "molecularWeight", "viscosity", "compressibilityFactor",
    )
    val UNIT_OP = listOf("duty", "power", "pressureDrop", "outletPressure", "outletTemperature", "temperatureChange")

    fun quantity(key: String): Quantity = when (key) {
        "temperature", "outletTemperature" -> Quantity.Temperature
        "temperatureChange" -> Quantity.TemperatureDifference
        "pressure", "outletPressure" -> Quantity.Pressure
        "pressureDrop" -> Quantity.PressureDifference
        "vapourFraction", "phaseMoleFraction", "compressibilityFactor" -> Quantity.Fraction
        "molarFlow" -> Quantity.MolarFlow
        "massFlow" -> Quantity.MassFlow
        "volumeFlow" -> Quantity.VolumeFlow
        "massEnthalpy" -> Quantity.MassEnthalpy
        "massEntropy" -> Quantity.MassEntropy
        "massHeatCapacity" -> Quantity.MassHeatCapacity
        "density" -> Quantity.Density
        "molecularWeight" -> Quantity.MolecularWeight
        "viscosity" -> Quantity.Viscosity
        "duty", "power", "energyFlow" -> Quantity.Power
        else -> Quantity.Fraction
    }

    fun label(key: String): String = when (key) {
        "temperature" -> "Temperature"
        "pressure" -> "Pressure"
        "vapourFraction" -> "Vapour fraction"
        "phaseMoleFraction" -> "Phase fraction (molar)"
        "molarFlow" -> "Molar flow"
        "massFlow" -> "Mass flow"
        "volumeFlow" -> "Actual volume flow"
        "massEnthalpy" -> "Mass enthalpy"
        "massEntropy" -> "Mass entropy"
        "massHeatCapacity" -> "Cp (mass)"
        "density" -> "Density"
        "molecularWeight" -> "Molecular weight"
        "viscosity" -> "Viscosity"
        "compressibilityFactor" -> "Z factor"
        "duty" -> "Duty"
        "power" -> "Power"
        "pressureDrop" -> "Pressure drop"
        "outletPressure" -> "Outlet pressure"
        "outletTemperature" -> "Outlet temperature"
        "temperatureChange" -> "Temperature change"
        "energyFlow" -> "Energy flow"
        else -> key
    }
}

/**
 * Turns a stored solve into on-screen values. A number only ever comes from the engine's result;
 * anything missing is [DisplayValue.Missing] or [DisplayValue.Error] with the reason (brief §4).
 */
class ResultView(private val solve: StoredSolve?, currentHash: String) {
    val stale: Boolean = solve != null && solve.modelHash != currentHash
    val status: SolveStatus? = solve?.result?.status

    fun objectStatus(id: String): ObjectStatus? = solve?.result?.objects?.get(id)?.status

    /** Value of an object-level property, e.g. ("s1", "temperature"). */
    fun value(objectId: String, key: String): DisplayValue = lookup(objectId) { it.properties[key] }

    /** Value of a phase property, e.g. ("s1", "vapour", "density"). */
    fun phaseValue(objectId: String, phase: String, key: String): DisplayValue =
        lookup(objectId, absentReason = "${Phases.label(phase)} phase not present") { it.phases?.get(phase)?.properties?.get(key) }

    fun composition(objectId: String, phase: String = Phases.OVERALL, compound: String): DisplayValue =
        lookup(objectId, absentReason = "${Phases.label(phase)} phase not present") { it.phases?.get(phase)?.composition?.get(compound) }

    fun presentPhases(objectId: String): List<String> {
        val phases = solve?.result?.objects?.get(objectId)?.phases ?: return emptyList()
        return Phases.ORDER.filter { it in phases }
    }

    private fun lookup(
        objectId: String,
        absentReason: String = "Not returned by the engine",
        pick: (app.flowstead.core.api.ObjectResult) -> Double?,
    ): DisplayValue {
        val r = solve?.result ?: return DisplayValue.Missing("Not solved yet")
        val obj = r.objects[objectId] ?: return when (r.status) {
            SolveStatus.Invalid -> DisplayValue.Missing("Solve refused: fix the flowsheet issues")
            SolveStatus.Timeout -> DisplayValue.Error("Solve timed out")
            else -> DisplayValue.Missing("Added after the last solve")
        }
        return when (obj.status) {
            ObjectStatus.Error -> DisplayValue.Error(obj.error ?: "Calculation failed")
            ObjectStatus.NotSolved -> DisplayValue.Missing(obj.error ?: "Not calculated")
            ObjectStatus.Solved -> pick(obj)?.takeIf { it.isFinite() }
                ?.let { DisplayValue.Calculated(it, stale) }
                ?: DisplayValue.Missing(absentReason)
        }
    }

    companion object {
        val NONE = ResultView(null, "")
    }
}

/** SHA-256 of the canonical flowsheet JSON: identifies exactly which flowsheet a result belongs to. */
fun Flowsheet.modelHash(): String {
    val json = FlowsheetJson.encodeToString(Flowsheet.serializer(), this)
    return java.security.MessageDigest.getInstance("SHA-256").digest(json.toByteArray()).joinToString("") { "%02x".format(it) }
}
