/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

/**
 * The enthalpy and entropy basis a flowsheet is solved on (D122, D153). The engine's DWSIM packages report enthalpy from an
 * ideal-gas reference at 25 °C without the heat of formation; HYSYS includes each compound's heat of formation and
 * absolute entropy. The engine adds them when asked ("formation"), for Peng-Robinson, SRK and Lee-Kesler.
 */
object EnthalpyBases {
    /** HYSYS's basis: heats of formation and absolute entropies included. */
    const val FORMATION = "formation"

    /** DWSIM's own reference: an ideal gas at 25 °C has zero enthalpy. */
    const val DWSIM = "dwsim"

    /** Whether some stream types an enthalpy or an entropy: such a case keeps the basis its values were written on. */
    fun typesEnergy(fs: Flowsheet): Boolean =
        fs.objects.any { SpecKey.MASS_ENTHALPY in it.specs || MASS_ENTROPY in it.specs }

    /** The basis in force: the one chosen, else HYSYS's, unless the case types an enthalpy or entropy (then DWSIM's). */
    fun effective(fs: Flowsheet): String = when (fs.enthalpyBasis) {
        FORMATION, DWSIM -> fs.enthalpyBasis
        else -> if (typesEnergy(fs)) DWSIM else FORMATION
    }

    /**
     * What a solve sends: the basis resolved as the engine reads it ("formation", or none for DWSIM's) and the streams that
     * get bubble and dew points ([saturationFor]: null for all, empty for none).
     */
    fun forEngine(fs: Flowsheet, saturationFor: List<String>? = null): Flowsheet =
        fs.copy(enthalpyBasis = if (effective(fs) == FORMATION) FORMATION else null, saturationFor = saturationFor)

    private const val MASS_ENTROPY = "massEntropy"
}
