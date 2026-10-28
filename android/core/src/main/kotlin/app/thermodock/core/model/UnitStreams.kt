/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.core.model

/** A material stream connected to a unit, as a column of the unit's worksheet: "IN · FEED", "OUT · GAS". */
data class UnitStream(val header: String, val stream: SimObject)

object UnitStreams {
    /** The unit's connected material streams, inlets first, in port order (as the HYSYS worksheet tab). */
    fun of(fs: Flowsheet, unitId: String): List<UnitStream> {
        val unit = fs.objects.firstOrNull { it.id == unitId } ?: return emptyList()
        val ports = Ports.of(unit.type).filter { it.kind == PortKind.Material }
        return ports.sortedBy { if (it.side == PortSide.Inlet) 0 else 1 }.mapNotNull { p ->
            fs.streamAt(unitId, p)?.takeIf { it.type == ObjectType.MaterialStream }?.let { s ->
                UnitStream((if (p.side == PortSide.Inlet) "IN" else "OUT") + " · " + s.tag, s)
            }
        }
    }
}
