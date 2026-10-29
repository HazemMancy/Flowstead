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
    /** Columns with a condenser: total (default) or partial. */
    const val CONDENSER = "condenser"
    val CONDENSER_OPTIONS = listOf("total" to "Total condenser", "partial" to "Partial condenser (vapour product)")
    const val DISTILLATE_COMPONENT = "distillateComponent"
    const val BOTTOMS_COMPONENT = "bottomsComponent"
    const val LIGHT_KEY = "lightKey"
    const val HEAVY_KEY = "heavyKey"
    const val COLUMN_SOLVER = "solver"
    val COLUMN_SOLVERS = listOf(
        "auto" to "Automatic",
        "bubble_point" to "Wang-Henke (bubble point)",
        "modified_bubble_point" to "Modified Wang-Henke",
        "simultaneous_correction" to "Naphtali-Sandholm (simultaneous correction)",
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
    /** Valve: installed valve's rated (full-open) Cv, US units; optional, for the opening (D47). */
    const val RATED_CV = "ratedCv"
    /** Valve: IEC 60534 FL and xT; optional (a globe valve's 0.9 and 0.72), typically set from [ValveLibrary]. */
    const val VALVE_FL = "valveFl"
    const val VALVE_XT = "valveXt"
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
    /** Pipe outside diameter: heat-transfer area only (1.15 × inside when absent). */
    const val OUTER_DIAMETER = "outerDiameter"
    const val ELEVATION_CHANGE = "elevationChange"
    const val ROUGHNESS = "roughness"
    /** Calculation increments along a pipe (whole number). */
    const val INCREMENTS = "increments"
    const val HEAT_TRANSFER_COEFFICIENT = "heatTransferCoefficient"
    const val AMBIENT_TEMPERATURE = "ambientTemperature"
    const val ORIFICE_DIAMETER = "orificeDiameter"
    const val PIPE_DIAMETER = "pipeDiameter"

    /** Tank volume, for the liquid residence time. */
    const val VOLUME = "volume"
    /** Plug-flow reactor length; the diameter follows from the volume. */
    const val REACTOR_LENGTH = "reactorLength"

    /** Component splitter: fraction of [compound]'s feed sent to outlet 1 (overhead). */
    const val SPLIT_PREFIX = "split:"
    fun split(compound: String) = SPLIT_PREFIX + compound
    fun splitCompound(key: String): String? = key.takeIf { it.startsWith(SPLIT_PREFIX) }?.removePrefix(SPLIT_PREFIX)

    // Columns: trays are theoretical stages from the top, condenser and reboiler excluded (as in HYSYS).
    const val TRAYS = "trays"
    fun feedStage(port: Int) = "feedStage${port + 1}"
    const val TOP_PRESSURE = "topPressure"
    const val COLUMN_PRESSURE_DROP = "columnPressureDrop"
    const val REFLUX_RATIO = "refluxRatio"
    const val DISTILLATE_RATE = "distillateRate"
    const val DISTILLATE_FRACTION = "distillateFraction"
    const val DISTILLATE_RECOVERY = "distillateRecovery"
    const val BOILUP_RATIO = "boilupRatio"
    const val BOTTOMS_RATE = "bottomsRate"
    const val REBOILER_DUTY = "reboilerDuty"
    const val BOTTOMS_FRACTION = "bottomsFraction"
    const val BOTTOMS_RECOVERY = "bottomsRecovery"
    const val VAPOUR_RATE = "vapourRate"
    const val LIGHT_KEY_IN_BOTTOMS = "lightKeyInBottoms"
    const val HEAVY_KEY_IN_DISTILLATE = "heavyKeyInDistillate"
    const val REBOILER_PRESSURE = "reboilerPressure"
    // No condenser duty or stage temperature specs: DWSIM's column solvers don't handle them reliably (D27).
    val CONDENSER_SPECS = listOf(REFLUX_RATIO, DISTILLATE_RATE, DISTILLATE_FRACTION, DISTILLATE_RECOVERY)
    val REBOILER_SPECS = listOf(BOILUP_RATIO, BOTTOMS_RATE, REBOILER_DUTY, BOTTOMS_FRACTION, BOTTOMS_RECOVERY)

    /** Small lengths that read better in mm or inches than in m or ft. */
    val SMALL_LENGTHS = setOf(INNER_DIAMETER, OUTER_DIAMETER, ROUGHNESS, ORIFICE_DIAMETER, PIPE_DIAMETER)

    val STREAM_STATE = listOf(TEMPERATURE, PRESSURE, VAPOUR_FRACTION, MASS_ENTHALPY)
    val STREAM_FLOW = listOf(MOLAR_FLOW, MASS_FLOW)

    /** The spec keys each type takes (component-splitter fractions aside). Mirrors the engine's Validator.SpecKeysFor. */
    fun forType(type: ObjectType): List<String> = when (type) {
        ObjectType.MaterialStream -> STREAM_STATE + STREAM_FLOW
        ObjectType.Valve -> listOf(OUTLET_PRESSURE, PRESSURE_DROP, RATED_CV, VALVE_FL, VALVE_XT)
        ObjectType.Heater, ObjectType.Cooler, ObjectType.AirCooler -> listOf(OUTLET_TEMPERATURE, DUTY, PRESSURE_DROP)
        ObjectType.Pump -> listOf(OUTLET_PRESSURE, PRESSURE_RISE, EFFICIENCY)
        ObjectType.Compressor -> listOf(OUTLET_PRESSURE, PRESSURE_RISE, PRESSURE_RATIO, EFFICIENCY)
        ObjectType.Expander -> listOf(OUTLET_PRESSURE, PRESSURE_DROP, PRESSURE_RATIO, EFFICIENCY)
        ObjectType.HeatExchanger -> listOf(DUTY, HOT_OUTLET_TEMPERATURE, COLD_OUTLET_TEMPERATURE, HOT_PRESSURE_DROP, COLD_PRESSURE_DROP)
        ObjectType.Splitter -> listOf(SPLIT_1, SPLIT_2)
        ObjectType.PipeSegment -> listOf(LENGTH, INNER_DIAMETER, OUTER_DIAMETER, ELEVATION_CHANGE, ROUGHNESS, INCREMENTS, HEAT_TRANSFER_COEFFICIENT, AMBIENT_TEMPERATURE)
        ObjectType.OrificePlate -> listOf(ORIFICE_DIAMETER, PIPE_DIAMETER)
        ObjectType.Tank -> listOf(VOLUME)
        ObjectType.DistillationColumn -> listOf(TOP_PRESSURE, COLUMN_PRESSURE_DROP) + CONDENSER_SPECS + REBOILER_SPECS
        ObjectType.RefluxedAbsorber -> listOf(TOP_PRESSURE, COLUMN_PRESSURE_DROP) + CONDENSER_SPECS
        ObjectType.ReboiledAbsorber -> listOf(TOP_PRESSURE, COLUMN_PRESSURE_DROP) + REBOILER_SPECS
        ObjectType.Absorber -> listOf(TOP_PRESSURE, COLUMN_PRESSURE_DROP)
        ObjectType.ShortcutColumn -> listOf(TOP_PRESSURE, REBOILER_PRESSURE, REFLUX_RATIO, LIGHT_KEY_IN_BOTTOMS, HEAVY_KEY_IN_DISTILLATE)
        else -> emptyList()
    }

    fun quantity(key: String): Quantity = when (key) {
        TEMPERATURE, OUTLET_TEMPERATURE, HOT_OUTLET_TEMPERATURE, COLD_OUTLET_TEMPERATURE, AMBIENT_TEMPERATURE -> Quantity.Temperature
        PRESSURE, OUTLET_PRESSURE, TOP_PRESSURE, REBOILER_PRESSURE -> Quantity.Pressure
        PRESSURE_DROP, PRESSURE_RISE, HOT_PRESSURE_DROP, COLD_PRESSURE_DROP, COLUMN_PRESSURE_DROP -> Quantity.PressureDifference
        VAPOUR_FRACTION, EFFICIENCY, SPLIT_1, SPLIT_2, DISTILLATE_FRACTION, DISTILLATE_RECOVERY, BOTTOMS_FRACTION,
        BOTTOMS_RECOVERY, LIGHT_KEY_IN_BOTTOMS, HEAVY_KEY_IN_DISTILLATE -> Quantity.Fraction
        PRESSURE_RATIO, REFLUX_RATIO, BOILUP_RATIO, RATED_CV, VALVE_FL, VALVE_XT -> Quantity.Ratio
        TRAYS, "feedStage1", "feedStage2", "feedStage3" -> Quantity.Count
        DISTILLATE_RATE, BOTTOMS_RATE, VAPOUR_RATE -> Quantity.MolarFlow
        REBOILER_DUTY -> Quantity.Power
        MASS_ENTHALPY -> Quantity.MassEnthalpy
        MOLAR_FLOW -> Quantity.MolarFlow
        MASS_FLOW -> Quantity.MassFlow
        DUTY -> Quantity.Power
        LENGTH, INNER_DIAMETER, OUTER_DIAMETER, ELEVATION_CHANGE, ROUGHNESS, ORIFICE_DIAMETER, PIPE_DIAMETER, REACTOR_LENGTH -> Quantity.Length
        INCREMENTS -> Quantity.Count
        HEAT_TRANSFER_COEFFICIENT -> Quantity.HeatTransferCoefficient
        VOLUME -> Quantity.Volume
        else -> if (key.startsWith(SPLIT_PREFIX)) Quantity.Fraction else throw IllegalArgumentException("Unknown spec '$key'")
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
        RATED_CV -> "Rated Cv (installed valve, optional)"
        VALVE_FL -> "Liquid pressure recovery factor FL"
        VALVE_XT -> "Pressure differential ratio factor xT"
        SPLIT_1 -> "Fraction to outlet 1"
        SPLIT_2 -> "Fraction to outlet 2"
        HOT_OUTLET_TEMPERATURE -> "Hot outlet temperature"
        COLD_OUTLET_TEMPERATURE -> "Cold outlet temperature"
        HOT_PRESSURE_DROP -> "Hot side pressure drop"
        COLD_PRESSURE_DROP -> "Cold side pressure drop"
        LENGTH -> "Length"
        INNER_DIAMETER -> "Inside diameter"
        OUTER_DIAMETER -> "Outside diameter"
        ELEVATION_CHANGE -> "Elevation change"
        ROUGHNESS -> "Wall roughness"
        INCREMENTS -> "Calculation increments"
        HEAT_TRANSFER_COEFFICIENT -> "Overall U (to ambient)"
        AMBIENT_TEMPERATURE -> "Ambient temperature"
        ORIFICE_DIAMETER -> "Orifice diameter"
        PIPE_DIAMETER -> "Pipe inside diameter"
        VOLUME -> "Volume"
        REACTOR_LENGTH -> "Length"
        TRAYS -> "Theoretical trays"
        "feedStage1" -> "Feed 1 tray"
        "feedStage2" -> "Feed 2 tray"
        "feedStage3" -> "Feed 3 tray"
        TOP_PRESSURE -> "Top pressure"
        COLUMN_PRESSURE_DROP -> "Column pressure drop"
        REFLUX_RATIO -> "Reflux ratio"
        DISTILLATE_RATE -> "Distillate rate"
        DISTILLATE_FRACTION -> "Distillate mole fraction"
        DISTILLATE_RECOVERY -> "Recovery to distillate"
        BOILUP_RATIO -> "Boil-up ratio"
        BOTTOMS_RATE -> "Bottoms rate"
        REBOILER_DUTY -> "Reboiler duty"
        BOTTOMS_FRACTION -> "Bottoms mole fraction"
        BOTTOMS_RECOVERY -> "Recovery to bottoms"
        VAPOUR_RATE -> "Vapour product rate"
        LIGHT_KEY_IN_BOTTOMS -> "Light key in bottoms"
        HEAVY_KEY_IN_DISTILLATE -> "Heavy key in distillate"
        REBOILER_PRESSURE -> "Reboiler pressure"
        else -> splitCompound(key)?.let { "$it to overhead" } ?: key
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

    /** Degrees of freedom for [o], counting values that Sets give it. */
    fun status(fs: Flowsheet, o: SimObject): SpecStatus {
        val applied = withSets(fs)
        return statusOf(applied, applied.obj(o.id) ?: o)
    }

    private fun statusOf(fs: Flowsheet, o: SimObject): SpecStatus = when (o.type) {
        ObjectType.MaterialStream -> if (fs.isFeed(o)) feedStream(fs, o) else SpecStatus.Complete
        ObjectType.EnergyStream -> SpecStatus.Complete
        ObjectType.Adjust -> adjust(fs, o)
        ObjectType.Set -> set(fs, o)
        ObjectType.ReliefValve -> reliefValve(fs, o)
        else -> unitOp(fs, o)
    }

    /**
     * A PSV on the flowsheet is its Safety analysis device (same id): it needs what it protects and its set pressure.
     * It never stops a solve; the engine does not see it.
     */
    fun reliefValve(fs: Flowsheet, o: SimObject): SpecStatus {
        val d = fs.safety?.devices?.firstOrNull { it.id == o.id }
        val needs = buildList {
            if (d?.protectedId == null) add("the equipment it protects")
            if (d?.setPressure == null) add("set pressure")
        }
        if (d?.protectedId != null && fs.obj(d.protectedId) == null) return SpecStatus.Invalid("The protected equipment no longer exists")
        return if (needs.isEmpty()) SpecStatus.Complete else SpecStatus.UnderSpecified(needs)
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

    /** Mirrors the engine's ValidateSets (loops are left to the engine). */
    fun set(fs: Flowsheet, o: SimObject): SpecStatus {
        val needs = mutableListOf<String>()
        val target = o.refs[SetKeys.TARGET_OBJECT]?.let { fs.obj(it) }
        val source = o.refs[SetKeys.SOURCE_OBJECT]?.let { fs.obj(it) }
        val key = o.refs[SetKeys.VARIABLE]
        if (o.refs[SetKeys.TARGET_OBJECT] != null && target == null) return SpecStatus.Invalid("The target object no longer exists")
        if (o.refs[SetKeys.SOURCE_OBJECT] != null && source == null) return SpecStatus.Invalid("The source object no longer exists")
        if (target == null || key == null) needs += "target object and variable"
        if (source == null) needs += "source object"
        if (target != null && key != null) {
            if (key !in SpecKey.forType(target.type) || (target.type == ObjectType.MaterialStream && !fs.isFeed(target))) {
                return SpecStatus.Invalid("${target.tag} can't take ${SpecKey.label(key).lowercase()} as a specification")
            }
        }
        if (source != null && target != null && source.id == target.id) return SpecStatus.Invalid("Source and target must be different objects")
        if (source != null && key != null && key !in source.specs) {
            return SpecStatus.Invalid("${source.tag} has no ${SpecKey.label(key).lowercase()} specified to copy")
        }
        return if (needs.isEmpty()) SpecStatus.Complete else SpecStatus.UnderSpecified(needs)
    }

    /** Objects whose specs a Set drives: their status must count the Set's value as given. */
    fun withSets(fs: Flowsheet): Flowsheet {
        val sets = fs.objects.filter { it.type == ObjectType.Set && set(fs, it) == SpecStatus.Complete }
        if (sets.isEmpty()) return fs
        val byId = fs.objects.associateBy { it.id }.toMutableMap()
        repeat(sets.size) {
            for (s in sets) {
                val source = byId.getValue(s.refs.getValue(SetKeys.SOURCE_OBJECT))
                val target = byId.getValue(s.refs.getValue(SetKeys.TARGET_OBJECT))
                val key = s.refs.getValue(SetKeys.VARIABLE)
                val x = source.specs[key] ?: continue
                val v = (s.specs[SetKeys.MULTIPLIER]?.si ?: 1.0) * x.si + (s.specs[SetKeys.OFFSET]?.si ?: 0.0)
                byId[target.id] = target.copy(specs = target.specs + (key to Spec(v, target.specs[key]?.unit ?: x.unit)))
            }
        }
        return fs.copy(objects = fs.objects.map { byId.getValue(it.id) })
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
        val onCurve = o.type in listOf(ObjectType.Pump, ObjectType.Compressor) && o.curve != null
        if (onCurve) {
            curveProblem(o)?.let { return SpecStatus.Invalid(it) }
            val pressures = listOf(SpecKey.OUTLET_PRESSURE, SpecKey.PRESSURE_RISE, SpecKey.PRESSURE_RATIO).count { it in s }
            val curve = o.curve!!
            when {
                pressures > 1 -> return SpecStatus.OverSpecified("Give one of outlet pressure, pressure rise or pressure ratio")
                pressures == 1 && !((curve.speed ?: 0.0) > 0) ->
                    return SpecStatus.OverSpecified("It runs on its curve, which sets the outlet pressure; remove the outlet pressure or pressure rise, or give the curve's speed so the engine finds the speed that meets it")
                pressures == 1 && curve.operatingSpeed != null ->
                    return SpecStatus.OverSpecified("Both the running speed and the outlet pressure are given; remove one (without the speed, the engine finds it)")
            }
        }
        exclusiveGroups(o.type).filterNot { onCurve }.forEach { (what, keys) ->
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
            ObjectType.ShortcutColumn, ObjectType.DistillationColumn, ObjectType.Absorber,
            ObjectType.RefluxedAbsorber, ObjectType.ReboiledAbsorber -> Columns.check(fs, o, needs)?.let { return it }
            ObjectType.ConversionReactor, ObjectType.EquilibriumReactor, ObjectType.GibbsReactor,
            ObjectType.Cstr, ObjectType.Pfr -> Reactors.check(fs, o, needs)?.let { return it }
            ObjectType.Tank -> s[SpecKey.VOLUME]?.let { if (it.si <= 0) return SpecStatus.Invalid("Volume must be positive") }
            ObjectType.ComponentSplitter -> {
                val splits = s.filterKeys { it.startsWith(SpecKey.SPLIT_PREFIX) }
                if (splits.isEmpty()) needs += "fraction of each component to the overhead"
                splits.forEach { (k, v) ->
                    if (v.si !in 0.0..1.0) return SpecStatus.Invalid("${SpecKey.label(k)} must be between 0 and 1")
                    if (SpecKey.splitCompound(k) !in fs.compounds) return SpecStatus.Invalid("${SpecKey.splitCompound(k)} is not in the component list")
                }
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
        s[SpecKey.OUTER_DIAMETER]?.let { od ->
            if (od.si <= (s[SpecKey.INNER_DIAMETER]?.si ?: 0.0)) return SpecStatus.Invalid("The outside diameter must be larger than the inside diameter")
        }
        val dz = s[SpecKey.ELEVATION_CHANGE]?.si
        val len = s[SpecKey.LENGTH]?.si
        if (dz != null && len != null && kotlin.math.abs(dz) >= len) return SpecStatus.Invalid("The elevation change must be smaller than the length")
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

    /** What is wrong with a pump or compressor curve, or null. Mirrors the engine validator (D48). */
    fun curveProblem(o: SimObject): String? {
        val curve = o.curve ?: return null
        pointsProblem(o, curve.points)?.let { return it }
        val speeds = curve.speedLines.map { it.speed } + (curve.speed ?: 0.0)
        return when {
            (curve.speedLines.isNotEmpty() || curve.operatingSpeed != null) && !((curve.speed ?: 0.0) > 0) ->
                "Give the curve's own speed to use other speed lines or an operating speed"
            curve.operatingSpeed != null && curve.operatingSpeed <= 0 -> "The operating speed must be positive"
            curve.speedLines.any { it.speed <= 0 } -> "Every speed line needs a positive speed"
            speeds.distinct().size != speeds.size -> "Two curves have the same speed"
            else -> curve.speedLines.firstNotNullOfOrNull { l ->
                pointsProblem(o, l.points)?.let { "At ${rpm(l.speed)} rpm: ${it.replaceFirstChar(Char::lowercase)}" }
                    ?: when {
                        l.points.any { it.efficiency != null } != curve.points.any { it.efficiency != null } -> "Give efficiencies on every speed line or on none"
                        l.points.any { it.npshr != null } != curve.points.any { it.npshr != null } -> "Give NPSH required on every speed line or on none"
                        else -> null
                    }
            }
        }
    }

    private fun rpm(v: Double) = if (v == Math.rint(v)) v.toLong().toString() else v.toString()

    private fun pointsProblem(o: SimObject, p: List<CurvePoint>): String? {
        return when {
            p.size < 2 -> "The performance curve needs at least two points"
            o.curve?.order?.let { it < 1 || it > 5 } == true -> "The polynomial order must be 1 to 5"
            p.any { !(it.flow > 0) || !(it.head > 0) } -> "Every curve point needs a positive flow and head"
            p.map { it.flow }.distinct().size != p.size -> "Two curve points have the same flow"
            p.any { it.efficiency != null } && p.any { it.efficiency == null || it.efficiency <= 0 || it.efficiency > 1 } ->
                "Give the efficiency at every curve point (above 0, up to 100 %), or at none"
            p.any { it.npshr != null } && (o.type != ObjectType.Pump || p.any { it.npshr == null || it.npshr < 0 }) ->
                "Give NPSH required at every point (0 or more), or at none"
            else -> null
        }
    }

    /** Alternative specs per unit type: exactly one of each group must be given. Mirrors the engine validator. */
    fun exclusiveGroups(type: ObjectType): List<Pair<String, List<String>>> = when (type) {
        ObjectType.Valve -> listOf("outlet pressure or pressure drop" to listOf(SpecKey.OUTLET_PRESSURE, SpecKey.PRESSURE_DROP))
        ObjectType.Heater, ObjectType.Cooler, ObjectType.AirCooler -> listOf("outlet temperature or duty" to listOf(SpecKey.OUTLET_TEMPERATURE, SpecKey.DUTY))
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

    /** Problems that stop any solve: nothing can be calculated until they are fixed (null = flowsheet). */
    fun blocking(flowsheet: Flowsheet): List<Pair<String?, String>> = buildList {
        if (flowsheet.propertyPackage.isNullOrBlank()) add(null to "Choose a property package")
        flowsheet.objects.filter { it.type == ObjectType.Adjust }.drop(1).forEach { add(it.id to "${it.tag}: only one Adjust per flowsheet is supported") }
        if (flowsheet.compounds.isEmpty()) add(null to "Add components")
        if (flowsheet.objects.isEmpty()) add(null to "The flowsheet is empty")
    }

    /**
     * Everything left to give, with the object it belongs to (null = flowsheet): [blocking] problems, then each object's
     * own. Only the blocking ones stop a solve; objects with their own problems are left out of it (D83, [Readiness]).
     */
    fun problems(flowsheet: Flowsheet): List<Pair<String?, String>> = buildList {
        val fs = withSets(flowsheet)
        addAll(blocking(flowsheet))
        for (o in fs.objects.filter { it.type != ObjectType.ReliefValve }) {
            when (val st = statusOf(fs, o)) {
                SpecStatus.Complete -> Unit
                is SpecStatus.UnderSpecified -> add(o.id to "${o.tag}: needs ${st.needs.joinToString("; ")}")
                is SpecStatus.OverSpecified -> add(o.id to "${o.tag}: ${st.problem}")
                is SpecStatus.Invalid -> add(o.id to "${o.tag}: ${st.problem}")
            }
        }
    }
}
