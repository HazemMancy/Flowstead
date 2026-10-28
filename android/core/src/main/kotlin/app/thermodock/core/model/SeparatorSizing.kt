/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/** Mist eliminators, with the Souders–Brown K (m/s, vertical up-flow, near atmospheric) the data library gives each. */
@Serializable
enum class MistEliminator(val label: String, val k: Double, val padThickness: Double) {
    @SerialName("none") None("None (gravity only)", 0.06, 0.0),
    @SerialName("mesh") Mesh("Wire mesh pad", 0.107, 0.15),
    @SerialName("vane") Vane("Vane pack", 0.15, 0.30),
    @SerialName("cyclone") Cyclone("Demisting cyclones", 0.25, 0.60),
}

/** Inlet devices, with the largest inlet-nozzle momentum ρv² (Pa) the data library allows each. */
@Serializable
enum class InletDevice(val label: String, val maxMomentum: Double) {
    @SerialName("none") None("None", 1000.0),
    @SerialName("diverter") Diverter("Diverter plate", 1400.0),
    @SerialName("half_pipe") HalfPipe("Half-open pipe", 2100.0),
    @SerialName("vane") Vane("Vane inlet (Schoepentoeter)", 6000.0),
    @SerialName("cyclone") Cyclone("Inlet cyclones", 8000.0),
}

/**
 * The user's separator sizing basis (D61). Every number has a data-library default ([SeparatorSizing]) and can be
 * replaced by the company's own. Not part of the solved model. Times in s, lengths in m, K in m/s.
 */
@Serializable
data class SeparatorSizingInput(
    val horizontal: Boolean = false,
    val mistEliminator: MistEliminator = MistEliminator.Mesh,
    val inletDevice: InletDevice = InletDevice.HalfPipe,
    /** Null: the library K of the mist eliminator, corrected for pressure (and × 1.25 in horizontal flow). */
    val k: Double? = null,
    /** Normal to low liquid level. */
    val holdupTime: Double = 300.0,
    /** High to normal liquid level. */
    val surgeTime: Double = 180.0,
    /** Three-phase: oil and water retention (API 12J). */
    val oilRetention: Double = 300.0,
    val waterRetention: Double = 300.0,
    /** Three-phase: the water droplet to settle out of the oil, and the oil droplet out of the water (Arnold & Stewart). */
    val waterDroplet: Double = 500e-6,
    val oilDroplet: Double = 200e-6,
    /** Horizontal: null takes L/D from the operating pressure. */
    val lengthToDiameter: Double? = null,
)

/** One nozzle: the bore the limit asks for and the smallest standard pipe (Sch 40 / STD) at least that size. */
data class NozzleSize(val name: String, val requiredDiameter: Double, val nps: String?, val insideDiameter: Double?, val limit: String)

/** One liquid level or zone height, measured from the bottom (m). */
data class Level(val name: String, val height: Double)

data class SeparatorSizingResult(
    val horizontal: Boolean,
    val threePhase: Boolean,
    val diameter: Double,
    /** Tangent to tangent. */
    val length: Double,
    val k: Double,
    /** Souders–Brown allowable gas velocity. */
    val maxGasVelocity: Double,
    val gasVelocity: Double,
    val levels: List<Level>,
    val nozzles: List<NozzleSize>,
    /** Face area the mist eliminator needs at the allowable velocity; null without one. */
    val mistEliminatorArea: Double?,
    val liquidVolume: Double,
    val notes: List<String>,
    val warnings: List<String>,
) {
    val slenderness: Double get() = length / diameter
}

/** What sizing needs from the solve, all SI; computed from the separator's solved outlet streams. */
data class SeparatorLoads(
    val pressure: Double,
    val gasMassFlow: Double,
    val gasDensity: Double,
    /** The light (oil or the only) liquid. */
    val oilVolumeFlow: Double,
    val oilDensity: Double,
    val oilViscosity: Double?,
    /** The heavy (aqueous) liquid; zero when there is none. */
    val waterVolumeFlow: Double,
    val waterDensity: Double?,
    val waterViscosity: Double?,
    val feedMassFlow: Double,
    val feedDensity: Double,
) {
    val gasVolumeFlow: Double get() = gasMassFlow / gasDensity
    val liquidVolumeFlow: Double get() = oilVolumeFlow + waterVolumeFlow
    val liquidDensity: Double get() =
        if (liquidVolumeFlow <= 0) oilDensity else (oilVolumeFlow * oilDensity + waterVolumeFlow * (waterDensity ?: 0.0)) / liquidVolumeFlow
}

/**
 * Separator sizing (D61): GPSA Engineering Data Book section 7 and Svrcek & Monnery (Chem. Eng. Prog., Oct 1993) for
 * two-phase vessels, API 12J and Arnold & Stewart for three-phase ones. Gas capacity from the Souders–Brown velocity,
 * liquid volume from hold-up and surge (or retention) times, droplet settling by Stokes's law, nozzles from momentum
 * and velocity limits. Every load comes from the solved outlet streams; nothing is assumed about the fluids.
 */
