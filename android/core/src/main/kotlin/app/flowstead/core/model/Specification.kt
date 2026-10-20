/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.units.Quantity

/** Method choices stored in SimObject.refs. Same strings as the engine's ChoiceKeys. */
object ChoiceKey {
    /** Pipe flow correlation; Beggs & Brill when absent. */
    const val PIPE_CORRELATION = "correlation"
    /** Orifice tap arrangement; flange taps when absent. */
    const val ORIFICE_TAPS = "taps"

    val PIPE_CORRELATIONS = listOf(
        "beggs_brill" to "Beggs & Brill",
        "lockhart_martinelli" to "Lockhart & Martinelli",
        "petalas_aziz" to "Petalas & Aziz",
    )
    val ORIFICE_TAP_OPTIONS = listOf("flange" to "Flange taps", "corner" to "Corner taps", "radius" to "Radius (D–D/2) taps")
}

/** Spec keys. Same strings as the engine's SpecKeys and shared/schema. */
object SpecKey {
    const val TEMPERATURE = "temperature"
    const val PRESSURE = "pressure"
    const val VAPOUR_FRACTION = "vapourFraction"
    const val MASS_ENTHALPY = "massEnthalpy"
    const val MOLAR_FLOW = "molarFlow"
    const val MASS_FLOW = "massFlow"
    const val OUTLET_PRESSURE = "outletPressure"
    const val PRESSURE_DROP = "pressureDrop"
    const val OUTLET_TEMPERATURE = "outletTemperature"
    const val DUTY = "duty"
    const val PRESSURE_RISE = "pressureRise"
    const val PRESSURE_RATIO = "pressureRatio"
    /** Adiabatic efficiency, stored as a fraction (0–1). */
    const val EFFICIENCY = "efficiency"
    /** Splitter: fraction of the feed to outlet 1 / outlet 2; outlet 3 takes the rest. */
    const val SPLIT_1 = "split1"
    const val SPLIT_2 = "split2"
    const val HOT_OUTLET_TEMPERATURE = "hotOutletTemperature"
    const val COLD_OUTLET_TEMPERATURE = "coldOutletTemperature"
    const val HOT_PRESSURE_DROP = "hotPressureDrop"
    const val COLD_PRESSURE_DROP = "coldPressureDrop"
    // Pipe segment and orifice plate
    const val LENGTH = "length"
    const val INNER_DIAMETER = "innerDiameter"
    const val ELEVATION_CHANGE = "elevationChange"
    const val ROUGHNESS = "roughness"
    /** Calculation increments along a pipe (whole number). */
    const val INCREMENTS = "increments"
    const val HEAT_TRANSFER_COEFFICIENT = "heatTransferCoefficient"
    const val AMBIENT_TEMPERATURE = "ambientTemperature"
    const val ORIFICE_DIAMETER = "orificeDiameter"
    const val PIPE_DIAMETER = "pipeDiameter"

    /** Small lengths that read better in mm or inches than in m or ft. */
    val SMALL_LENGTHS = setOf(INNER_DIAMETER, ROUGHNESS, ORIFICE_DIAMETER, PIPE_DIAMETER)

    val STREAM_STATE = listOf(TEMPERATURE, PRESSURE, VAPOUR_FRACTION, MASS_ENTHALPY)
    val STREAM_FLOW = listOf(MOLAR_FLOW, MASS_FLOW)

