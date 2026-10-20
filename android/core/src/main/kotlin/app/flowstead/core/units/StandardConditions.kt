/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.units

/** Physical constants and the reference conditions behind gauge pressure and standard gas volumes. */
object StandardConditions {
    /** Molar gas constant, J/(mol·K) (CODATA 2018, exact). */
    const val R = 8.314462618

    /** Default atmospheric reference for gauge pressures, Pa. Overridable per project (D9). */
    const val DEFAULT_PATM_PA = 101_325.0

    /** Metric standard: 15 °C, 101.325 kPa (ISO 13443). */
    const val METRIC_STD_T_K = 288.15
    const val METRIC_STD_P_PA = 101_325.0

    /** US field standard: 60 °F, 14.696 psia. */
    const val FIELD_STD_T_K = (60.0 + 459.67) * 5.0 / 9.0
    const val FIELD_STD_P_PA = 14.696 * Conversions.PSI_PA

    /** Ideal-gas molar volume at metric standard conditions, m³/mol. */
    val metricMolarVolume: Double = R * METRIC_STD_T_K / METRIC_STD_P_PA

    /** Ideal-gas molar volume at field standard conditions, m³/mol. */
    val fieldMolarVolume: Double = R * FIELD_STD_T_K / FIELD_STD_P_PA
}

/** Exact (by definition) conversion factors to SI. */
object Conversions {
    const val LB_KG = 0.45359237
    const val FT_M = 0.3048
    const val IN_M = 0.0254
    const val FT3_M3 = FT_M * FT_M * FT_M
    const val PSI_PA = 6_894.757293168361
    const val BAR_PA = 100_000.0
    const val BTU_IT_J = 1_055.05585262
    const val HOUR_S = 3_600.0
    const val DAY_S = 86_400.0
    const val US_GAL_M3 = 3.785411784e-3
    const val BBL_M3 = 42.0 * US_GAL_M3
    const val HP_W = 745.69987158227022 // mechanical horsepower
    const val CAL_IT_J = 4.1868
}