object SeparatorSizing {
    private const val G = 9.80665
    private const val STEP = 0.1524 // vessels in 6 in steps of diameter
    private const val LOW_LEVEL_VERTICAL = 0.38
    private const val LOW_LEVEL_HORIZONTAL = 0.23
    const val GAS_OUTLET_MOMENTUM = 4500.0
    const val LIQUID_OUTLET_VELOCITY = 1.0

    /** GPSA's reduction of the mesh-pad K with pressure: 1.0 at 0 psig to 0.75 at 1500 psig, linear between its points. */
    fun pressureFactor(pressure: Double): Double {
        val psig = (pressure - 101325.0) / 6894.757
        val pts = listOf(0.0 to 1.0, 300.0 to 0.9, 600.0 to 0.85, 900.0 to 0.8, 1500.0 to 0.75)
        if (psig <= 0) return 1.0
        if (psig >= 1500) return 0.75
        val i = pts.indexOfLast { it.first <= psig }
        val (x0, y0) = pts[i]
        val (x1, y1) = pts[i + 1]
        return y0 + (y1 - y0) * (psig - x0) / (x1 - x0)
    }

    /** Svrcek & Monnery's L/D by operating pressure. */
    fun slenderness(pressure: Double): Double {
        val barg = (pressure - 101325.0) / 1e5
        return when {
            barg <= 17.2 -> 3.0
            barg <= 34.5 -> 4.0
            else -> 5.0
        }
    }

    fun k(input: SeparatorSizingInput, pressure: Double): Double =
        input.k ?: (input.mistEliminator.k * pressureFactor(pressure) * if (input.horizontal) 1.25 else 1.0)

    /** Stokes settling velocity of a droplet of [d] through a continuous phase of viscosity [mu] (Pa·s). */
    fun stokes(d: Double, deltaRho: Double, mu: Double): Double = G * d * d * deltaRho / (18 * mu)

    /** Area of a circular segment of height [h] in a circle of diameter [d]. */
    fun segment(h: Double, d: Double): Double {
        val r = d / 2
        val x = h.coerceIn(0.0, d)
        return r * r * acos((r - x) / r) - (r - x) * sqrt(max(0.0, 2 * r * x - x * x))
    }

    /** The height whose segment has area [a]. */
    fun height(a: Double, d: Double): Double {
        var lo = 0.0
        var hi = d
        repeat(60) {
            val m = (lo + hi) / 2
            if (segment(m, d) < a) lo = m else hi = m
        }
        return (lo + hi) / 2
    }

    /** Smallest NPS whose Sch 40 (or STD) bore is at least [d]. */
    fun nozzle(name: String, d: Double, limit: String): NozzleSize {
        val pick = PipeSizes.ALL.asSequence().mapNotNull { s ->
            val sch = s.schedules.firstOrNull { "40" in it.label.split(" / ") } ?: s.schedules.firstOrNull { "STD" in it.label.split(" / ") }
            sch?.let { s to s.insideDiameterM(it) }
        }.firstOrNull { it.second >= d }
        return NozzleSize(name, d, pick?.first?.nps, pick?.second, limit)
    }

    private fun roundUp(x: Double, step: Double) = ceil(x / step - 1e-9) * step

