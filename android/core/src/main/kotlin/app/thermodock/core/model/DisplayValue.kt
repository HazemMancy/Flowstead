/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.core.model

/**
 * Where a value on screen came from. Drives the HYSYS-style colour code and guarantees
 * that only numbers the user typed or the engine returned are ever rendered.
 */
sealed interface DisplayValue {
    /** Typed by the user (blue). [si] is in the quantity's SI base unit. */
    data class Specified(val si: Double) : DisplayValue

    /** Returned by the engine (near-black). [stale] when the flowsheet changed since the solve. */
    data class Calculated(val si: Double, val stale: Boolean = false) : DisplayValue

    /** Needed but not yet known: under-specified (amber). Rendered as "—" plus [reason]. */
    data class Missing(val reason: String) : DisplayValue

    /** The engine reported a failure for this value or its object (red). */
    data class Error(val reason: String) : DisplayValue
}
