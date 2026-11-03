/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/**
 * A black oil (D101): a reservoir fluid by its stock-tank oil gravity, gas gravity, producing GOR (Sm³ gas per m³ of
 * stock-tank oil) and water cut (fraction of the liquid volume), for the Black Oil package. Same as the engine's.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class BlackOil(
    val name: String,
    val oilSpecificGravity: Double,
    val gasSpecificGravity: Double,
    val gasOilRatio: Double,
    val waterCut: Double = 0.0,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val viscosity1: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val viscosityTemperature1: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val viscosity2: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val viscosityTemperature2: Double? = null,
) {
    val api: Double get() = 141.5 / oilSpecificGravity - 131.5

    /** What is wrong with the inputs, or null. Mirrors the engine validator. */
    fun problem(): String? = when {
        name.isBlank() -> "Give the black oil a name"
        !(oilSpecificGravity > 0.6 && oilSpecificGravity < 1.07) -> "The oil specific gravity must be between 0.6 and 1.07 (API 104 to 2)"
        !(gasSpecificGravity > 0.55 && gasSpecificGravity < 2.0) -> "The gas specific gravity must be between 0.55 and 2"
        !(gasOilRatio >= 0 && gasOilRatio < 2000) -> "The GOR must be from 0 to 2000 Sm³/m³"
        !(waterCut >= 0 && waterCut < 1) -> "The water cut must be from 0 to below 100 %"
        else -> null
    }

    companion object {
        const val PACKAGE = "Black Oil"
        fun sgFromApi(api: Double) = 141.5 / (api + 131.5)
    }
}
