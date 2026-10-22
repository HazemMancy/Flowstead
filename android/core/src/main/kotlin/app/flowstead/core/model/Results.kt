/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.api.ObjectStatus
import app.flowstead.core.api.Phases
import app.flowstead.core.api.SolveResult
import app.flowstead.core.api.SolveStatus
import app.flowstead.core.units.Quantity
import kotlinx.serialization.Serializable

/** A solve result together with the exact flowsheet it belongs to. */
@Serializable
data class StoredSolve(
    val result: SolveResult,
    /** SHA-256 of the flowsheet JSON that was solved. */
    val modelHash: String,
    val solvedAtMillis: Long,
)

/** Result property ids → display quantity and label. Keys match the engine's ObjectResult.properties. */
object ResultKeys {
    /** Pipe line-sizing results, from the engine's increment-by-increment profile. */
    val PIPE_HYDRAULICS = listOf(
        "pressureGradient", "frictionPressureGradient", "inletVelocity", "outletVelocity", "maxVelocity",
        "gasVelocity", "liquidVelocity", "maxMomentumFlux", "mixtureDensity", "maxMachNumber", "liquidHoldup",
    )
    val STREAM = listOf(
        "temperature", "pressure", "vapourFraction", "molarFlow", "massFlow", "volumeFlow",
        "massEnthalpy", "massEntropy", "massHeatCapacity", "density", "molecularWeight", "viscosity", "compressibilityFactor",
    )
    val UNIT_OP = listOf(
        "duty", "power", "pressureDrop", "pressureRise", "pressureRatio", "outletPressure", "outletTemperature",
        "temperatureChange", "efficiency", "adiabaticHead", "lmtd", "hotOutletTemperature", "coldOutletTemperature",
        "hotPressureDrop", "coldPressureDrop", "iterations",
        "heatTransferred", "frictionPressureDrop", "staticPressureDrop", "orificePressureDrop", "beta",
        "liquidResidenceTime", "liquidVolumeFlow", "targetValue",
        "residenceTime",
        "condenserDuty", "reboilerDuty", "refluxRatio", "condenserTemperature", "reboilerTemperature", "topTemperature",
        "bottomTemperature", "minimumRefluxRatio", "minimumStages", "actualStages", "optimalFeedStage",
    ) + PIPE_HYDRAULICS


    /** Unit-level results shown for each type, in display order. Streams use [STREAM]. */
    fun forType(type: ObjectType): List<String> = when (type) {
        ObjectType.EnergyStream -> listOf("energyFlow")
        ObjectType.Valve -> listOf("pressureDrop", "outletPressure", "temperatureChange", "requiredCv", "requiredKv", "valveOpening", "chokedPressureDrop", "inletBubblePressure")
        ObjectType.Heater, ObjectType.Cooler, ObjectType.AirCooler -> listOf("duty", "outletTemperature", "pressureDrop")
        ObjectType.ComponentSplitter -> listOf("duty")
        ObjectType.Tank -> listOf("liquidResidenceTime", "liquidVolumeFlow")
        ObjectType.Set -> listOf("targetValue")
        ObjectType.DistillationColumn -> listOf("condenserDuty", "reboilerDuty", "refluxRatio", "condenserTemperature", "reboilerTemperature")
        ObjectType.RefluxedAbsorber -> listOf("condenserDuty", "refluxRatio", "condenserTemperature", "bottomTemperature")
        ObjectType.ReboiledAbsorber -> listOf("reboilerDuty", "topTemperature", "reboilerTemperature")
        ObjectType.Absorber -> listOf("topTemperature", "bottomTemperature")
        ObjectType.ConversionReactor, ObjectType.EquilibriumReactor, ObjectType.GibbsReactor -> listOf("duty", "temperatureChange", "pressureDrop")
        ObjectType.Cstr, ObjectType.Pfr -> listOf("duty", "temperatureChange", "residenceTime", "pressureDrop")
        ObjectType.ShortcutColumn -> listOf(
            "minimumRefluxRatio", "minimumStages", "actualStages", "optimalFeedStage",
            "condenserDuty", "reboilerDuty",
        )
        ObjectType.Pump -> listOf("power", "pressureRise", "head", "temperatureChange", "efficiency", "npshAvailable", "npshRequired", "feedBubblePressure", "inletVolumeFlow")
        ObjectType.Compressor -> listOf("power", "outletTemperature", "pressureRatio", "adiabaticHead", "efficiency", "inletVolumeFlow", "surgeMargin")
        ObjectType.Expander -> listOf("power", "outletTemperature", "pressureDrop", "efficiency")
        ObjectType.HeatExchanger -> listOf("duty", "lmtd", "minimumApproach", "pinchLocation", "pinchHotTemperature", "pinchColdTemperature", "hotOutletTemperature", "coldOutletTemperature", "hotPressureDrop", "coldPressureDrop")
        ObjectType.PipeSegment -> listOf("pressureDrop", "frictionPressureDrop", "staticPressureDrop") + PIPE_HYDRAULICS +
            listOf("temperatureChange", "heatTransferred")
        ObjectType.OrificePlate -> listOf("pressureDrop", "orificePressureDrop", "beta", "temperatureChange")
        ObjectType.Recycle -> listOf("iterations")
        ObjectType.Adjust -> listOf("manipulatedValue", "controlledValue", "iterations")
        else -> emptyList()
    }