    fun quantity(key: String): Quantity = when (key) {
        TEMPERATURE, OUTLET_TEMPERATURE, HOT_OUTLET_TEMPERATURE, COLD_OUTLET_TEMPERATURE, AMBIENT_TEMPERATURE -> Quantity.Temperature
        PRESSURE, OUTLET_PRESSURE -> Quantity.Pressure
        PRESSURE_DROP, PRESSURE_RISE, HOT_PRESSURE_DROP, COLD_PRESSURE_DROP -> Quantity.PressureDifference
        VAPOUR_FRACTION, EFFICIENCY, SPLIT_1, SPLIT_2 -> Quantity.Fraction
        PRESSURE_RATIO -> Quantity.Ratio
        MASS_ENTHALPY -> Quantity.MassEnthalpy
        MOLAR_FLOW -> Quantity.MolarFlow
        MASS_FLOW -> Quantity.MassFlow
        DUTY -> Quantity.Power
        LENGTH, INNER_DIAMETER, ELEVATION_CHANGE, ROUGHNESS, ORIFICE_DIAMETER, PIPE_DIAMETER -> Quantity.Length
        INCREMENTS -> Quantity.Count
        HEAT_TRANSFER_COEFFICIENT -> Quantity.HeatTransferCoefficient
        else -> throw IllegalArgumentException("Unknown spec '$key'")
    }

    fun label(key: String): String = when (key) {
        TEMPERATURE -> "Temperature"
        PRESSURE -> "Pressure"
        VAPOUR_FRACTION -> "Vapour fraction"
        MASS_ENTHALPY -> "Mass enthalpy"
        MOLAR_FLOW -> "Molar flow"
        MASS_FLOW -> "Mass flow"
        OUTLET_PRESSURE -> "Outlet pressure"
        PRESSURE_DROP -> "Pressure drop"
        OUTLET_TEMPERATURE -> "Outlet temperature"
        DUTY -> "Duty"
        PRESSURE_RISE -> "Pressure rise"
        PRESSURE_RATIO -> "Pressure ratio"
        EFFICIENCY -> "Adiabatic efficiency"
        SPLIT_1 -> "Fraction to outlet 1"
        SPLIT_2 -> "Fraction to outlet 2"
        HOT_OUTLET_TEMPERATURE -> "Hot outlet temperature"
        COLD_OUTLET_TEMPERATURE -> "Cold outlet temperature"
        HOT_PRESSURE_DROP -> "Hot side pressure drop"
        COLD_PRESSURE_DROP -> "Cold side pressure drop"
        LENGTH -> "Length"
        INNER_DIAMETER -> "Inside diameter"
        ELEVATION_CHANGE -> "Elevation change"
        ROUGHNESS -> "Wall roughness"
        INCREMENTS -> "Calculation increments"
        HEAT_TRANSFER_COEFFICIENT -> "Overall U (to ambient)"
        AMBIENT_TEMPERATURE -> "Ambient temperature"
        ORIFICE_DIAMETER -> "Orifice diameter"
        PIPE_DIAMETER -> "Pipe inside diameter"
        else -> key
    }
}

/** Degrees-of-freedom verdict for one object, shown as the indicator in its editor. */
sealed interface SpecStatus {
    data object Complete : SpecStatus
    data class UnderSpecified(val needs: List<String>) : SpecStatus
    data class OverSpecified(val problem: String) : SpecStatus
    data class Invalid(val problem: String) : SpecStatus

    val ok: Boolean get() = this == Complete
}

/**
 * Checks what the user has to give before a solve, mirroring the engine's validator
 * (engine/src/Flowstead.Engine.Worker/Validator.cs) so problems show up while editing, offline.
 */
object Specification {
    const val COMPOSITION_TOLERANCE = 1e-6

    fun status(fs: Flowsheet, o: SimObject): SpecStatus = when (o.type) {
        ObjectType.MaterialStream -> if (fs.isFeed(o)) feedStream(fs, o) else SpecStatus.Complete
        ObjectType.EnergyStream -> SpecStatus.Complete
        ObjectType.Adjust -> adjust(fs, o)
        else -> unitOp(fs, o)
    }

