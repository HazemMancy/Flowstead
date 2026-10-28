/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import kotlin.math.abs

/** Symbol sizes on the flowsheet, in world units (dp at zoom 1), shared by the canvas, the PFD and [app.flowstead.core.help.AutoLayout]. */
object Symbols {
    const val UNIT = 56.0
    const val STREAM_W = 48.0
    const val STREAM_H = 24.0

    /** Width and height of each type's symbol box; ports sit at fractions of it. */
    fun size(type: ObjectType): Pair<Double, Double> {
        val u = UNIT
        return when (type) {
            ObjectType.MaterialStream, ObjectType.EnergyStream -> STREAM_W to STREAM_H
            ObjectType.Separator, ObjectType.SolidsSeparator -> u * 0.7 to u * 1.2
            ObjectType.ThreePhaseSeparator -> u * 1.4 to u * 0.8
            ObjectType.HeatExchanger -> u * 1.3 to u * 0.8
            ObjectType.AirCooler -> u * 1.3 to u
            ObjectType.Compressor, ObjectType.Expander -> u * 1.1 to u
            ObjectType.Valve -> u to u * 0.85
            ObjectType.PipeSegment -> u * 1.5 to u * 0.5
            ObjectType.OrificePlate -> u to u * 0.6
            ObjectType.ReliefValve -> u * 0.85 to u
            ObjectType.Mixer, ObjectType.Splitter -> u * 0.85 to u
            ObjectType.Tank -> u * 0.95 to u * 1.1
            ObjectType.ShortcutColumn, ObjectType.DistillationColumn, ObjectType.Absorber,
            ObjectType.RefluxedAbsorber, ObjectType.ReboiledAbsorber -> u * 1.1 to u * 2
            ObjectType.ConversionReactor, ObjectType.EquilibriumReactor, ObjectType.GibbsReactor -> u * 0.9 to u * 1.15
            ObjectType.Cstr -> u to u * 1.15
            ObjectType.Pfr -> u * 1.6 to u * 0.6
            else -> u to u
        }
    }

    /** Where [port] sits on a symbol of [type] centred at ([x], [y]). */
    fun portPosition(type: ObjectType, port: Port, x: Double, y: Double): Pair<Double, Double> {
        val (w, h) = size(type)
        return x + port.dx * w to y + port.dy * h
    }
}

/**
 * The side of its symbol a port faces, as a unit vector (x right, y down): a line leaves an outlet, and meets an
 * inlet, square to that side. Ports on the left or right edge face sideways, ports on the top or bottom edge face
 * up or down, and ports inside the outline face the side they are nearer.
 */
val Port.facing: Pair<Int, Int>
    get() = when {
        abs(dx) >= 0.5f -> (if (dx > 0) 1 else -1) to 0
        abs(dy) >= 0.5f -> 0 to (if (dy > 0) 1 else -1)
        else -> (if (dx >= 0) 1 else -1) to 0
    }
