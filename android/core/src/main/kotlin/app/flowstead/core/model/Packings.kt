/*
 * Flowstead — packed columns (D152, from Alireza's question: "do you plan to add packed column modeling?").
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.format.ValueFormat
import app.flowstead.core.units.Quantity

/**
 * A rigorous column's trays rated as a packed bed. The engine does the rating with DWSIM 10's own packing hydraulics
 * (engine/src/Flowstead.Engine.Worker/PackedColumns.cs) after the solve; the app only chooses the packing and shows
 * what came back. The choice sits in the column's refs, so it is sent with the flowsheet and a change marks the
 * results out of date.
 *
 * - [PACKING]: a name from DWSIM's catalogue ([RANDOM], [STRUCTURED]); absent, the column has sieve trays.
 * - [FLOOD]: design fraction of flood (0.70 when absent, DWSIM's own default for packings).
 * - [DIAMETER] and [HEIGHT] (m): the user's column diameter and bed height; absent, the engine sizes the diameter
 *   for the flood fraction (rounded up to 50 mm) and the bed as the stages times the HETP.
 */
object Packings {
    const val PACKING = "packing"
    const val FLOOD = "packingFlood"
    const val DIAMETER = "packingDiameter"
    const val HEIGHT = "packingHeight"
    const val DEFAULT_FLOOD = 0.70