    /** The loads from the solved separator, or the reason they can't be had. */
    fun loads(fs: Flowsheet, results: ResultView, sep: SimObject): Pair<SeparatorLoads?, String?> {
        fun calc(id: String, key: String) = (results.value(id, key) as? DisplayValue.Calculated)?.si
        val ports = Ports.of(sep.type)
        val gas = fs.streamAt(sep.id, ports.first { it.side == PortSide.Outlet && it.index == 0 }) ?: return null to "Connect the vapour outlet"
        val liquids = ports.filter { it.side == PortSide.Outlet && it.index > 0 }.mapNotNull { fs.streamAt(sep.id, it) }
        val feeds = ports.filter { it.side == PortSide.Inlet && it.kind == PortKind.Material }.mapNotNull { fs.streamAt(sep.id, it) }
        if (liquids.isEmpty() || feeds.isEmpty()) return null to "Connect the feed and liquid outlet"
        val p = calc(gas.id, "pressure") ?: return null to "Solve the flowsheet first"
        val gasFlow = calc(gas.id, "massFlow") ?: return null to "Solve the flowsheet first"
        val gasRho = calc(gas.id, "density")
        // A separator without vapour (all liquid) still gets a size: no gas load.
        if (gasFlow > 0 && gasRho == null) return null to "No gas density in the solve"
        // The light liquid is the first liquid outlet; the heavy (three-phase) the second.
        val oil = liquids.first()
        val water = liquids.getOrNull(1)
        val oilMass = calc(oil.id, "massFlow") ?: return null to "Solve the flowsheet first"
        val oilRho = calc(oil.id, "density") ?: if (oilMass > 0) return null to "No liquid density in the solve" else 1000.0
        val waterMass = water?.let { calc(it.id, "massFlow") } ?: 0.0
        val waterRho = water?.let { calc(it.id, "density") }
        val feedMass = feeds.sumOf { calc(it.id, "massFlow") ?: 0.0 }
        val feedVolume = feeds.sumOf { calc(it.id, "volumeFlow") ?: 0.0 }
        if (feedVolume <= 0) return null to "No feed volume flow in the solve"
        return SeparatorLoads(
            pressure = p,
            gasMassFlow = gasFlow,
            gasDensity = gasRho ?: 1.0,
            oilVolumeFlow = oilMass / oilRho,
            oilDensity = oilRho,
            oilViscosity = calc(oil.id, "viscosity"),
            waterVolumeFlow = if (waterRho != null && waterRho > 0) waterMass / waterRho else 0.0,
            waterDensity = waterRho,
            waterViscosity = water?.let { calc(it.id, "viscosity") },
            feedMassFlow = feedMass,
            feedDensity = feedMass / feedVolume,
        ) to null
    }

    fun size(loads: SeparatorLoads, input: SeparatorSizingInput, threePhase: Boolean): SeparatorSizingResult {
        val warnings = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val k = k(input, loads.pressure)
        val rhoL = loads.liquidDensity
        val rhoG = loads.gasDensity
        val vMax = if (loads.gasMassFlow > 0 && rhoL > rhoG) k * sqrt((rhoL - rhoG) / rhoG) else Double.POSITIVE_INFINITY
        val qg = if (loads.gasMassFlow > 0) loads.gasVolumeFlow else 0.0
        val ql = loads.liquidVolumeFlow
        val water = threePhase && loads.waterVolumeFlow > 0 && loads.waterDensity != null
        // Settling velocities of water out of the oil and oil out of the water (three-phase only).
        val dRho = if (water) loads.waterDensity!! - loads.oilDensity else 0.0
        val vWater = if (water && loads.oilViscosity != null && dRho > 0) stokes(input.waterDroplet, dRho, loads.oilViscosity) else null
        val vOil = if (water && loads.waterViscosity != null && dRho > 0) stokes(input.oilDroplet, dRho, loads.waterViscosity) else null
        if (water && (vWater == null || vOil == null)) warnings += "Liquid viscosities or a density difference are missing: droplet settling is not checked."
        if (threePhase && !water) notes += "No aqueous phase in the solve: sized as two-phase."
        val liquidVolume = if (water) loads.oilVolumeFlow * input.oilRetention + loads.waterVolumeFlow * input.waterRetention + ql * input.surgeTime
        else ql * (input.holdupTime + input.surgeTime)

        // Nozzles first: the vertical vessel's heights depend on the inlet bore.
        val vIn = sqrt(input.inletDevice.maxMomentum / loads.feedDensity)
        val inlet = nozzle("Inlet", sqrt(4 * loads.feedMassFlow / loads.feedDensity / (PI * vIn)), "ρv² ≤ ${input.inletDevice.maxMomentum.toInt()} Pa (${input.inletDevice.label})")
        val nozzles = mutableListOf(inlet)
        if (qg > 0) nozzles += nozzle("Gas outlet", sqrt(4 * qg / (PI * sqrt(GAS_OUTLET_MOMENTUM / rhoG))), "ρv² ≤ ${GAS_OUTLET_MOMENTUM.toInt()} Pa")
        if (water) {
            nozzles += nozzle("Oil outlet", sqrt(4 * loads.oilVolumeFlow / (PI * LIQUID_OUTLET_VELOCITY)), "v ≤ $LIQUID_OUTLET_VELOCITY m/s")
            nozzles += nozzle("Water outlet", sqrt(4 * loads.waterVolumeFlow / (PI * LIQUID_OUTLET_VELOCITY)), "v ≤ $LIQUID_OUTLET_VELOCITY m/s")
        } else if (ql > 0) nozzles += nozzle("Liquid outlet", sqrt(4 * ql / (PI * LIQUID_OUTLET_VELOCITY)), "v ≤ $LIQUID_OUTLET_VELOCITY m/s")
        val dIn = inlet.insideDiameter ?: inlet.requiredDiameter
        val meArea = if (input.mistEliminator != MistEliminator.None && qg > 0) qg / vMax else null

        return if (!input.horizontal) {
            var dReq = if (qg > 0) sqrt(4 * qg / (PI * vMax)) else 0.3
            if (vWater != null) dReq = max(dReq, sqrt(4 * loads.oilVolumeFlow / (PI * vWater)))
            if (vOil != null) dReq = max(dReq, sqrt(4 * loads.waterVolumeFlow / (PI * vOil)))
            val d = max(roundUp(dReq, STEP), 2 * STEP)
            val a = PI * d * d / 4
            val levels = mutableListOf<Level>()
            var h = LOW_LEVEL_VERTICAL
            levels += Level("Low liquid level (LLL)", h)
            if (water) {
                h += loads.waterVolumeFlow * input.waterRetention / a
                levels += Level("Oil–water oilWater", h)
                h += loads.oilVolumeFlow * input.oilRetention / a
                levels += Level("Normal liquid level (NLL)", h)
            } else {
                h += ql * input.holdupTime / a
                levels += Level("Normal liquid level (NLL)", h)
            }
            h += ql * input.surgeTime / a
            levels += Level("High liquid level (HLL)", h)
            h += 0.3 + dIn / 2
            levels += Level("Inlet nozzle centre", h)
            h += if (input.mistEliminator == MistEliminator.None) max(d, 1.2192 + dIn / 2) else max(0.5 * d, 0.9144 + dIn / 2)
            if (input.mistEliminator != MistEliminator.None) {
                levels += Level("Mist eliminator (bottom)", h)
                h += input.mistEliminator.padThickness + 0.4
            }
            var length = roundUp(h, 0.05)
            if (length / d < 1.5) {
                length = roundUp(1.5 * d, 0.05)
                notes += "Height raised to 1.5 × D, the least for a vertical vessel."
            }
            if (length / d > 6) warnings += "H/D ${"%.1f".format(length / d)} is above 6: a horizontal vessel will be shorter."
            SeparatorSizingResult(
                false, water, d, length, k, vMax, if (qg > 0) qg / a else 0.0, levels, nozzles, meArea, liquidVolume, notes, warnings,
            )
        } else horizontal(loads, input, k, vMax, qg, ql, water, vWater, vOil, liquidVolume, nozzles, meArea, notes, warnings)
    }

