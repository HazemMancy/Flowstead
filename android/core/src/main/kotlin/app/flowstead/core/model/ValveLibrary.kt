/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

/**
 * Valve types with their typical IEC 60534-2-1 factors at full travel (Annex A, Table A.1), as HYSYS's valve library
 * offers them. They are typical, not a maker's data: the user can overwrite FL and xT with the vendor's figures.
 */
object ValveLibrary {
    data class Type(val id: String, val label: String, val fl: Double, val xt: Double)

    val TYPES = listOf(
        Type("globe", "Globe, contoured plug (flow to open)", 0.90, 0.72),
        Type("globeCage", "Globe, cage guided (flow to open)", 0.90, 0.75),
        Type("globeClose", "Globe, contoured plug (flow to close)", 0.80, 0.55),
        Type("angle", "Angle, contoured plug (flow to close)", 0.80, 0.65),
        Type("ballStandard", "Ball, standard bore", 0.55, 0.15),
        Type("ballSegment", "Ball, segmented (V-notch)", 0.60, 0.30),
        Type("butterflySwing", "Butterfly, swing-through (70°)", 0.62, 0.35),
        Type("butterflyFluted", "Butterfly, fluted vane (70°)", 0.67, 0.38),
        Type("butterflyOffset", "Butterfly, offset seat (70°)", 0.67, 0.35),
        Type("rotaryPlug", "Eccentric rotary plug", 0.85, 0.61),
    )

    /** The type whose factors the valve has (no factors given: the globe valve the engine assumes), or null if custom. */
    fun of(valve: SimObject): Type? {
        val fl = valve.specs[SpecKey.VALVE_FL]?.si
        val xt = valve.specs[SpecKey.VALVE_XT]?.si
        if (fl == null && xt == null) return TYPES.first()
        return TYPES.firstOrNull { it.fl == (fl ?: 0.9) && it.xt == (xt ?: 0.72) }
    }
}