    /**
     * DWSIM 10.2.15's catalogue (DWSIM.SharedClassesCSharp, PackingCatalogue: Perry 7th ed. Tables 14-7a/b and
     * Seader & Henley 2nd ed. Table 6.8), as its display names, less the 12 rows without the data any of its
     * capacity models needs (no packing factor, no Billet-Schultes constants).
     */
    val RANDOM = listOf(
        "Berl saddles Ceramic 25 mm",
        "Berl saddles Ceramic 13 mm",
        "Bialecki rings Metal 50 mm",
        "Bialecki rings Metal 35 mm",
        "Bialecki rings Metal 25 mm",
        "DIN-PAK rings Plastic 70 mm",
        "DIN-PAK rings Plastic 47 mm",
        "Envi Pac rings Plastic 80 mm, no. 3",
        "Envi Pac rings Plastic 60 mm, no. 2",
        "Envi Pac rings Plastic 32 mm, no. 1",
        "Glitsch rings Metal 30 PMK",
        "Glitsch rings Metal 30 P",
        "Glitsch CMR rings Metal 1.5 in",
        "Glitsch CMR rings Metal 1.5 in, T",
        "Glitsch CMR rings Metal 1.0 in",
        "Cascade minirings Metal 1.5 in CMR",
        "Cascade minirings Metal 1.0 in CMR",
        "Hackettes Plastic 45 mm",
        "Hiflow rings Ceramic 75 mm",
        "Hiflow rings Ceramic 50 mm",
        "Hiflow rings Ceramic 38 mm",
        "Hiflow rings Metal 50 mm",
        "Hiflow rings Metal 25 mm",
        "Hiflow rings Plastic 90 mm",
        "Hiflow rings Plastic 50 mm",
        "Intalox saddles Ceramic 50 mm",
        "Intalox saddles Plastic 50 mm",
        "NORPAC rings Plastic 50 mm",
        "NORPAC rings Plastic 35 mm",
        "NORPAC rings Plastic 25 mm, type B",
        "NORPAC rings Plastic 25 mm",
        "Pall rings Ceramic 50 mm",
        "Pall rings Metal 50 mm",
        "Pall rings Metal 35 mm",
        "Pall rings Metal 25 mm",
        "Pall rings Metal 15 mm",
        "Pall rings Plastic 50 mm",
        "Pall rings Plastic 35 mm",
        "Pall rings Plastic 25 mm",
        "Raflux rings Plastic 15 mm",
        "Ralu flow Plastic 1",
        "Ralu flow Plastic 2",
        "Ralu rings Plastic 50 mm",
        "Ralu rings Plastic 38 mm",
        "Ralu rings Plastic 25 mm",
        "Ralu rings Metal 50 mm",
        "Ralu rings Metal 38 mm",
        "Ralu rings Metal 25 mm",
        "Raschig rings Ceramic 25 mm",
        "Raschig rings Ceramic 15 mm",
        "Raschig rings Ceramic 10 mm",
        "Raschig rings Ceramic 6 mm",
        "Raschig rings Metal 15 mm",
        "Raschig Super-rings Metal 0.3",
        "Raschig Super-rings Metal 0.5",
        "Raschig Super-rings Metal 1",
        "Raschig Super-rings Metal 2",
        "Raschig Super-rings Metal 3",
        "Raschig Super-rings Plastic 2",
        "Tellerettes Plastic 25 mm",
        "Top-Pak rings Aluminum 50 mm",
        "VSP rings Metal 50 mm, no. 2",
        "VSP rings Metal 25 mm, no. 1",
        "Raschig rings Ceramic 13 mm",
        "Raschig rings Ceramic 25 mm (Perry)",
        "Raschig rings Ceramic 50 mm",
        "Raschig rings Ceramic 75 mm",
        "Raschig rings Metal 19 mm",
        "Raschig rings Metal 25 mm",
        "Raschig rings Metal 50 mm",
        "Raschig rings Metal 75 mm",
        "Pall rings Metal 16 mm",
        "Pall rings Metal 25 mm (Perry)",
        "Pall rings Metal 38 mm",
        "Pall rings Metal 50 mm (Perry)",
        "Pall rings Metal 90 mm",
        "Cascade mini rings (CMR) Metal 1",
        "Cascade mini rings (CMR) Metal 1.5",
        "Cascade mini rings (CMR) Metal 2.5",
        "Cascade mini rings (CMR) Metal 3",
        "Cascade mini rings (CMR) Plastic 1A",
        "Cascade mini rings (CMR) Plastic 3A",
        "Berl saddles Ceramic 6 mm",
        "Berl saddles Ceramic 13 mm (Perry)",
        "Berl saddles Ceramic 25 mm (Perry)",
        "Berl saddles Ceramic 38 mm",
        "Berl saddles Ceramic 50 mm",
        "Intalox saddles Ceramic 13 mm",
        "Intalox saddles Ceramic 25 mm",
        "Intalox saddles Ceramic 50 mm (Perry)",
        "Intalox saddles Ceramic 75 mm",
        "Fleximax Metal 300",
        "Fleximax Metal 400",
        "Metal Intalox (IMTP) Metal 25 mm",
        "Metal Intalox (IMTP) Metal 40 mm",
        "Metal Intalox (IMTP) Metal 50 mm",
        "Metal Intalox (IMTP) Metal 70 mm",
        "Nutter rings Metal 1",
        "Nutter rings Metal 2",
        "Nutter rings Metal 2.5",
        "Nutter rings Metal 3.0",
        "Pall rings Plastic 25 mm (Perry)",
        "Pall rings Plastic 50 mm (Perry)",
        "Pall rings Plastic 90 mm",
        "Intalox saddles Plastic 1",
        "Intalox saddles Plastic 2",
        "Snowflake Plastic",
        "Nor-Pac Plastic 25 mm, 1",
        "Nor-Pac Plastic 38 mm, 1.5",
        "Nor-Pac Plastic 50 mm, 2.0",
        "Tri-Pack Plastic 25 mm, 1",
        "Tri-Pack Plastic 50 mm, 2",
        "VSP Metal 25 mm, 1 (Perry)",
        "VSP Metal 50 mm, 2 (Perry)",
    )
    val STRUCTURED = listOf(
        "Euroform Plastic PN-110",
        "Gempak Metal A2 T-304",
        "Impulse Ceramic 100",
        "Impulse Metal 250",
        "Koch-Sulzer Metal CY",
        "Koch-Sulzer Metal BX",
        "Mellapak Plastic 250 Y",
        "Montz Metal B1-100",
        "Montz Metal B1-200",
        "Montz Metal B1-300",
        "Montz Plastic C1-200",
        "Montz Plastic C2-200",
        "Ralu Pak Metal YC-250",
        "Flexipac Sheet metal 1",
        "Flexipac Sheet metal 2",
        "Flexipac Sheet metal 3",
        "Flexiramic Ceramic 28",
        "Flexiramic Ceramic 48",
        "Flexiramic Ceramic 88",
        "Gempak Sheet metal 4A",
        "Gempak Sheet metal 3A",
        "Gempak Sheet metal 2A",
        "Intalox structured Sheet metal 1T",
        "Intalox structured Sheet metal 2T",
        "Intalox structured Sheet metal 3T",
        "Max-Pak Sheet metal",
        "Mellapak Sheet metal 125Y",
        "Mellapak Sheet metal 250Y",
        "Mellapak Sheet metal 350Y",
        "Mellapak Sheet metal 500Y",
        "Montz-Pak Sheet metal B1-250",
        "Ralu Pak Sheet metal 250YC",
        "Sulzer Gauze AX",
        "Sulzer Gauze BX",
        "Sulzer Gauze CY",
    )
    val ALL = RANDOM + STRUCTURED