    /** Like [quantity], but an Adjust's values take the quantity of what they reference. */
    fun quantityFor(o: SimObject, key: String): Quantity = when {
        o.type == ObjectType.Adjust && key == "manipulatedValue" ->
            o.refs[AdjustKeys.MANIPULATED_SPEC]?.let { runCatching { SpecKey.quantity(it) }.getOrNull() } ?: Quantity.Ratio
        o.type == ObjectType.Adjust && key == "controlledValue" ->
            o.refs[AdjustKeys.CONTROLLED_PROPERTY]?.let { quantity(it) } ?: Quantity.Ratio
        o.type == ObjectType.Set && key == "targetValue" ->
            o.refs[SetKeys.VARIABLE]?.let { runCatching { SpecKey.quantity(it) }.getOrNull() } ?: Quantity.Ratio
        else -> quantity(key)
    }

    fun quantity(key: String): Quantity = when (key) {
        "temperature", "outletTemperature", "hotOutletTemperature", "coldOutletTemperature",
        "condenserTemperature", "reboilerTemperature", "topTemperature", "bottomTemperature" -> Quantity.Temperature
        "temperatureChange", "lmtd", "minimumApproach" -> Quantity.TemperatureDifference
        "npshAvailable" -> Quantity.Length
        "pinchLocation" -> Quantity.Fraction
        "pinchHotTemperature", "pinchColdTemperature" -> Quantity.Temperature
        "requiredCv", "requiredKv" -> Quantity.Ratio
        "valveOpening" -> Quantity.Fraction
        "chokedPressureDrop" -> Quantity.PressureDifference
        "inletBubblePressure" -> Quantity.Pressure
        "feedBubblePressure" -> Quantity.Pressure
        "pressure", "outletPressure" -> Quantity.Pressure
        "pressureDrop", "pressureRise", "hotPressureDrop", "coldPressureDrop",
        "frictionPressureDrop", "staticPressureDrop", "orificePressureDrop" -> Quantity.PressureDifference
        "vapourFraction", "phaseMoleFraction", "efficiency" -> Quantity.Fraction
        "compressibilityFactor", "pressureRatio", "beta", "refluxRatio", "minimumRefluxRatio",
        "minimumStages", "actualStages", "optimalFeedStage" -> Quantity.Ratio
        "iterations" -> Quantity.Count
        "adiabaticHead", "polytropicHead", "head", "curveHead", "npshRequired" -> Quantity.Length
        "inletVolumeFlow" -> Quantity.VolumeFlow
        "surgeMargin" -> Quantity.Fraction
        "molarFlow" -> Quantity.MolarFlow
        "massFlow" -> Quantity.MassFlow
        "volumeFlow", "liquidVolumeFlow" -> Quantity.VolumeFlow
        "liquidResidenceTime", "residenceTime" -> Quantity.Time
        "massEnthalpy" -> Quantity.MassEnthalpy
        "molarEnthalpy" -> Quantity.MolarEnthalpy
        "stdLiquidVolumeFlow" -> Quantity.VolumeFlow
        "massEntropy" -> Quantity.MassEntropy
        "massHeatCapacity", "massHeatCapacityCv" -> Quantity.MassHeatCapacity
        "heatCapacityRatio" -> Quantity.Ratio
        "thermalConductivity" -> Quantity.ThermalConductivity
        "kinematicViscosity" -> Quantity.KinematicViscosity
        "surfaceTension" -> Quantity.SurfaceTension
        "density" -> Quantity.Density
        "molecularWeight" -> Quantity.MolecularWeight
        "viscosity" -> Quantity.Viscosity
        "duty", "power", "energyFlow", "heatTransferred", "condenserDuty", "reboilerDuty" -> Quantity.Power
        "pressureGradient", "frictionPressureGradient" -> Quantity.PressureGradient
        "inletVelocity", "outletVelocity", "maxVelocity", "gasVelocity", "liquidVelocity" -> Quantity.Velocity
        "maxMomentumFlux" -> Quantity.MomentumFlux
        "mixtureDensity" -> Quantity.Density
        "maxMachNumber" -> Quantity.Ratio
        "liquidHoldup" -> Quantity.Fraction
        else -> Quantity.Fraction
    }