    /** Mirrors the engine's ValidateAdjust. */
    fun adjust(fs: Flowsheet, a: SimObject): SpecStatus {
        val needs = mutableListOf<String>()
        val mId = a.refs[AdjustKeys.MANIPULATED_OBJECT]
        val mKey = a.refs[AdjustKeys.MANIPULATED_SPEC]
        val cId = a.refs[AdjustKeys.CONTROLLED_OBJECT]
        val cKey = a.refs[AdjustKeys.CONTROLLED_PROPERTY]
        if (mId == null || mKey == null) needs += "variable to vary"
        if (cId == null || cKey == null) needs += "result to control"
        if (AdjustKeys.TARGET !in a.specs) needs += "target value"
        if (mId != null) {
            val m = fs.obj(mId) ?: return SpecStatus.Invalid("The varied object no longer exists")
            if (mKey != null && mKey !in m.specs) return SpecStatus.Invalid("${m.tag} has no ${SpecKey.label(mKey).lowercase()} specified to start from")
        }
        if (cId != null && fs.obj(cId) == null) return SpecStatus.Invalid("The controlled object no longer exists")
        val min = a.specs[AdjustKeys.MINIMUM]?.si
        val max = a.specs[AdjustKeys.MAXIMUM]?.si
        if (min != null && max != null && min >= max) return SpecStatus.Invalid("Minimum must be below maximum")
        a.specs[AdjustKeys.TOLERANCE]?.let { if (it.si <= 0) return SpecStatus.Invalid("Tolerance must be positive") }
        return if (needs.isEmpty()) SpecStatus.Complete else SpecStatus.UnderSpecified(needs)
    }

    fun feedStream(fs: Flowsheet, o: SimObject): SpecStatus {
        val s = o.specs
        s[SpecKey.TEMPERATURE]?.let { if (it.si <= 0) return SpecStatus.Invalid("Temperature must be above absolute zero") }
        s[SpecKey.PRESSURE]?.let { if (it.si <= 0) return SpecStatus.Invalid("Pressure must be above zero absolute") }
        s[SpecKey.VAPOUR_FRACTION]?.let { if (it.si !in 0.0..1.0) return SpecStatus.Invalid("Vapour fraction must be between 0 and 1") }

        val state = SpecKey.STREAM_STATE.filter { it in s }
        val flows = SpecKey.STREAM_FLOW.filter { it in s }
        if (state.size > 2) return SpecStatus.OverSpecified("Give exactly two of temperature, pressure, vapour fraction, enthalpy")
        if (flows.size > 1) return SpecStatus.OverSpecified("Give a molar or a mass flow, not both")
        if (state.size == 2 && !validPair(state.toSet())) {
            return SpecStatus.Invalid("That pair can't fix the stream; use T+P, P+VF, T+VF or P+H")
        }

        val needs = buildList {
            when (state.size) {
                0 -> add("two of temperature, pressure, vapour fraction")
                1 -> add(
                    when (state.single()) {
                        SpecKey.TEMPERATURE -> "pressure or vapour fraction"
                        SpecKey.PRESSURE -> "temperature, vapour fraction or enthalpy"
                        SpecKey.VAPOUR_FRACTION -> "temperature or pressure"
                        else -> "pressure"
                    },
                )
            }
            if (flows.isEmpty()) add("a molar or mass flow")
            if (fs.compounds.isEmpty()) add("components (fluid package)")
            else if (compositionProblem(fs, o) != null) add("composition")
        }
        flows.firstOrNull()?.let { if (s.getValue(it).si <= 0) return SpecStatus.Invalid("Flow must be positive") }
        return if (needs.isEmpty()) SpecStatus.Complete else SpecStatus.UnderSpecified(needs)
    }

    /** Null when the composition is usable; otherwise why not. */
    fun compositionProblem(fs: Flowsheet, o: SimObject): String? {
        if (o.composition.isEmpty()) return "Enter the composition"
        val sum = o.composition.filterKeys { it in fs.compounds }.values.sum()
        if (sum <= 0) return "Composition is all zero"
        if (kotlin.math.abs(sum - 1.0) > COMPOSITION_TOLERANCE) return "Fractions add up to %.6f, not 1".format(java.util.Locale.ROOT, sum)
        return null
    }

