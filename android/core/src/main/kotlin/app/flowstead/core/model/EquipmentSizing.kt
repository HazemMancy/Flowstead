/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.units.Quantity
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The engineering data library (D62): typical design values with their source, as defaults the user replaces with
 * their company's. Nothing here is a result; sizing tools use these only where the user gave no value.
 */
object DataLibrary {
    /** A service's typical overall heat-transfer coefficient range, W/m²·K; the default is the middle. */
    data class UService(val id: String, val group: String, val label: String, val low: Double, val high: Double) {
        val typical: Double get() = (low + high) / 2
    }

    /** Coulson & Richardson, Chemical Engineering vol. 6, Table 12.1 (shell and tube), and GPSA section 10 (air coolers, bare tube). */
    val U_SERVICES: List<UService> = listOf(
        UService("water_water", "Heat exchangers", "Water – water", 800.0, 1500.0),
        UService("organic_organic", "Heat exchangers", "Organic solvents – organic solvents", 100.0, 300.0),
        UService("light_light", "Heat exchangers", "Light oils – light oils", 100.0, 400.0),
        UService("heavy_heavy", "Heat exchangers", "Heavy oils – heavy oils", 50.0, 300.0),
        UService("gas_gas", "Heat exchangers", "Gases – gases", 10.0, 50.0),
        UService("organic_water", "Coolers", "Organic solvents – water", 250.0, 750.0),
        UService("light_water", "Coolers", "Light oils – water", 350.0, 900.0),
        UService("heavy_water", "Coolers", "Heavy oils – water", 60.0, 300.0),
        UService("gas_water", "Coolers", "Gases – water", 20.0, 300.0),
        UService("organic_brine", "Coolers", "Organic solvents – brine", 150.0, 500.0),
        UService("water_brine", "Coolers", "Water – brine", 600.0, 1200.0),
        UService("steam_water", "Heaters", "Steam – water", 1500.0, 4000.0),
        UService("steam_organic", "Heaters", "Steam – organic solvents", 500.0, 1000.0),
        UService("steam_light", "Heaters", "Steam – light oils", 300.0, 900.0),
        UService("steam_heavy", "Heaters", "Steam – heavy oils", 60.0, 450.0),
        UService("steam_gas", "Heaters", "Steam – gases", 30.0, 300.0),
        UService("hot_oil_heavy", "Heaters", "Hot oil – heavy oils", 50.0, 300.0),
        UService("hot_oil_gas", "Heaters", "Hot oil – gases", 20.0, 200.0),
        UService("aqueous_vap_water", "Condensers", "Aqueous vapours – water", 1000.0, 1500.0),
        UService("organic_vap_water", "Condensers", "Organic vapours – water", 700.0, 1000.0),
        UService("organic_nc_water", "Condensers", "Organics with some non-condensables – water", 500.0, 700.0),
        UService("vacuum_water", "Condensers", "Vacuum condensers – water", 200.0, 500.0),
        UService("steam_aqueous_boil", "Vaporisers", "Steam – aqueous solutions", 1000.0, 1500.0),
        UService("steam_light_boil", "Vaporisers", "Steam – light organics", 900.0, 1200.0),
        UService("steam_heavy_boil", "Vaporisers", "Steam – heavy organics", 600.0, 900.0),
        UService("air_light_liquid", "Air coolers (bare tube)", "Light hydrocarbon liquids", 400.0, 570.0),
        UService("air_heavy_liquid", "Air coolers (bare tube)", "Heavy oils", 60.0, 280.0),
        UService("air_gas_low", "Air coolers (bare tube)", "Hydrocarbon gas, 3–20 barg", 170.0, 280.0),
        UService("air_gas_high", "Air coolers (bare tube)", "Hydrocarbon gas, 35–100 barg", 280.0, 450.0),
        UService("air_condensing", "Air coolers (bare tube)", "Light hydrocarbon condensers", 450.0, 600.0),
        UService("air_water", "Air coolers (bare tube)", "Water (jacket and process)", 680.0, 800.0),
    )

    fun service(id: String?): UService? = U_SERVICES.firstOrNull { it.id == id }

