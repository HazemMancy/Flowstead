/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.units

import app.flowstead.core.units.Conversions.BAR_PA
import app.flowstead.core.units.Conversions.BBL_M3
import app.flowstead.core.units.Conversions.BTU_IT_J
import app.flowstead.core.units.Conversions.CAL_IT_J
import app.flowstead.core.units.Conversions.DAY_S
import app.flowstead.core.units.Conversions.FT3_M3
import app.flowstead.core.units.Conversions.HOUR_S
import app.flowstead.core.units.Conversions.HP_W
import app.flowstead.core.units.Conversions.LB_KG
import app.flowstead.core.units.Conversions.PSI_PA
import app.flowstead.core.units.Conversions.US_GAL_M3

/** Context a conversion may depend on: the atmospheric reference used for gauge pressures. */
data class UnitContext(val atmosphericPressurePa: Double = StandardConditions.DEFAULT_PATM_PA) {
    init {
        require(atmosphericPressurePa.isFinite() && atmosphericPressurePa > 0.0) {
            "Atmospheric pressure must be positive, was $atmosphericPressurePa Pa"
        }
    }
}

/**
 * A display unit. Every unit is affine in its SI base: `si = value * factor + offset(ctx)`.
 * [id] is stable and is what gets persisted; [symbol] is what the user sees.
 */
class EngUnit internal constructor(
    val id: String,
    val symbol: String,
    val quantity: Quantity,
    private val factor: Double,
    private val offset: (UnitContext) -> Double = { 0.0 },
    /** Decimal places used when formatting a value in this unit. */
    val decimals: Int,
) {
    fun toSi(value: Double, ctx: UnitContext = UnitContext()): Double = value * factor + offset(ctx)
    fun fromSi(si: Double, ctx: UnitContext = UnitContext()): Double = (si - offset(ctx)) / factor
    override fun toString() = symbol
}

object Units {
    private val all = mutableListOf<EngUnit>()

    private fun u(
        id: String, symbol: String, q: Quantity, factor: Double, decimals: Int,
        offset: (UnitContext) -> Double = { 0.0 },
    ) = EngUnit(id, symbol, q, factor, offset, decimals).also { all += it }

    // Temperature
    val K = u("K", "K", Quantity.Temperature, 1.0, 2)
    val C = u("C", "°C", Quantity.Temperature, 1.0, 2, offset = { 273.15 })
    val F = u("F", "°F", Quantity.Temperature, 5.0 / 9.0, 2, offset = { 459.67 * 5.0 / 9.0 })
    val R = u("R", "°R", Quantity.Temperature, 5.0 / 9.0, 2)

    // Temperature difference (no offset)
    val dK = u("dK", "K", Quantity.TemperatureDifference, 1.0, 2)
    val dC = u("dC", "°C", Quantity.TemperatureDifference, 1.0, 2)
    val dF = u("dF", "°F", Quantity.TemperatureDifference, 5.0 / 9.0, 2)

    // Pressure (absolute and gauge)
    val Pa = u("Pa", "Pa", Quantity.Pressure, 1.0, 0)
    val kPa = u("kPa", "kPa", Quantity.Pressure, 1e3, 2)
    val kPag = u("kPag", "kPag", Quantity.Pressure, 1e3, 2, offset = { it.atmosphericPressurePa })
    val MPa = u("MPa", "MPa", Quantity.Pressure, 1e6, 4)
    val bara = u("bara", "bara", Quantity.Pressure, BAR_PA, 3)
    val barg = u("barg", "barg", Quantity.Pressure, BAR_PA, 3, offset = { it.atmosphericPressurePa })
    val psia = u("psia", "psia", Quantity.Pressure, PSI_PA, 2)
    val psig = u("psig", "psig", Quantity.Pressure, PSI_PA, 2, offset = { it.atmosphericPressurePa })

    // Pressure difference (no offset)
    val dPa = u("dPa", "Pa", Quantity.PressureDifference, 1.0, 0)
    val dkPa = u("dkPa", "kPa", Quantity.PressureDifference, 1e3, 2)
    val dbar = u("dbar", "bar", Quantity.PressureDifference, BAR_PA, 3)
    val dpsi = u("dpsi", "psi", Quantity.PressureDifference, PSI_PA, 2)

    // Molar flow, including ideal-gas standard volumes
    val molPerS = u("mol/s", "mol/s", Quantity.MolarFlow, 1.0, 4)
    val kgmolPerH = u("kgmol/h", "kgmol/h", Quantity.MolarFlow, 1e3 / HOUR_S, 2)
    val lbmolPerH = u("lbmol/h", "lbmol/h", Quantity.MolarFlow, LB_KG * 1e3 / HOUR_S, 2)
    val sm3PerH = u("Sm3/h", "Sm³/h", Quantity.MolarFlow, 1.0 / (StandardConditions.metricMolarVolume * HOUR_S), 1)
    val sm3PerD = u("Sm3/d", "Sm³/d", Quantity.MolarFlow, 1.0 / (StandardConditions.metricMolarVolume * DAY_S), 0)
    val mmscfd = u("MMSCFD", "MMSCFD", Quantity.MolarFlow, 1e6 * FT3_M3 / (StandardConditions.fieldMolarVolume * DAY_S), 4)

