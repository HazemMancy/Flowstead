/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

/**
 * Pipe sizes from ASME B36.10M (welded and seamless wrought steel: schedules 10–160, STD, XS, XXS) and
 * ASME B36.19M (stainless: 5S, 10S, 40S, 80S), NPS 1/8 to 36. Dimensions are the standards' inch values;
 * inside diameter = outside diameter − 2 × wall. Where several designations share a wall they are one
 * entry ("40 / STD / 40S"). Schedules a size does not have in either standard are absent.
 */
object PipeSizes {
    private const val IN = 0.0254

    data class Schedule(val label: String, val wallM: Double)

    data class Size(val nps: String, val dn: Int, val outsideDiameterM: Double, val schedules: List<Schedule>) {
        val label: String get() = "NPS $nps (DN $dn)"
        fun schedule(label: String): Schedule? = schedules.firstOrNull { it.label == label }
        fun insideDiameterM(s: Schedule): Double = outsideDiameterM - 2 * s.wallM
    }

    val ALL: List<Size> = listOf(
        size("1/8", 6, 0.405, "10 / 10S" to 0.049, "40 / STD / 40S" to 0.068, "80 / XS / 80S" to 0.095),
        size("1/4", 8, 0.540, "10 / 10S" to 0.065, "40 / STD / 40S" to 0.088, "80 / XS / 80S" to 0.119),
        size("3/8", 10, 0.675, "10 / 10S" to 0.065, "40 / STD / 40S" to 0.091, "80 / XS / 80S" to 0.126),
        size("1/2", 15, 0.840, "5 / 5S" to 0.065, "10 / 10S" to 0.083, "40 / STD / 40S" to 0.109, "80 / XS / 80S" to 0.147, "160" to 0.188, "XXS" to 0.294),
        size("3/4", 20, 1.050, "5 / 5S" to 0.065, "10 / 10S" to 0.083, "40 / STD / 40S" to 0.113, "80 / XS / 80S" to 0.154, "160" to 0.219, "XXS" to 0.308),
        size("1", 25, 1.315, "5 / 5S" to 0.065, "10 / 10S" to 0.109, "40 / STD / 40S" to 0.133, "80 / XS / 80S" to 0.179, "160" to 0.250, "XXS" to 0.358),
        size("1 1/4", 32, 1.660, "5 / 5S" to 0.065, "10 / 10S" to 0.109, "40 / STD / 40S" to 0.140, "80 / XS / 80S" to 0.191, "160" to 0.250, "XXS" to 0.382),
        size("1 1/2", 40, 1.900, "5 / 5S" to 0.065, "10 / 10S" to 0.109, "40 / STD / 40S" to 0.145, "80 / XS / 80S" to 0.200, "160" to 0.281, "XXS" to 0.400),
        size("2", 50, 2.375, "5 / 5S" to 0.065, "10 / 10S" to 0.109, "40 / STD / 40S" to 0.154, "80 / XS / 80S" to 0.218, "160" to 0.344, "XXS" to 0.436),
        size("2 1/2", 65, 2.875, "5 / 5S" to 0.083, "10 / 10S" to 0.120, "40 / STD / 40S" to 0.203, "80 / XS / 80S" to 0.276, "160" to 0.375, "XXS" to 0.552),
        size("3", 80, 3.500, "5 / 5S" to 0.083, "10 / 10S" to 0.120, "40 / STD / 40S" to 0.216, "80 / XS / 80S" to 0.300, "160" to 0.438, "XXS" to 0.600),
        size("3 1/2", 90, 4.000, "5 / 5S" to 0.083, "10 / 10S" to 0.120, "40 / STD / 40S" to 0.226, "80 / XS / 80S" to 0.318),
        size("4", 100, 4.500, "5 / 5S" to 0.083, "10 / 10S" to 0.120, "40 / STD / 40S" to 0.237, "80 / XS / 80S" to 0.337, "120" to 0.438, "160" to 0.531, "XXS" to 0.674),
        size("5", 125, 5.563, "5 / 5S" to 0.109, "10 / 10S" to 0.134, "40 / STD / 40S" to 0.258, "80 / XS / 80S" to 0.375, "120" to 0.500, "160" to 0.625, "XXS" to 0.750),
        size("6", 150, 6.625, "5 / 5S" to 0.109, "10 / 10S" to 0.134, "40 / STD / 40S" to 0.280, "80 / XS / 80S" to 0.432, "120" to 0.562, "160" to 0.719, "XXS" to 0.864),
        size("8", 200, 8.625, "5S" to 0.109, "10S" to 0.148, "20" to 0.250, "30" to 0.277, "40 / STD / 40S" to 0.322, "60" to 0.406, "80 / XS / 80S" to 0.500, "100" to 0.594, "120" to 0.719, "140" to 0.812, "XXS" to 0.875, "160" to 0.906),
        size("10", 250, 10.750, "5S" to 0.134, "10S" to 0.165, "20" to 0.250, "30" to 0.307, "40 / STD / 40S" to 0.365, "60 / XS / 80S" to 0.500, "80" to 0.594, "100" to 0.719, "120" to 0.844, "140 / XXS" to 1.000, "160" to 1.125),
        size("12", 300, 12.750, "5S" to 0.156, "10S" to 0.180, "20" to 0.250, "30" to 0.330, "STD / 40S" to 0.375, "40" to 0.406, "XS / 80S" to 0.500, "60" to 0.562, "80" to 0.688, "100" to 0.844, "120 / XXS" to 1.000, "140" to 1.125, "160" to 1.312),
        size("14", 350, 14.000, "5S" to 0.156, "10S" to 0.188, "10" to 0.250, "20" to 0.312, "30 / STD" to 0.375, "40" to 0.438, "XS" to 0.500, "60" to 0.594, "80" to 0.750, "100" to 0.938, "120" to 1.094, "140" to 1.250, "160" to 1.406),
        size("16", 400, 16.000, "5S" to 0.165, "10S" to 0.188, "10" to 0.250, "20" to 0.312, "30 / STD" to 0.375, "40 / XS" to 0.500, "60" to 0.656, "80" to 0.844, "100" to 1.031, "120" to 1.219, "140" to 1.438, "160" to 1.594),
        size("18", 450, 18.000, "5S" to 0.165, "10S" to 0.188, "10" to 0.250, "20" to 0.312, "STD" to 0.375, "30" to 0.438, "XS" to 0.500, "40" to 0.562, "60" to 0.750, "80" to 0.938, "100" to 1.156, "120" to 1.375, "140" to 1.562, "160" to 1.781),
        size("20", 500, 20.000, "5S" to 0.188, "10S" to 0.218, "10" to 0.250, "20 / STD" to 0.375, "30 / XS" to 0.500, "40" to 0.594, "60" to 0.812, "80" to 1.031, "100" to 1.281, "120" to 1.500, "140" to 1.750, "160" to 1.969),
        size("22", 550, 22.000, "5S" to 0.188, "10S" to 0.218, "10" to 0.250, "20 / STD" to 0.375, "30 / XS" to 0.500, "60" to 0.875, "80" to 1.125, "100" to 1.375, "120" to 1.625, "140" to 1.875, "160" to 2.125),
        size("24", 600, 24.000, "5S" to 0.218, "10 / 10S" to 0.250, "20 / STD" to 0.375, "XS" to 0.500, "30" to 0.562, "40" to 0.688, "60" to 0.969, "80" to 1.219, "100" to 1.531, "120" to 1.812, "140" to 2.062, "160" to 2.344),
        size("26", 650, 26.000, "10" to 0.312, "STD" to 0.375, "20 / XS" to 0.500),
        size("28", 700, 28.000, "10" to 0.312, "STD" to 0.375, "20 / XS" to 0.500, "30" to 0.625),
        size("30", 750, 30.000, "5S" to 0.250, "10 / 10S" to 0.312, "STD" to 0.375, "20 / XS" to 0.500, "30" to 0.625),
        size("32", 800, 32.000, "10" to 0.312, "STD" to 0.375, "20 / XS" to 0.500, "30" to 0.625, "40" to 0.688),
        size("34", 850, 34.000, "10" to 0.312, "STD" to 0.375, "20 / XS" to 0.500, "30" to 0.625, "40" to 0.688),
        size("36", 900, 36.000, "10" to 0.312, "STD" to 0.375, "20 / XS" to 0.500, "30" to 0.625, "40" to 0.750),
    )

    fun size(nps: String): Size? = ALL.firstOrNull { it.nps == nps }

    private fun size(nps: String, dn: Int, odInch: Double, vararg schedules: Pair<String, Double>) =
        Size(nps, dn, odInch * IN, schedules.map { (label, wall) -> Schedule(label, wall * IN) })
}