    /** IEC 60072 standard motor ratings, kW. */
    val MOTORS_KW: List<Double> = listOf(
        0.37, 0.55, 0.75, 1.1, 1.5, 2.2, 3.0, 4.0, 5.5, 7.5, 11.0, 15.0, 18.5, 22.0, 30.0, 37.0, 45.0, 55.0, 75.0, 90.0,
        110.0, 132.0, 160.0, 200.0, 250.0, 315.0, 355.0, 400.0, 450.0, 500.0, 560.0, 630.0, 710.0, 800.0, 900.0, 1000.0,
        1120.0, 1250.0, 1400.0, 1600.0, 1800.0, 2000.0, 2240.0, 2500.0, 2800.0, 3150.0, 3550.0, 4000.0, 4500.0, 5000.0,
        5600.0, 6300.0, 7100.0, 8000.0, 9000.0, 10000.0, 11200.0, 12500.0, 14000.0, 16000.0, 18000.0, 20000.0,
    )

    /** API 610 driver margin over the pump's rated power: 125 % to 22 kW, 115 % to 55 kW, 110 % above. */
    fun pumpMargin(shaftW: Double): Double = when {
        shaftW <= 22e3 -> 1.25
        shaftW <= 55e3 -> 1.15
        else -> 1.10
    }

    /** API 617 / API 619: a motor rated for at least 110 % of the compressor's greatest power. */
    const val COMPRESSOR_MARGIN = 1.10

    fun motor(requiredW: Double): Double? = MOTORS_KW.firstOrNull { it * 1e3 >= requiredW * (1 - 1e-9) }?.times(1e3)

    /** Default utility temperatures (K): cooling water 30 → 40 °C, low-pressure steam condensing at 150 °C, air in at 35 °C. */
    const val COOLING_WATER_IN = 303.15
    const val COOLING_WATER_OUT = 313.15
    const val STEAM_TEMPERATURE = 423.15
    const val AIR_IN = 308.15
}

/**
 * The user's sizing basis for equipment other than separators (D62). Null fields take the data library's value.
 * Temperatures K, U W/m²·K, times s.
 */
@Serializable
data class EquipmentSizingInput(
    /** A [DataLibrary.U_SERVICES] id; its typical U unless [u] is given. */
    val service: String? = null,
    val u: Double? = null,
    /** Heater, cooler or air cooler: the utility's (or air's) inlet and outlet temperatures; equal for condensing steam. */
    val utilityIn: Double? = null,
    val utilityOut: Double? = null,
    /** Driver margin over the shaft power, as a factor; null is API 610 / 617's. */
    val margin: Double? = null,
    /** Tank: working liquid residence time, and the fraction of the volume the working liquid fills. */
    val residenceTime: Double? = null,
    val fillFraction: Double? = null,
    /** Tray columns (D65): tray spacing (m), design fraction of flood, downcomer fraction of the area, tray efficiency, system (foaming) factor. */
    val traySpacing: Double? = null,
    val floodFraction: Double? = null,
    val downcomerFraction: Double? = null,
    val trayEfficiency: Double? = null,
    val systemFactor: Double? = null,
)

/** One line of a sizing result: an SI value in [quantity], or a text. */
data class SizingLine(val label: String, val si: Double? = null, val quantity: Quantity? = null, val text: String? = null)

data class EquipmentSizingResult(val lines: List<SizingLine>, val notes: List<String> = emptyList(), val warnings: List<String> = emptyList())

/**
 * Sizing for exchangers, heaters, coolers, air coolers, pumps, compressors, expanders and tanks (D62), from the solved
 * duties, powers, temperatures and flows. Area = Q / (U F LMTD) with F for 1-shell-pass, 2-tube-pass shells in series
 * (Bowman, Mueller & Nagle); drivers as the next IEC motor above the API margin; tanks from a residence time.
 */
object EquipmentSizing {
    val TYPES = setOf(
        ObjectType.HeatExchanger, ObjectType.Heater, ObjectType.Cooler, ObjectType.AirCooler,
        ObjectType.Pump, ObjectType.Compressor, ObjectType.Expander, ObjectType.Tank,
        ObjectType.DistillationColumn, ObjectType.Absorber, ObjectType.ReboiledAbsorber, ObjectType.RefluxedAbsorber,
    )