    // Mass flow
    val kgPerS = u("kg/s", "kg/s", Quantity.MassFlow, 1.0, 4)
    val kgPerH = u("kg/h", "kg/h", Quantity.MassFlow, 1.0 / HOUR_S, 1)
    val tPerD = u("t/d", "t/d", Quantity.MassFlow, 1e3 / DAY_S, 2)
    val lbPerH = u("lb/h", "lb/h", Quantity.MassFlow, LB_KG / HOUR_S, 1)

    // Actual volume flow
    val m3PerS = u("m3/s", "m³/s", Quantity.VolumeFlow, 1.0, 5)
    val m3PerH = u("m3/h", "m³/h", Quantity.VolumeFlow, 1.0 / HOUR_S, 3)
    val bblPerD = u("bbl/d", "bbl/d", Quantity.VolumeFlow, BBL_M3 / DAY_S, 1)
    val usgpm = u("USGPM", "USGPM", Quantity.VolumeFlow, US_GAL_M3 / 60.0, 2)

    // Power and duty
    val W = u("W", "W", Quantity.Power, 1.0, 0)
    val kW = u("kW", "kW", Quantity.Power, 1e3, 2)
    val MW = u("MW", "MW", Quantity.Power, 1e6, 4)
    val btuPerH = u("Btu/h", "Btu/h", Quantity.Power, BTU_IT_J / HOUR_S, 0)
    val mmbtuPerH = u("MMBtu/h", "MMBtu/h", Quantity.Power, 1e6 * BTU_IT_J / HOUR_S, 4)
    val hp = u("hp", "hp", Quantity.Power, HP_W, 2)

    // Enthalpy, entropy, heat capacity
    val jPerKg = u("J/kg", "J/kg", Quantity.MassEnthalpy, 1.0, 0)
    val kJPerKg = u("kJ/kg", "kJ/kg", Quantity.MassEnthalpy, 1e3, 3)
    val btuPerLb = u("Btu/lb", "Btu/lb", Quantity.MassEnthalpy, BTU_IT_J / LB_KG, 3)
    val jPerMol = u("J/mol", "J/mol", Quantity.MolarEnthalpy, 1.0, 1)
    val kJPerKgmol = u("kJ/kgmol", "kJ/kgmol", Quantity.MolarEnthalpy, 1.0, 1)
    val btuPerLbmol = u("Btu/lbmol", "Btu/lbmol", Quantity.MolarEnthalpy, BTU_IT_J / (LB_KG * 1e3), 1)
    val kJPerKgK = u("kJ/kg.K", "kJ/kg·K", Quantity.MassEntropy, 1e3, 4)
    val btuPerLbF = u("Btu/lb.F", "Btu/lb·°F", Quantity.MassEntropy, CAL_IT_J * 1e3, 4)
    val cpKJPerKgK = u("cp:kJ/kg.K", "kJ/kg·K", Quantity.MassHeatCapacity, 1e3, 4)
    val cpBtuPerLbF = u("cp:Btu/lb.F", "Btu/lb·°F", Quantity.MassHeatCapacity, CAL_IT_J * 1e3, 4)

    // Density, molecular weight, viscosity, fractions
    val kgPerM3 = u("kg/m3", "kg/m³", Quantity.Density, 1.0, 2)
    val lbPerFt3 = u("lb/ft3", "lb/ft³", Quantity.Density, LB_KG / FT3_M3, 3)
    val kgPerKmol = u("kg/kmol", "kg/kmol", Quantity.MolecularWeight, 1.0, 3)
    val paS = u("Pa.s", "Pa·s", Quantity.Viscosity, 1.0, 6)
    val cP = u("cP", "cP", Quantity.Viscosity, 1e-3, 4)
    val fraction = u("frac", "", Quantity.Fraction, 1.0, 4)
    val percent = u("%", "%", Quantity.Fraction, 0.01, 2)

    private val byId: Map<String, EngUnit> = all.associateBy { it.id }.also {
        check(it.size == all.size) { "Duplicate unit ids" }
    }

    fun byId(id: String): EngUnit = byId[id] ?: throw IllegalArgumentException("Unknown unit '$id'")
    fun byIdOrNull(id: String): EngUnit? = byId[id]
    fun forQuantity(q: Quantity): List<EngUnit> = all.filter { it.quantity == q }
    fun si(q: Quantity): EngUnit = byId(q.siUnitId)
    val allUnits: List<EngUnit> get() = all.toList()
}

/** Convert [value] from one unit to another of the same quantity. */
fun convert(value: Double, from: EngUnit, to: EngUnit, ctx: UnitContext = UnitContext()): Double {
    require(from.quantity == to.quantity) { "Cannot convert ${from.quantity} to ${to.quantity}" }
    return to.fromSi(from.toSi(value, ctx), ctx)
}
