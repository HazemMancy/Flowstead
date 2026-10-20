/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

enum class PortKind { Material, Energy }

enum class PortSide { Inlet, Outlet }

/**
 * A connection point on a unit operation. [index] is the DWSIM connector index the engine uses.
 * [dx]/[dy] place the port on the icon, as fractions of its size (−0.5…0.5 from the centre).
 */
data class Port(
    val side: PortSide,
    val kind: PortKind,
    val index: Int,
    val label: String,
    val dx: Float,
    val dy: Float,
    val required: Boolean = true,
)

/** Ports per object type. Streams have one implicit inlet and outlet (index 0) and no entry here. */
object Ports {
    private fun mIn(i: Int, label: String, dx: Float = -0.5f, dy: Float = 0f, required: Boolean = true) =
        Port(PortSide.Inlet, PortKind.Material, i, label, dx, dy, required)
    private fun mOut(i: Int, label: String, dx: Float = 0.5f, dy: Float = 0f, required: Boolean = true) =
        Port(PortSide.Outlet, PortKind.Material, i, label, dx, dy, required)
    // Energy ports are optional: the engine adds a hidden energy stream when none is drawn.
    private fun eIn(i: Int, label: String, dx: Float = 0f, dy: Float = 0.5f) =
        Port(PortSide.Inlet, PortKind.Energy, i, label, dx, dy, required = false)
    private fun eOut(i: Int, label: String, dx: Float = 0f, dy: Float = 0.5f) =
        Port(PortSide.Outlet, PortKind.Energy, i, label, dx, dy, required = false)

    fun of(type: ObjectType): List<Port> = when (type) {
        ObjectType.MaterialStream, ObjectType.EnergyStream -> emptyList()
        ObjectType.Valve -> listOf(mIn(0, "Inlet", dy = 0.15f), mOut(0, "Outlet", dy = 0.15f))
        ObjectType.Heater -> listOf(mIn(0, "Inlet", dy = -0.06f), mOut(0, "Outlet", dy = -0.06f), eIn(1, "Duty"))
        ObjectType.Cooler -> listOf(mIn(0, "Inlet", dy = -0.06f), mOut(0, "Outlet", dy = -0.06f), eOut(1, "Duty"))
        ObjectType.Separator -> listOf(
            mIn(0, "Feed"),
            mIn(1, "Feed 2", dy = 0.25f, required = false),
            mOut(0, "Vapour", dx = 0f, dy = -0.5f),
            mOut(1, "Liquid", dx = 0f, dy = 0.5f),
        )
        ObjectType.ThreePhaseSeparator -> listOf(
            mIn(0, "Feed"),
            mIn(1, "Feed 2", dy = 0.25f, required = false),
            mOut(0, "Vapour", dx = 0.25f, dy = -0.5f),
            mOut(1, "Light liquid", dx = 0.5f, dy = 0.1f),
            mOut(2, "Heavy liquid", dx = 0.2f, dy = 0.5f),
        )
        ObjectType.Mixer -> listOf(
            mIn(0, "Inlet 1", dy = -0.2f), mIn(1, "Inlet 2", dy = 0.2f),
            mIn(2, "Inlet 3", dy = 0f, required = false), mOut(0, "Outlet"),
        )
        ObjectType.Splitter -> listOf(
            mIn(0, "Inlet"), mOut(0, "Outlet 1", dy = -0.2f), mOut(1, "Outlet 2", dy = 0.2f),
            mOut(2, "Outlet 3", required = false),
        )
        // DWSIM decides which side is hot from the inlet temperatures.
        ObjectType.HeatExchanger -> listOf(
            mIn(0, "Side A in"), mIn(1, "Side B in", dx = -0.16f, dy = -0.5f),
            mOut(0, "Side A out"), mOut(1, "Side B out", dx = 0.16f, dy = 0.5f),
        )
        ObjectType.Pump -> listOf(mIn(0, "Inlet"), mOut(0, "Outlet", dy = -0.23f), eIn(1, "Power"))
        ObjectType.Compressor -> listOf(mIn(0, "Inlet"), mOut(0, "Outlet"), eIn(1, "Power"))
        ObjectType.Expander -> listOf(mIn(0, "Inlet"), mOut(0, "Outlet"), eOut(1, "Power"))
        ObjectType.Recycle -> listOf(mIn(0, "Calculated"), mOut(0, "Assumed"))
        ObjectType.PipeSegment, ObjectType.OrificePlate -> listOf(mIn(0, "Inlet"), mOut(0, "Outlet"))
        // Ports for the remaining types arrive with their engine support (they are not placeable yet).
        else -> emptyList()
    }