    val COLUMNS = setOf(ObjectType.DistillationColumn, ObjectType.Absorber, ObjectType.ReboiledAbsorber, ObjectType.RefluxedAbsorber)

    /** The library service a type starts from. */
    fun defaultService(type: ObjectType): String? = when (type) {
        ObjectType.HeatExchanger -> "light_light"
        ObjectType.Cooler -> "light_water"
        ObjectType.Heater -> "steam_light"
        ObjectType.AirCooler -> "air_light_liquid"
        else -> null
    }

    fun lmtd(dt1: Double, dt2: Double): Double? {
        if (dt1 <= 0 || dt2 <= 0) return null
        return if (abs(dt1 - dt2) < 1e-6) dt1 else (dt1 - dt2) / ln(dt1 / dt2)
    }

    /** F for [shells] 1–2 shells in series (hot T1 → T2, cold t1 → t2); null when the duty can't be done in that many. */
    fun f(t1Hot: Double, t2Hot: Double, t1Cold: Double, t2Cold: Double, shells: Int): Double? {
        val r = (t1Hot - t2Hot) / (t2Cold - t1Cold)
        val p = (t2Cold - t1Cold) / (t1Hot - t1Cold)
        if (!(p > 0 && p < 1) || !(r > 0) || !r.isFinite()) return null
        // P per shell for N shells in series.
        val p1 = if (abs(r - 1) < 1e-6) p / (shells - (shells - 1) * p) else {
            val x = ((1 - p * r) / (1 - p)).pow(1.0 / shells)
            (1 - x) / (r - x)
        }
        val s = sqrt(r * r + 1)
        val num = if (abs(r - 1) < 1e-6) s * p1 / (1 - p1) else s * ln((1 - p1) / (1 - p1 * r)) / (r - 1)
        val a = 2 - p1 * (r + 1 - s)
        val b = 2 - p1 * (r + 1 + s)
        if (a <= 0 || b <= 0 || a / b <= 1) return null
        val fv = num / ln(a / b)
        return fv.takeIf { it.isFinite() && it > 0 && it <= 1.0 + 1e-9 }
    }

    /** Fewest shells (up to 6) whose F is at least 0.75, with that F. */
    fun shells(t1Hot: Double, t2Hot: Double, t1Cold: Double, t2Cold: Double): Pair<Int, Double>? {
        for (n in 1..6) f(t1Hot, t2Hot, t1Cold, t2Cold, n)?.takeIf { it >= 0.75 }?.let { return n to it }
        return null
    }