    fun label(key: String): String = when (key) {
        "temperature" -> "Temperature"
        "pressure" -> "Pressure"
        "vapourFraction" -> "Vapour fraction"
        "phaseMoleFraction" -> "Phase fraction (molar)"
        "molarFlow" -> "Molar flow"
        "massFlow" -> "Mass flow"
        "volumeFlow" -> "Actual volume flow"
        "massEnthalpy" -> "Mass enthalpy"
        "molarEnthalpy" -> "Molar enthalpy"
        "stdLiquidVolumeFlow" -> "Std ideal liquid volume flow"
        "massEntropy" -> "Mass entropy"
        "massHeatCapacity" -> "Cp (mass)"
        "massHeatCapacityCv" -> "Cv (mass)"
        "heatCapacityRatio" -> "Cp/Cv"
        "thermalConductivity" -> "Thermal conductivity"
        "kinematicViscosity" -> "Kinematic viscosity"
        "surfaceTension" -> "Surface tension"
        "density" -> "Density"
        "molecularWeight" -> "Molecular weight"
        "viscosity" -> "Viscosity"
        "compressibilityFactor" -> "Z factor"
        "duty" -> "Duty"
        "power" -> "Power"
        "pressureDrop" -> "Pressure drop"
        "outletPressure" -> "Outlet pressure"
        "outletTemperature" -> "Outlet temperature"
        "temperatureChange" -> "Temperature change"
        "energyFlow" -> "Energy flow"
        "pressureRise" -> "Pressure rise"
        "pressureRatio" -> "Pressure ratio"
        "efficiency" -> "Adiabatic efficiency"
        "adiabaticHead" -> "Adiabatic head"
        "lmtd" -> "LMTD"
        "minimumApproach" -> "Minimum approach (zone analysis)"
        "pinchLocation" -> "Pinch at (fraction of the duty from the hot inlet)"
        "pinchHotTemperature" -> "Hot side temperature at the pinch"
        "pinchColdTemperature" -> "Cold side temperature at the pinch"
        "npshAvailable" -> "NPSH available (at the pump inlet)"
        "npshRequired" -> "NPSH required (from the curve)"
        "head" -> "Head"
        "curveHead" -> "Head on the curve at this flow"
        "inletVolumeFlow" -> "Actual inlet flow"
        "surgeMargin" -> "Surge margin (flow above the surge limit)"
        "requiredCv" -> "Required Cv (US, IEC 60534)"
        "requiredKv" -> "Required Kv (metric)"
        "valveOpening" -> "Opening (linear trim)"
        "chokedPressureDrop" -> "Choked (maximum useful) pressure drop"
        "inletBubblePressure" -> "Inlet bubble-point pressure"
        "feedBubblePressure" -> "Feed bubble-point pressure"
        "hotOutletTemperature" -> "Hot outlet temperature"
        "coldOutletTemperature" -> "Cold outlet temperature"
        "hotPressureDrop" -> "Hot side pressure drop"
        "coldPressureDrop" -> "Cold side pressure drop"
        "iterations" -> "Iterations"
        "heatTransferred" -> "Heat gained from surroundings"
        "frictionPressureDrop" -> "Friction pressure drop"
        "staticPressureDrop" -> "Static (elevation) pressure drop"
        "orificePressureDrop" -> "Differential across the plate"
        "beta" -> "Beta (d/D)"
        "liquidResidenceTime" -> "Liquid residence time"
        "residenceTime" -> "Residence time"
        "liquidVolumeFlow" -> "Liquid outflow (actual)"
        "targetValue" -> "Value given to the target"
        "condenserDuty" -> "Condenser duty"
        "reboilerDuty" -> "Reboiler duty"
        "refluxRatio" -> "Reflux ratio (L / total overhead)"
        "condenserTemperature" -> "Condenser temperature"
        "reboilerTemperature" -> "Reboiler temperature"
        "topTemperature" -> "Top stage temperature"
        "bottomTemperature" -> "Bottom stage temperature"
        "minimumRefluxRatio" -> "Minimum reflux ratio"
        "minimumStages" -> "Minimum stages"
        "actualStages" -> "Theoretical stages"
        "optimalFeedStage" -> "Optimal feed stage"
        "pressureGradient" -> "ΔP per length (total)"
        "frictionPressureGradient" -> "ΔP per length (friction)"
        "inletVelocity" -> "Inlet velocity"
        "outletVelocity" -> "Outlet velocity"
        "maxVelocity" -> "Maximum velocity"
        "gasVelocity" -> "Gas superficial velocity (at max.)"
        "liquidVelocity" -> "Liquid superficial velocity (at max.)"
        "maxMomentumFlux" -> "Maximum ρv²"
        "mixtureDensity" -> "Mixture density (at max. ρv², no-slip)"
        "maxMachNumber" -> "Maximum Mach number"
        "liquidHoldup" -> "Liquid holdup (mean)"
        "manipulatedValue" -> "Varied value"
        "controlledValue" -> "Controlled value"
        else -> key.removePrefix(CONVERSION_PREFIX).takeIf { key.startsWith(CONVERSION_PREFIX) }?.let { "$it conversion" } ?: key
    }