    private fun validPair(keys: Set<String>) = keys == setOf(SpecKey.TEMPERATURE, SpecKey.PRESSURE) ||
        keys == setOf(SpecKey.PRESSURE, SpecKey.VAPOUR_FRACTION) ||
        keys == setOf(SpecKey.TEMPERATURE, SpecKey.VAPOUR_FRACTION) ||
        keys == setOf(SpecKey.PRESSURE, SpecKey.MASS_ENTHALPY)

    private fun unitOp(fs: Flowsheet, o: SimObject): SpecStatus {
        val unconnected = Ports.of(o.type).filter { it.required && fs.streamAt(o.id, it) == null }
        val needs = unconnected.map { "${it.label.lowercase()} stream" }.toMutableList()
        val s = o.specs
        exclusiveGroups(o.type).forEach { (what, keys) ->
            when (keys.count { it in s }) {
                0 -> needs += what
                1 -> Unit
                else -> return SpecStatus.OverSpecified("Give only one of $what")
            }
        }
        if (o.type == ObjectType.Splitter) {
            if (SpecKey.SPLIT_1 !in s) needs += "fraction to outlet 1"
            val total = (s[SpecKey.SPLIT_1]?.si ?: 0.0) + (s[SpecKey.SPLIT_2]?.si ?: 0.0)
            if (total > 1.0 + 1e-9) return SpecStatus.Invalid("Split fractions add up to more than 1")
        }
        for (k in listOf(SpecKey.PRESSURE_DROP, SpecKey.PRESSURE_RISE, SpecKey.HOT_PRESSURE_DROP, SpecKey.COLD_PRESSURE_DROP)) {
            s[k]?.let { if (it.si < 0) return SpecStatus.Invalid("${SpecKey.label(k)} can't be negative") }
        }
        s[SpecKey.DUTY]?.let { if (it.si < 0) return SpecStatus.Invalid("Enter duty as a positive number") }
        s[SpecKey.EFFICIENCY]?.let { if (it.si <= 0 || it.si > 1) return SpecStatus.Invalid("Efficiency must be between 0 and 100 %") }
        s[SpecKey.PRESSURE_RATIO]?.let { if (it.si <= 0) return SpecStatus.Invalid("Pressure ratio must be positive") }
        for (k in listOf(SpecKey.SPLIT_1, SpecKey.SPLIT_2)) {
            s[k]?.let { if (it.si !in 0.0..1.0) return SpecStatus.Invalid("Split fractions must be between 0 and 1") }
        }
        when (o.type) {
            ObjectType.PipeSegment -> {
                pipe(o, needs)?.let { return it }
            }
            ObjectType.OrificePlate -> {
                for (k in listOf(SpecKey.ORIFICE_DIAMETER, SpecKey.PIPE_DIAMETER)) {
                    val v = s[k] ?: run { needs += SpecKey.label(k).lowercase(); null } ?: continue
                    if (v.si <= 0) return SpecStatus.Invalid("${SpecKey.label(k)} must be positive")
                }
                val d = s[SpecKey.ORIFICE_DIAMETER]?.si
                val dp = s[SpecKey.PIPE_DIAMETER]?.si
                if (d != null && dp != null && d >= dp) return SpecStatus.Invalid("The orifice must be smaller than the pipe")
            }
            else -> Unit
        }
        return if (needs.isEmpty()) SpecStatus.Complete else SpecStatus.UnderSpecified(needs)
    }