    fun size(fs: Flowsheet, results: ResultView, o: SimObject, input: EquipmentSizingInput): EquipmentSizingResult? {
        fun calc(id: String, key: String) = (results.value(id, key) as? DisplayValue.Calculated)?.si
        fun stream(side: PortSide, index: Int) = Ports.of(o.type).firstOrNull { it.side == side && it.index == index && it.kind == PortKind.Material }
            ?.let { fs.streamAt(o.id, it) }
        fun t(side: PortSide, index: Int) = stream(side, index)?.let { calc(it.id, "temperature") }
        return when (o.type) {
            ObjectType.HeatExchanger -> {
                val q = calc(o.id, "duty") ?: return null
                val a = t(PortSide.Inlet, 0) to t(PortSide.Outlet, 0)
                val b = t(PortSide.Inlet, 1) to t(PortSide.Outlet, 1)
                if (a.first == null || a.second == null || b.first == null || b.second == null) return null
                val (hot, cold) = if (a.first!! >= b.first!!) a to b else b to a
                exchanger(abs(q), hot.first!!, hot.second!!, cold.first!!, cold.second!!, input, ObjectType.HeatExchanger, emptyList())
            }
            ObjectType.Heater, ObjectType.Cooler, ObjectType.AirCooler -> {
                val q = calc(o.id, "duty") ?: return null
                val tin = t(PortSide.Inlet, 0) ?: return null
                val tout = t(PortSide.Outlet, 0) ?: return null
                val heating = tout > tin
                val (uIn, uOut) = when {
                    o.type == ObjectType.AirCooler -> (input.utilityIn ?: DataLibrary.AIR_IN) to (input.utilityOut ?: ((input.utilityIn ?: DataLibrary.AIR_IN) + 15.0))
                    heating -> (input.utilityIn ?: DataLibrary.STEAM_TEMPERATURE) to (input.utilityOut ?: input.utilityIn ?: DataLibrary.STEAM_TEMPERATURE)
                    else -> (input.utilityIn ?: DataLibrary.COOLING_WATER_IN) to (input.utilityOut ?: DataLibrary.COOLING_WATER_OUT)
                }
                val notes = listOf(
                    when {
                        o.type == ObjectType.AirCooler -> "Air ${fmtC(uIn)} → ${fmtC(uOut)} °C (data library: 35 °C in, 15 K rise)."
                        heating -> "Heating medium ${fmtC(uIn)} → ${fmtC(uOut)} °C (data library: LP steam condensing at 150 °C)."
                        else -> "Cooling medium ${fmtC(uIn)} → ${fmtC(uOut)} °C (data library: cooling water 30 → 40 °C)."
                    },
                )
                if (heating) exchanger(abs(q), uIn, uOut, tin, tout, input, o.type, notes)
                else exchanger(abs(q), tin, tout, uIn, uOut, input, o.type, notes)
            }
            ObjectType.Pump, ObjectType.Compressor, ObjectType.Expander -> {
                val w = abs(calc(o.id, "power") ?: return null)
                val margin = input.margin ?: when (o.type) {
                    ObjectType.Pump -> DataLibrary.pumpMargin(w)
                    else -> DataLibrary.COMPRESSOR_MARGIN
                }
                val rated = w * margin
                val motor = DataLibrary.motor(rated)
                val lines = mutableListOf(
                    SizingLine(if (o.type == ObjectType.Expander) "Shaft power delivered" else "Shaft power", w, Quantity.Power),
                    SizingLine("Margin", text = "${"%.0f".format(margin * 100)} %" + if (input.margin == null) (if (o.type == ObjectType.Pump) " (API 610)" else " (API 617)") else ""),
                    SizingLine(if (o.type == ObjectType.Expander) "Generator at least" else "Driver at least", rated, Quantity.Power),
                )
                lines += if (motor != null) SizingLine(if (o.type == ObjectType.Expander) "Standard generator (IEC 60072)" else "Standard motor (IEC 60072)", motor, Quantity.Power)
                else SizingLine("Standard motor", text = "above 20 MW: a turbine or a special motor")
                EquipmentSizingResult(lines)
            }
            ObjectType.Tank -> {
                val liquid = stream(PortSide.Outlet, 1) ?: return null
                val ql = calc(liquid.id, "volumeFlow") ?: return null
                val time = input.residenceTime ?: 600.0
                val fill = input.fillFraction ?: 0.85
                val v = ql * time / fill
                // Vertical tank, H = 1.5 D (the data library's proportion for a process tank).
                val d = cbrt(4 * v / (1.5 * PI))
                EquipmentSizingResult(
                    listOf(
                        SizingLine("Liquid flow", ql, Quantity.VolumeFlow),
                        SizingLine("Working volume", ql * time, Quantity.Volume),
                        SizingLine("Nominal volume", v, Quantity.Volume),
                        SizingLine("Diameter (H = 1.5 D)", d, Quantity.Length),
                        SizingLine("Height", 1.5 * d, Quantity.Length),
                    ),
                    notes = listOf("Residence time ${"%.0f".format(time / 60)} min, filled to ${"%.0f".format(fill * 100)} %" +
                        if (input.residenceTime == null || input.fillFraction == null) " (data library: 10 min, 85 %)." else "."),
                )
            }
            in COLUMNS -> {
                val profile = results.profiles(o.id) ?: return null
                val ts = input.traySpacing ?: ColumnSizing.DEFAULT_TRAY_SPACING
                val ff = input.floodFraction ?: ColumnSizing.DEFAULT_FLOOD
                val dc = input.downcomerFraction ?: ColumnSizing.DEFAULT_DOWNCOMER
                val eff = input.trayEfficiency ?: ColumnSizing.DEFAULT_EFFICIENCY
                val r = ColumnSizing.size(o.type, profile, ts, ff, dc, eff, input.systemFactor ?: 1.0)
                    ?: return EquipmentSizingResult(emptyList(), warnings = listOf("The solve has no stage properties for this column: solve again with this engine version."))
                val worst = r.trays.first { it.stage == r.controllingStage }
                EquipmentSizingResult(
                    listOf(
                        SizingLine("Diameter required", r.requiredDiameter, Quantity.Length),
                        SizingLine("Diameter", r.diameter, Quantity.Length),
                        SizingLine("Controlling stage", r.controllingStage.toDouble(), Quantity.Count),
                        SizingLine("Flood velocity there", worst.floodVelocity, Quantity.Velocity),
                        SizingLine("Flow parameter there", worst.flowParameter, Quantity.Ratio),
                        SizingLine("Theoretical trays", r.trays.size.toDouble(), Quantity.Count),
                        SizingLine("Actual trays", r.actualTrays.toDouble(), Quantity.Count),
                        SizingLine("Height (tangent to tangent)", r.height, Quantity.Length),
                    ),
                    notes = listOf(
                        "Fair's flooding correlation at ${"%.0f".format(ff * 100)} % of flood, ${"%.0f".format(ts * 1000)} mm tray spacing, " +
                            "${"%.0f".format(dc * 100)} % downcomer area, ${"%.0f".format(eff * 100)} % tray efficiency; " +
                            "${ColumnSizing.TOP_SPACE} m above the top tray and ${ColumnSizing.BOTTOM_SPACE} m below the bottom one.",
                    ),
                    warnings = r.warnings,
                )
            }
            else -> null
        }
    }