    /** Reactors report "conversion:<compound>" for each reactant. */
    const val CONVERSION_PREFIX = "conversion:"

    /** Keys shown for [o]: [forType] plus, for reactors, the conversion of each reactant. */
    fun forObject(fs: Flowsheet, o: SimObject): List<String> =
        forType(o.type) + if (Reactors.isReactor(o.type)) Reactors.reactants(fs, o).map { CONVERSION_PREFIX + it } else emptyList()

    /** [forObject], then any other unit-level value the engine returned for it that has a label, so nothing it calculated is hidden. */
    fun allForObject(fs: Flowsheet, o: SimObject, view: ResultView): List<String> {
        val listed = forObject(fs, o)
        return listed + view.propertyKeys(o.id).filter { it !in listed && label(it) != it }.sorted()
    }
}

/**
 * Turns a stored solve into on-screen values. A number only ever comes from the engine's result;
 * anything missing is [DisplayValue.Missing] or [DisplayValue.Error] with the reason (brief §4).
 */
class ResultView(private val solve: StoredSolve?, currentHash: String) {
    val stale: Boolean = solve != null && solve.modelHash != currentHash
    val status: SolveStatus? = solve?.result?.status

    fun objectStatus(id: String): ObjectStatus? = solve?.result?.objects?.get(id)?.status

