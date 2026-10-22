/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.format.ValueFormat
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.UnitSet
import kotlinx.serialization.Serializable

/**
 * A table of an object's results drawn on the flowsheet, as HYSYS's property tables on the PFD: the
 * properties the user picked, placed at an offset ([dx], [dy], world units) from the object so it moves with it.
 */
@Serializable
data class PropertyTable(
    val objectId: String,
    val keys: List<String>,
    val dx: Double = 0.0,
    val dy: Double = 60.0,
)

object PropertyTables {
    /** Properties a table can show for [o]: the stream properties, or the unit's own results. */
    fun available(fs: Flowsheet, o: SimObject): List<String> =
        if (o.type == ObjectType.MaterialStream) ResultKeys.STREAM + listOf("molarEnthalpy", "stdLiquidVolumeFlow") else ResultKeys.forObject(fs, o)

    /** What a new table shows: conditions and flows for a stream, the first results of a unit. */
    fun defaults(fs: Flowsheet, o: SimObject): List<String> =
        if (o.type == ObjectType.MaterialStream) listOf("temperature", "pressure", "vapourFraction", "molarFlow", "massFlow")
        else available(fs, o).take(4)

    data class Row(val label: String, val value: String, val unit: String)

    /** The table's rows in the user's units; "—" when the engine returned nothing for a property. */
    fun rows(fs: Flowsheet, table: PropertyTable, view: ResultView, units: UnitSet): List<Row> {
        val o = fs.objects.firstOrNull { it.id == table.objectId } ?: return emptyList()
        val ctx = UnitContext(fs.atmosphericPressurePa)
        return table.keys.map { key ->
            val u = units.unitFor(ResultKeys.quantityFor(o, key))
            val text = when (val v = view.value(o.id, key)) {
                is DisplayValue.Calculated -> ValueFormat.number(u.fromSi(v.si, ctx), u.decimals)
                is DisplayValue.Specified -> ValueFormat.number(u.fromSi(v.si, ctx), u.decimals)
                else -> "—"
            }
            Row(ResultKeys.label(key), text, u.symbol)
        }
    }
}