    private fun fmtC(k: Double) = "%.0f".format(k - 273.15)

    private fun exchanger(
        q: Double, hotIn: Double, hotOut: Double, coldIn: Double, coldOut: Double, input: EquipmentSizingInput,
        type: ObjectType, notes: List<String>,
    ): EquipmentSizingResult {
        val warnings = mutableListOf<String>()
        val service = DataLibrary.service(input.service ?: defaultService(type))
        val u = input.u ?: service?.typical
        val lines = mutableListOf(SizingLine("Duty", q, Quantity.Power))
        val lm = lmtd(hotIn - coldOut, hotOut - coldIn)
        if (lm == null) {
            warnings += "The temperatures cross (hot out ${fmtC(hotOut)} °C against cold in ${fmtC(coldIn)} °C): no counter-current LMTD."
            return EquipmentSizingResult(lines, notes, warnings)
        }
        lines += SizingLine("LMTD (counter-current)", lm, Quantity.TemperatureDifference)
        // Isothermal on one side (condensing steam): any arrangement is true counter-current.
        val isothermal = abs(hotIn - hotOut) < 0.01 || abs(coldIn - coldOut) < 0.01
        val shellsF = if (isothermal) 1 to 1.0 else shells(hotIn, hotOut, coldIn, coldOut)
        if (shellsF == null) {
            warnings += "No arrangement of up to 6 shells in series gets F to 0.75: a true counter-current exchanger is needed."
        } else {
            lines += SizingLine("F (1-2 shells in series)", shellsF.second, Quantity.Ratio)
            if (type == ObjectType.HeatExchanger) lines += SizingLine("Shells in series", shellsF.first.toDouble(), Quantity.Count)
        }
        if (u == null) {
            warnings += "Choose a service or give U."
            return EquipmentSizingResult(lines, notes, warnings)
        }
        lines += SizingLine("U" + if (input.u == null) " (${service?.label}, typical)" else "", u, Quantity.HeatTransferCoefficient)
        val f = shellsF?.second ?: 1.0
        val area = q / (u * f * lm)
        lines += SizingLine(if (type == ObjectType.AirCooler) "Bare-tube area" else "Heat-transfer area", area, null, text = "%.1f m²".format(area))
        if (input.u == null && service != null) {
            lines += SizingLine("Area over the service's U range", null, null,
                text = "%.1f – %.1f m²".format(q / (service.high * f * lm), q / (service.low * f * lm)))
        }
        if (type == ObjectType.HeatExchanger && shellsF != null && area / shellsF.first > 1000) {
            warnings += "Over 1000 m² a shell: split into parallel shells."
        }
        return EquipmentSizingResult(lines, notes, warnings)
    }
}