    fun find(type: ObjectType, side: PortSide, index: Int): Port? = of(type).firstOrNull { it.side == side && it.index == index }
}

/** HYSYS-style tag prefixes for new objects: V-100, VLV-100, E-100… Material streams are numbered 1, 2, 3. */
fun ObjectType.tagPrefix(): String? = when (this) {
    ObjectType.MaterialStream -> null
    ObjectType.EnergyStream -> "Q"
    ObjectType.Mixer -> "MIX"
    ObjectType.Splitter -> "TEE"
    ObjectType.Heater -> "E"
    ObjectType.Cooler -> "E"
    ObjectType.HeatExchanger -> "E"
    ObjectType.Valve -> "VLV"
    ObjectType.Pump -> "P"
    ObjectType.Compressor -> "K"
    ObjectType.Expander -> "K"
    ObjectType.Separator, ObjectType.ThreePhaseSeparator -> "V"
    ObjectType.Recycle -> "RCY"
    ObjectType.Adjust -> "ADJ"
    ObjectType.AirCooler -> "AC"
    ObjectType.PipeSegment -> "PIPE"
    ObjectType.OrificePlate -> "FO"
    ObjectType.ReliefValve -> "PSV"
    ObjectType.ComponentSplitter -> "X"
    ObjectType.Tank -> "TK"
    ObjectType.Filter -> "F"
    ObjectType.SolidsSeparator -> "CY"
    ObjectType.ShortcutColumn, ObjectType.DistillationColumn, ObjectType.Absorber,
    ObjectType.RefluxedAbsorber, ObjectType.ReboiledAbsorber -> "T"
    ObjectType.ConversionReactor, ObjectType.EquilibriumReactor, ObjectType.GibbsReactor,
    ObjectType.Cstr, ObjectType.Pfr -> "R"
    ObjectType.Set -> "SET"
    ObjectType.EnergyRecycle -> "ERCY"
}

/** Display name of each type, as shown in the palette and editor titles. */
fun ObjectType.displayName(): String = when (this) {
    ObjectType.MaterialStream -> "Material stream"
    ObjectType.EnergyStream -> "Energy stream"
    ObjectType.Mixer -> "Mixer"
    ObjectType.Splitter -> "Tee (splitter)"
    ObjectType.Heater -> "Heater"
    ObjectType.Cooler -> "Cooler"
    ObjectType.HeatExchanger -> "Heat exchanger"
    ObjectType.Valve -> "Valve"
    ObjectType.Pump -> "Pump"
    ObjectType.Compressor -> "Compressor"
    ObjectType.Expander -> "Expander"
    ObjectType.Separator -> "Separator"
    ObjectType.ThreePhaseSeparator -> "3-phase separator"
    ObjectType.Recycle -> "Recycle"
    ObjectType.Adjust -> "Adjust"
    ObjectType.AirCooler -> "Air cooler"
    ObjectType.PipeSegment -> "Pipe segment"
    ObjectType.OrificePlate -> "Orifice plate"
    ObjectType.ReliefValve -> "Relief valve"
    ObjectType.ComponentSplitter -> "Component splitter"
    ObjectType.Tank -> "Tank"
    ObjectType.Filter -> "Filter"
    ObjectType.SolidsSeparator -> "Cyclone"
    ObjectType.ShortcutColumn -> "Shortcut column"
    ObjectType.DistillationColumn -> "Distillation column"
    ObjectType.Absorber -> "Absorber"
    ObjectType.RefluxedAbsorber -> "Refluxed absorber"
    ObjectType.ReboiledAbsorber -> "Reboiled absorber"
    ObjectType.ConversionReactor -> "Conversion reactor"
    ObjectType.EquilibriumReactor -> "Equilibrium reactor"
    ObjectType.GibbsReactor -> "Gibbs reactor"
    ObjectType.Cstr -> "CSTR"
    ObjectType.Pfr -> "PFR"
    ObjectType.Set -> "Set"
    ObjectType.EnergyRecycle -> "Energy recycle"
}
