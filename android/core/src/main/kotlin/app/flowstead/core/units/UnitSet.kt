/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.units

import kotlinx.serialization.Serializable

/** Which display unit to use for each quantity. */
@Serializable
data class UnitSet(val name: String, val units: Map<Quantity, String>) {
    fun unitFor(q: Quantity): EngUnit = Units.byIdOrNull(units[q] ?: q.siUnitId)
        ?.takeIf { it.quantity == q } ?: Units.si(q)

    fun with(q: Quantity, unit: EngUnit): UnitSet {
        require(unit.quantity == q) { "${unit.id} is not a ${q.name} unit" }
        return copy(name = CUSTOM, units = units + (q to unit.id))
    }

    companion object {
        const val CUSTOM = "Custom"

        /** HYSYS-style SI set. */
        val SI = UnitSet(
            "SI",
            mapOf(
                Quantity.Temperature to Units.C.id,
                Quantity.TemperatureDifference to Units.dC.id,
                Quantity.Pressure to Units.kPa.id,
                Quantity.PressureDifference to Units.dkPa.id,
                Quantity.MolarFlow to Units.kgmolPerH.id,
                Quantity.MassFlow to Units.kgPerH.id,
                Quantity.VolumeFlow to Units.m3PerH.id,
                Quantity.Power to Units.kW.id,
                Quantity.MassEnthalpy to Units.kJPerKg.id,
                Quantity.MolarEnthalpy to Units.kJPerKgmol.id,
                Quantity.MassEntropy to Units.kJPerKgK.id,
                Quantity.MassHeatCapacity to Units.cpKJPerKgK.id,
                Quantity.Density to Units.kgPerM3.id,
                Quantity.MolecularWeight to Units.kgPerKmol.id,
                Quantity.Viscosity to Units.cP.id,
                Quantity.Fraction to Units.fraction.id,
            ),
        )

        /** US oilfield set. */
        val FIELD = UnitSet(
            "Field",
            mapOf(
                Quantity.Temperature to Units.F.id,
                Quantity.TemperatureDifference to Units.dF.id,
                Quantity.Pressure to Units.psia.id,
                Quantity.PressureDifference to Units.dpsi.id,
                Quantity.MolarFlow to Units.lbmolPerH.id,
                Quantity.MassFlow to Units.lbPerH.id,
                Quantity.VolumeFlow to Units.bblPerD.id,
                Quantity.Power to Units.btuPerH.id,
                Quantity.MassEnthalpy to Units.btuPerLb.id,
                Quantity.MolarEnthalpy to Units.btuPerLbmol.id,
                Quantity.MassEntropy to Units.btuPerLbF.id,
                Quantity.MassHeatCapacity to Units.cpBtuPerLbF.id,
                Quantity.Density to Units.lbPerFt3.id,
                Quantity.MolecularWeight to Units.kgPerKmol.id,
                Quantity.Viscosity to Units.cP.id,
                Quantity.Fraction to Units.fraction.id,
            ),
        )

        val PRESETS = listOf(SI, FIELD)
    }
}
