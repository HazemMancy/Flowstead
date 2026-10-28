/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.core.model

import app.thermodock.core.api.Phases
import app.thermodock.core.units.EngUnit
import app.thermodock.core.units.Quantity
import app.thermodock.core.units.UnitSet
import app.thermodock.core.units.Units

/**
 * A material stream's results laid out as in a HYSYS workbook: Overall, then Vapour, Liquid and Aqueous phase
 * blocks, each only when the phase is present. Used by the Results screen, the results export and the heat and
 * material balance so all three show the same rows.
 */
object StreamLayout {
    /** One row: [key] read at stream level ([phase] null) or from that phase; [standardGas] shows a molar flow as standard gas volume. */
    data class Row(val label: String, val key: String, val phase: String?, val standardGas: Boolean = false)

    data class Section(val title: String, val phase: String, val rows: List<Row>)

    private fun phaseRows(name: String, phase: String, vapour: Boolean): List<Row> = buildList {
        add(Row("$name phase molar flow", "molarFlow", phase))
        add(Row("$name phase mass flow", "massFlow", phase))
        add(Row("$name phase actual volume flow", "volumeFlow", phase))
        if (vapour) add(Row("$name phase std gas flow", "molarFlow", phase, standardGas = true))
        add(Row("$name phase std ideal liquid volume flow", "stdLiquidVolumeFlow", phase))
        add(Row("$name phase molecular weight", "molecularWeight", phase))
        add(Row("$name phase mass density", "density", phase))
        add(Row("$name phase viscosity", "viscosity", phase))
        add(Row("$name phase kinematic viscosity", "kinematicViscosity", phase))
        add(Row("$name phase Z factor", "compressibilityFactor", phase))
        add(Row("$name phase mass Cp", "massHeatCapacity", phase))
        add(Row("$name phase Cp/Cv", "heatCapacityRatio", phase))
        add(Row("$name phase thermal conductivity", "thermalConductivity", phase))
        if (!vapour) add(Row("$name phase surface tension", "surfaceTension", phase))
        add(Row("$name phase molar enthalpy", "molarEnthalpy", phase))
        add(Row("$name phase mass entropy", "massEntropy", phase))
    }

    val SECTIONS: List<Section> = listOf(
        Section(
            "Overall", Phases.OVERALL,
            listOf(
                Row("Vapour fraction", "vapourFraction", null),
                Row("Temperature", "temperature", null),
                Row("Pressure", "pressure", null),
                Row("Molar flow", "molarFlow", null),
                Row("Mass flow", "massFlow", null),
                Row("Actual volume flow", "volumeFlow", Phases.OVERALL),
                Row("Std ideal liquid volume flow", "stdLiquidVolumeFlow", Phases.OVERALL),
                Row("Molecular weight", "molecularWeight", Phases.OVERALL),
                Row("Mass density", "density", Phases.OVERALL),
                Row("Molar enthalpy", "molarEnthalpy", Phases.OVERALL),
                Row("Mass enthalpy", "massEnthalpy", null),
                Row("Mass entropy", "massEntropy", Phases.OVERALL),
                Row("Mass Cp", "massHeatCapacity", Phases.OVERALL),
                // Single-phase streams only: these come from the one phase present.
                Row("Viscosity", "viscosity", null),
                Row("Z factor", "compressibilityFactor", null),
                Row("Thermal conductivity", "thermalConductivity", null),
                // Bubble and dew points: temperatures at the stream's pressure, pressures at its temperature.
                Row("Bubble point temperature", "bubblePointTemperature", null),
                Row("Dew point temperature", "dewPointTemperature", null),
                Row("Bubble point pressure", "bubblePointPressure", null),
                Row("Dew point pressure", "dewPointPressure", null),
            ),
        ),
        Section("Vapour phase", Phases.VAPOUR, phaseRows("Vapour", Phases.VAPOUR, vapour = true)),
        Section("Liquid phase", Phases.LIQUID1, phaseRows("Liquid", Phases.LIQUID1, vapour = false)),
        Section("Aqueous phase", Phases.LIQUID2, phaseRows("Aqueous", Phases.LIQUID2, vapour = false)),
    )

    /** Sections to show for [streamIds]: Overall always, a phase block when any of the streams has that phase. */
    fun sections(view: ResultView, streamIds: List<String>): List<Section> =
        SECTIONS.filter { it.phase == Phases.OVERALL || streamIds.any { id -> it.phase in view.presentPhases(id) } }

    fun value(view: ResultView, streamId: String, row: Row): DisplayValue =
        if (row.phase == null) view.value(streamId, row.key) else view.phaseValue(streamId, row.phase, row.key)

    /** The display unit: the user's unit for the quantity; a standard gas volume (Sm³/h, or MMSCFD in field units) for std gas flow. */
    fun unit(row: Row, units: UnitSet): EngUnit =
        if (row.standardGas) {
            if (units.unitFor(Quantity.Temperature) == Units.F || units.unitFor(Quantity.Temperature) == Units.R) Units.mmscfd else Units.sm3PerH
        } else units.unitFor(ResultKeys.quantity(row.key))
}