    private fun horizontal(
        loads: SeparatorLoads, input: SeparatorSizingInput, k: Double, vMax: Double, qg: Double, ql: Double, water: Boolean,
        vWater: Double?, vOil: Double?, liquidVolume: Double, nozzles: List<NozzleSize>, meArea: Double?,
        notes: MutableList<String>, warnings: MutableList<String>,
    ): SeparatorSizingResult {
        val ld = input.lengthToDiameter ?: slenderness(loads.pressure)
        var d = 2 * STEP
        while (d <= 6.0 + 1e-9) {
            val l = ld * d
            val a = PI * d * d / 4
            val hv = max(0.2 * d, 0.3)
            val av = segment(hv, d)
            val aLow = segment(LOW_LEVEL_HORIZONTAL, d)
            val uv = if (qg > 0) qg / av else 0.0
            val gasOk = uv <= vMax && (qg <= 0 || l >= uv * hv / vMax)
            val liquidOk = (a - av - aLow) * l >= liquidVolume
            var layersOk = true
            var oilWater: Double? = null
            if (water && liquidOk) {
                val hw = height(aLow + loads.waterVolumeFlow * input.waterRetention / l, d)
                val hOilTop = height(segment(hw, d) + loads.oilVolumeFlow * input.oilRetention / l, d)
                oilWater = hw
                if (vWater != null && hOilTop - hw > vWater * input.oilRetention) layersOk = false
                if (vOil != null && hw > vOil * input.waterRetention) layersOk = false
            }
            if (gasOk && liquidOk && layersOk) {
                val length = roundUp(l, 0.1)
                val levels = mutableListOf(Level("Low liquid level (LLL)", LOW_LEVEL_HORIZONTAL))
                oilWater?.let { levels += Level("Oil–water oilWater", it) }
                val nll = if (water) height(segment(oilWater!!, d) + loads.oilVolumeFlow * input.oilRetention / length, d)
                else height(aLow + ql * input.holdupTime / length, d)
                levels += Level("Normal liquid level (NLL)", nll)
                levels += Level("High liquid level (HLL)", height(segment(nll, d) + ql * input.surgeTime / length, d))
                levels += Level("Vapour space (top)", d - hv)
                return SeparatorSizingResult(true, water, d, length, k, vMax, uv, levels, nozzles, meArea, liquidVolume, notes, warnings)
            }
            d += STEP
        }
        warnings += "No diameter up to 6 m meets every limit at L/D ${"%.1f".format(ld)}."
        val d6 = 6.0
        return SeparatorSizingResult(true, water, d6, ld * d6, k, vMax, if (qg > 0) qg / segment(max(0.2 * d6, 0.3), d6) else 0.0,
            emptyList(), nozzles, meArea, liquidVolume, notes, warnings)
    }
}