    /**
     * Value of an object-level property, e.g. ("s1", "temperature"). For a stream, properties the engine
     * reports per phase (density, volume flow, molecular weight, entropy, Cp…) come from the overall phase,
     * and viscosity and Z, which exist only per phase, from the stream's single phase when it has one.
     */
    fun value(objectId: String, key: String): DisplayValue = lookup(objectId) { r ->
        r.properties[key] ?: r.phases?.let { phases ->
            phases[Phases.OVERALL]?.properties?.get(key)
                ?: phases.filterKeys { it != Phases.OVERALL }.values.singleOrNull()?.properties?.get(key)
        }
    }

    /** Value of a phase property, e.g. ("s1", "vapour", "density"). */
    fun phaseValue(objectId: String, phase: String, key: String): DisplayValue =
        lookup(objectId, absentReason = "${Phases.label(phase)} phase not present") { it.phases?.get(phase)?.properties?.get(key) }

    fun composition(objectId: String, phase: String = Phases.OVERALL, compound: String): DisplayValue =
        lookup(objectId, absentReason = "${Phases.label(phase)} phase not present") { it.phases?.get(phase)?.composition?.get(compound) }

    /** The solver's messages about this object (the engine's unit checks, D46), errors first. */
    fun messages(objectId: String): List<app.flowstead.core.api.SolverMessage> =
        solve?.result?.messages.orEmpty().filter { it.objectId == objectId }.sortedBy { -it.level.ordinal }

    /** Warnings about a solved object: its numbers stand, but something needs the engineer's attention. */
    fun warnings(objectId: String): List<app.flowstead.core.api.SolverMessage> =
        messages(objectId).filter { it.level == app.flowstead.core.api.MessageLevel.Warning }

    /** Keys of the object-level values the engine returned for a solved object. */
    fun propertyKeys(objectId: String): List<String> =
        solve?.result?.objects?.get(objectId)?.takeIf { it.status == ObjectStatus.Solved }?.properties?.keys?.toList().orEmpty()

    /** A text result (e.g. a pipe's "flowRegime"), only from a solved object. */
    fun text(objectId: String, key: String): String? =
        solve?.result?.objects?.get(objectId)?.takeIf { it.status == ObjectStatus.Solved }?.texts?.get(key)

    fun presentPhases(objectId: String): List<String> {
        val phases = solve?.result?.objects?.get(objectId)?.phases ?: return emptyList()
        return Phases.ORDER.filter { it in phases }
    }

    private fun lookup(
        objectId: String,
        absentReason: String = "Not returned by the engine",
        pick: (app.flowstead.core.api.ObjectResult) -> Double?,
    ): DisplayValue {
        val r = solve?.result ?: return DisplayValue.Missing("Not solved yet")
        val obj = r.objects[objectId] ?: return when (r.status) {
            SolveStatus.Invalid -> DisplayValue.Missing("Solve refused: fix the flowsheet issues")
            SolveStatus.Timeout -> DisplayValue.Error("Solve timed out")
            else -> DisplayValue.Missing("Added after the last solve")
        }
        return when (obj.status) {
            ObjectStatus.Error -> DisplayValue.Error(obj.error ?: "Calculation failed")
            ObjectStatus.NotSolved -> DisplayValue.Missing(obj.error ?: "Not calculated")
            ObjectStatus.Solved -> pick(obj)?.takeIf { it.isFinite() }
                ?.let { DisplayValue.Calculated(it, stale) }
                ?: DisplayValue.Missing(absentReason)
        }
    }

    companion object {
        val NONE = ResultView(null, "")
    }
}

/** SHA-256 of the canonical flowsheet JSON: identifies exactly which flowsheet a result belongs to. */
fun Flowsheet.modelHash(): String {
    // Only what the engine solves counts: sizing criteria, data-sheet text, flowsheet tables and the solver mode change no result.
    val solved = copy(sizingCriteria = null, documentInfo = null, tables = emptyList(), solverActive = false, objects = objects.map { if (it.sheet.isEmpty()) it else it.copy(sheet = emptyMap()) })
    val json = FlowsheetJson.encodeToString(Flowsheet.serializer(), solved)
    return java.security.MessageDigest.getInstance("SHA-256").digest(json.toByteArray()).joinToString("") { "%02x".format(it) }
}
