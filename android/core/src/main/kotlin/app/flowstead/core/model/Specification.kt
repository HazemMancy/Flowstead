/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.units.Quantity

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

    val STREAM_STATE = listOf(TEMPERATURE, PRESSURE, VAPOUR_FRACTION, MASS_ENTHALPY)
    val STREAM_FLOW = listOf(MOLAR_FLOW, MASS_FLOW)

    fun quantity(key: String): Quantity = when (key) {
        TEMPERATURE, OUTLET_TEMPERATURE -> Quantity.Temperature
        PRESSURE, OUTLET_PRESSURE -> Quantity.Pressure
        PRESSURE_DROP -> Quantity.PressureDifference
        VAPOUR_FRACTION -> Quantity.Fraction
        MASS_ENTHALPY -> Quantity.MassEnthalpy
        MOLAR_FLOW -> Quantity.MolarFlow
        MASS_FLOW -> Quantity.MassFlow
        DUTY -> Quantity.Power
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
        else -> unitOp(fs, o)
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
        when (o.type) {
            ObjectType.Valve -> when {
                SpecKey.OUTLET_PRESSURE in s && SpecKey.PRESSURE_DROP in s ->
                    return SpecStatus.OverSpecified("Give outlet pressure or pressure drop, not both")
                SpecKey.OUTLET_PRESSURE !in s && SpecKey.PRESSURE_DROP !in s -> needs += "outlet pressure or pressure drop"
            }
            ObjectType.Heater, ObjectType.Cooler -> when {
                SpecKey.OUTLET_TEMPERATURE in s && SpecKey.DUTY in s ->
                    return SpecStatus.OverSpecified("Give outlet temperature or duty, not both")
                SpecKey.OUTLET_TEMPERATURE !in s && SpecKey.DUTY !in s -> needs += "outlet temperature or duty"
            }
            else -> Unit
        }
        s[SpecKey.PRESSURE_DROP]?.let { if (it.si < 0) return SpecStatus.Invalid("Pressure drop can't be negative") }
        s[SpecKey.DUTY]?.let { if (it.si < 0) return SpecStatus.Invalid("Enter duty as a positive number") }
        return if (needs.isEmpty()) SpecStatus.Complete else SpecStatus.UnderSpecified(needs)
    }

    /** Flowsheet-level problems that block a solve, with the object they belong to (null = flowsheet). */
    fun problems(fs: Flowsheet): List<Pair<String?, String>> = buildList {
        if (fs.propertyPackage.isNullOrBlank()) add(null to "Choose a property package")
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