    /** The packing of column [o], or null for trays. */
    fun of(o: SimObject): String? = o.refs[PACKING]?.takeIf { it.isNotBlank() }

    /** A number of the packing basis from the refs (SI), or null. */
    fun number(o: SimObject, key: String): Double? = o.refs[key]?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }

    /** The text a number is kept as in the refs (invariant, SI). */
    fun text(si: Double): String = si.toString()

    /** What the sizing section shows for a packed column, from the engine's rating; null when it returned none. */
    fun size(results: ResultView, o: SimObject): EquipmentSizingResult? {
        val packing = of(o) ?: return null
        fun v(key: String) = (results.value(o.id, key) as? DisplayValue.Calculated)?.si
        val d = v("packingDiameter")
        val warnings = results.warnings(o.id).map { it.text.removePrefix("${o.tag}: ") }
        if (d == null) return EquipmentSizingResult(
            emptyList(),
            warnings = warnings.ifEmpty { listOf("The solve has no packing rating for this column: solve again.") },
        )
        val lines = mutableListOf(SizingLine("Packing", text = results.text(o.id, "packing") ?: packing))
        fun line(label: String, key: String, q: Quantity) = v(key)?.let { lines += SizingLine(label, it, q) }
        line("Diameter required", "packingRequiredDiameter", Quantity.Length)
        line("Diameter", "packingDiameter", Quantity.Length)
        v("packingFloodFraction")?.let { lines += SizingLine("Highest flood at this diameter, %", it * 100, Quantity.Ratio) }
        line("Stage at the highest flood", "packingLimitingStage", Quantity.Count)
        line("Flood velocity there", "packingFloodVelocity", Quantity.Velocity)
        line("Packed stages", "packingStages", Quantity.Count)
        line("HETP (mean)", "packingHetp", Quantity.Length)
        line("Bed height", "packingHeight", Quantity.Length)
        line("Largest pressure gradient", "packingMaxPressureGradient", Quantity.PressureGradient)
        line("Pressure drop over the bed", "packingPressureDrop", Quantity.PressureDifference)
        v("packingMinWettingRatio")?.let { lines += SizingLine("Lowest liquid rate over the minimum wetting rate", it, Quantity.Ratio) }
        val target = v("packingFloodTarget") ?: number(o, FLOOD) ?: DEFAULT_FLOOD
        val notes = listOfNotNull(
            "Rated by DWSIM's packing hydraulics: ${results.text(o.id, "packingModel") ?: "its packing correlations"}; data from ${results.text(o.id, "packingSource") ?: "DWSIM's catalogue"}.",
            "Each stage between the condenser and the reboiler is one stage of bed, rated with its own solved vapour and liquid. " +
                (if (number(o, DIAMETER) == null) "The diameter puts the most loaded stage at ${ValueFormat.number(target * 100, 0)} % of flood, rounded up to 50 mm. " else "The diameter is the one given. ") +
                (if (number(o, HEIGHT) == null) "The bed is the stages times the HETP by Kister's rules of thumb (1.5 ft per inch of nominal size for random packings; 100/a + 4 in for structured ones; not less than the diameter below 2 ft)." else "The bed height is the one given."),
            "Allow for liquid distributors, bed supports and redistributors (a bed every 6 m or so of random packing) on top of the bed height.",
        )
        return EquipmentSizingResult(lines, notes, warnings)
    }
}
