/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.units.Quantity

/** ISA-5.1 first letters for a controller's tag, from the variable it measures (D84). */
object ControllerTags {
    /** A tag the app gave (XIC-100, PIC-100…), which follows the measured variable; one the user typed is kept. */
    val GIVEN = Regex("^[A-Z]IC-(\\d+)$")

    fun letter(controlledProperty: String): String = when (runCatching { ResultKeys.quantity(controlledProperty) }.getOrNull()) {
        Quantity.Temperature, Quantity.TemperatureDifference -> "T"
        Quantity.Pressure, Quantity.PressureDifference -> "P"
        Quantity.MolarFlow, Quantity.MassFlow, Quantity.VolumeFlow -> "F"
        Quantity.Fraction -> "A" // analysis: composition, vapour fraction
        Quantity.Power -> "J"
        Quantity.Density -> "D"
        Quantity.Length -> "L"
        else -> "X"
    }
}
