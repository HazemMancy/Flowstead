/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

/** Column layout and checks. Mirrors the engine's Columns.cs and Validator.ValidateColumn. */
object Columns {
    fun hasCondenser(t: ObjectType) = t == ObjectType.DistillationColumn || t == ObjectType.RefluxedAbsorber || t == ObjectType.ShortcutColumn
    fun hasReboiler(t: ObjectType) = t == ObjectType.DistillationColumn || t == ObjectType.ReboiledAbsorber || t == ObjectType.ShortcutColumn

    /** Inlet ports whose tray the user gives (the others enter a fixed tray). */
    fun variableFeedPorts(t: ObjectType): List<Int> = when (t) {
        ObjectType.DistillationColumn -> listOf(0, 1, 2)
        ObjectType.RefluxedAbsorber, ObjectType.ReboiledAbsorber -> listOf(1)
        ObjectType.Absorber -> listOf(2)
        else -> emptyList()
    }

    /** Null when nothing is wrong beyond what was added to [needs]. */
    fun check(fs: Flowsheet, o: SimObject, needs: MutableList<String>): SpecStatus? {
        val s = o.specs
        fun compound(ref: String, what: String): SpecStatus? {
            val c = o.refs[ref]
            if (c == null) needs += what else if (c !in fs.compounds) return SpecStatus.Invalid("$c is not in the component list")
            return null
        }
        s[SpecKey.TOP_PRESSURE]?.let { if (it.si <= 0) return SpecStatus.Invalid("Pressure must be above zero absolute") }
            ?: run { needs += if (o.type == ObjectType.ShortcutColumn) "condenser pressure" else "top pressure" }

        if (o.type == ObjectType.ShortcutColumn) {
            compound(ChoiceKey.LIGHT_KEY, "light key")?.let { return it }
            compound(ChoiceKey.HEAVY_KEY, "heavy key")?.let { return it }
            if (o.refs[ChoiceKey.LIGHT_KEY] != null && o.refs[ChoiceKey.LIGHT_KEY] == o.refs[ChoiceKey.HEAVY_KEY]) {
                return SpecStatus.Invalid("The light and heavy keys must be different")
            }
            for (k in listOf(SpecKey.LIGHT_KEY_IN_BOTTOMS, SpecKey.HEAVY_KEY_IN_DISTILLATE)) {
                val v = s[k] ?: run { needs += SpecKey.label(k).lowercase(); null } ?: continue
                if (v.si <= 0 || v.si >= 1) return SpecStatus.Invalid("${SpecKey.label(k)} must be between 0 and 1")
            }
            s[SpecKey.REFLUX_RATIO]?.let { if (it.si <= 0) return SpecStatus.Invalid("Reflux ratio must be positive") } ?: run { needs += "reflux ratio" }
            return null
        }

        val trays = s[SpecKey.TRAYS]?.si
        if (trays == null) needs += "number of trays"
        else if (trays < 1 || trays > 200 || trays != kotlin.math.floor(trays)) return SpecStatus.Invalid("Trays must be a whole number from 1 to 200")
        s[SpecKey.COLUMN_PRESSURE_DROP]?.let { if (it.si < 0) return SpecStatus.Invalid("Column pressure drop can't be negative") }
        for (port in variableFeedPorts(o.type)) {
            if (fs.connections.none { it.toObject == o.id && it.toPort == port }) continue
            val key = SpecKey.feedStage(port)
            val tray = s[key]?.si
            if (tray == null) needs += "feed ${port + 1} tray"
            else if (trays != null && (tray < 1 || tray > trays || tray != kotlin.math.floor(tray))) {
                return SpecStatus.Invalid("Feed ${port + 1} tray must be a whole number from 1 to ${trays.toInt()}")
            }
        }
        fun oneOf(keys: List<String>, where: String, ref: String): SpecStatus? {
            val given = keys.filter { it in s }
            when {
                given.isEmpty() -> needs += "$where specification"
                given.size > 1 -> return SpecStatus.OverSpecified("Give one $where specification")
                else -> {
                    val k = given.single()
                    val v = s.getValue(k).si
                    if (k.endsWith("Fraction") || k.endsWith("Recovery")) {
                        compound(ref, "component for the $where specification")?.let { return it }
                        if (v <= 0 || v > 1 || (k.endsWith("Fraction") && v >= 1)) return SpecStatus.Invalid("${SpecKey.label(k)} must be between 0 and 1")
                    } else if ((k.endsWith("Ratio") || k.endsWith("Rate")) && v <= 0) {
                        return SpecStatus.Invalid("${SpecKey.label(k)} must be positive")
                    }
                }
            }
            return null
        }
        if (hasCondenser(o.type)) {
            oneOf(SpecKey.CONDENSER_SPECS, "condenser", ChoiceKey.DISTILLATE_COMPONENT)?.let { return it }
            if (o.refs[ChoiceKey.CONDENSER] == "partial") {
                if (SpecKey.VAPOUR_RATE !in s) needs += "vapour product rate"
                if (fs.connections.none { it.fromObject == o.id && it.fromPort == 2 }) needs += "vapour product stream"
            }
        }
        if (hasReboiler(o.type)) oneOf(SpecKey.REBOILER_SPECS, "reboiler", ChoiceKey.BOTTOMS_COMPONENT)?.let { return it }
        return null
    }
}