    /** Mirrors the engine validator's pipe rules; adds what is missing to [needs]. */
    private fun pipe(o: SimObject, needs: MutableList<String>): SpecStatus? {
        val s = o.specs
        for (k in listOf(SpecKey.LENGTH, SpecKey.INNER_DIAMETER)) {
            val v = s[k]
            if (v == null) needs += SpecKey.label(k).lowercase()
            else if (v.si <= 0) return SpecStatus.Invalid("${SpecKey.label(k)} must be positive")
        }
        s[SpecKey.ROUGHNESS]?.let { if (it.si < 0) return SpecStatus.Invalid("Roughness can't be negative") }
        s[SpecKey.INCREMENTS]?.let {
            if (it.si < 1 || it.si > 500 || it.si != kotlin.math.floor(it.si)) return SpecStatus.Invalid("Increments must be a whole number from 1 to 500")
        }
        if ((SpecKey.HEAT_TRANSFER_COEFFICIENT in s) != (SpecKey.AMBIENT_TEMPERATURE in s)) {
            needs += if (SpecKey.HEAT_TRANSFER_COEFFICIENT in s) "ambient temperature" else "heat transfer coefficient"
        }
        s[SpecKey.HEAT_TRANSFER_COEFFICIENT]?.let { if (it.si < 0) return SpecStatus.Invalid("Heat transfer coefficient can't be negative") }
        s[SpecKey.AMBIENT_TEMPERATURE]?.let { if (it.si <= 0) return SpecStatus.Invalid("Ambient temperature must be above absolute zero") }
        return null
    }

    /** Alternative specs per unit type: exactly one of each group must be given. Mirrors the engine validator. */
    fun exclusiveGroups(type: ObjectType): List<Pair<String, List<String>>> = when (type) {
        ObjectType.Valve -> listOf("outlet pressure or pressure drop" to listOf(SpecKey.OUTLET_PRESSURE, SpecKey.PRESSURE_DROP))
        ObjectType.Heater, ObjectType.Cooler -> listOf("outlet temperature or duty" to listOf(SpecKey.OUTLET_TEMPERATURE, SpecKey.DUTY))
        ObjectType.Pump -> listOf("outlet pressure or pressure rise" to listOf(SpecKey.OUTLET_PRESSURE, SpecKey.PRESSURE_RISE))
        ObjectType.Compressor -> listOf(
            "outlet pressure, pressure rise or pressure ratio" to listOf(SpecKey.OUTLET_PRESSURE, SpecKey.PRESSURE_RISE, SpecKey.PRESSURE_RATIO),
        )
        ObjectType.Expander -> listOf(
            "outlet pressure, pressure drop or pressure ratio" to listOf(SpecKey.OUTLET_PRESSURE, SpecKey.PRESSURE_DROP, SpecKey.PRESSURE_RATIO),
        )
        ObjectType.HeatExchanger -> listOf(
            "duty or one outlet temperature" to listOf(SpecKey.DUTY, SpecKey.HOT_OUTLET_TEMPERATURE, SpecKey.COLD_OUTLET_TEMPERATURE),
        )
        else -> emptyList()
    }

    /** Flowsheet-level problems that block a solve, with the object they belong to (null = flowsheet). */
    fun problems(fs: Flowsheet): List<Pair<String?, String>> = buildList {
        if (fs.propertyPackage.isNullOrBlank()) add(null to "Choose a property package")
        fs.objects.filter { it.type == ObjectType.Adjust }.drop(1).forEach { add(it.id to "${it.tag}: only one Adjust per flowsheet is supported") }
        if (fs.compounds.isEmpty()) add(null to "Add components")
        if (fs.objects.isEmpty()) add(null to "The flowsheet is empty")
        for (o in fs.objects) {
            when (val st = status(fs, o)) {
                SpecStatus.Complete -> Unit
                is SpecStatus.UnderSpecified -> add(o.id to "${o.tag}: needs ${st.needs.joinToString("; ")}")
                is SpecStatus.OverSpecified -> add(o.id to "${o.tag}: ${st.problem}")
                is SpecStatus.Invalid -> add(o.id to "${o.tag}: ${st.problem}")
            }
        }
    }
}
