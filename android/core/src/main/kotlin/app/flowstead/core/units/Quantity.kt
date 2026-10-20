/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.units

/**
 * Physical quantities shown in the app. Each has one SI base unit; the engine
 * sends and receives values only in that base unit.
 */
enum class Quantity(val siUnitId: String) {
    Temperature("K"),
    TemperatureDifference("dK"),
    Pressure("Pa"),
    PressureDifference("dPa"),
    MolarFlow("mol/s"),
    MassFlow("kg/s"),
    VolumeFlow("m3/s"),
    Power("W"),
    MassEnthalpy("J/kg"),
    MolarEnthalpy("J/mol"),
    MassEntropy("J/kg.K"),
    MassHeatCapacity("J/kg.K"),
    Density("kg/m3"),
    MolecularWeight("kg/kmol"),
    Viscosity("Pa.s"),
    Fraction("frac"),
    /** Dimensionless ratio, e.g. a compressor pressure ratio (not a fraction: can exceed 1). */
    Ratio("ratio"),
    /** Whole numbers such as iteration counts. */
    Count("count"),
}
