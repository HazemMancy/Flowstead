/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FlowsheetEditingTest {
    private val base = Flowsheet(propertyPackage = "Peng-Robinson (PR)", compounds = listOf("Methane", "Ethane"))

    private fun port(type: ObjectType, side: PortSide, index: Int = 0) = Ports.find(type, side, index)!!

    @Test
    fun tagsFollowHysysConventions() {
        var fs = base
        val tags = listOf(ObjectType.MaterialStream, ObjectType.MaterialStream, ObjectType.Separator, ObjectType.Separator,
            ObjectType.Valve, ObjectType.EnergyStream, ObjectType.Recycle, ObjectType.Heater, ObjectType.Cooler).map {
            val (next, o) = FlowsheetEditing.add(fs, it, 0.0, 0.0)
            fs = next
            o.tag
        }
        assertEquals(listOf("1", "2", "V-100", "V-101", "VLV-100", "Q-100", "RCY-1", "E-100", "E-101"), tags)
    }

    @Test
    fun aPsvOnTheFlowsheetIsItsSafetyReliefValve() {
        val (withVessel, vessel) = FlowsheetEditing.add(base, ObjectType.Separator, 0.0, 0.0)
        val (fs, psv) = FlowsheetEditing.add(withVessel, ObjectType.ReliefValve, 48.0, 0.0)
        assertEquals("PSV-100", psv.tag)
        val device = fs.safety!!.devices.single()
        assertEquals(psv.id to "PSV-100", device.id to device.tag)
        // Needs what it protects and its set pressure, but never stops a solve or changes the model hash.
        assertIs<SpecStatus.UnderSpecified>(Specification.status(fs, psv))
        assertTrue(Specification.problems(fs).none { it.first == psv.id })
        assertEquals(withVessel.modelHash(), fs.modelHash())

        var edited = FlowsheetEditing.upsertReliefDevice(fs, device.copy(protectedId = vessel.id, setPressure = 10e5))
        assertEquals(SpecStatus.Complete, Specification.status(edited, edited.obj(psv.id)!!))
        edited = FlowsheetEditing.rename(edited, psv.id, "PSV-7")
        assertEquals("PSV-7", edited.safety!!.devices.single().tag)
        edited = FlowsheetEditing.upsertReliefDevice(edited, edited.safety!!.devices.single().copy(tag = "PSV-8"))
        assertEquals("PSV-8", edited.obj(psv.id)!!.tag)
        assertTrue(FlowsheetEditing.delete(edited, psv.id).safety!!.devices.isEmpty())
        assertTrue(FlowsheetEditing.deleteReliefDevice(edited, psv.id).objects.none { it.id == psv.id })
    }

    @Test
    fun positionsSnapToGrid() {
        val (fs, o) = FlowsheetEditing.add(base, ObjectType.Valve, 37.0, 50.0)
        assertEquals(48.0 to 48.0, o.x to o.y)
        val moved = FlowsheetEditing.move(fs, o.id, -13.0, 11.9).obj(o.id)!!
        assertEquals(-24.0 to 0.0, moved.x to moved.y)
    }

    @Test
    fun connectsStreamThroughValveInTheRightDirection() {
        var fs = base
        val (a, feed) = FlowsheetEditing.add(fs, ObjectType.MaterialStream, 0.0, 0.0); fs = a
        val (b, valve) = FlowsheetEditing.add(fs, ObjectType.Valve, 100.0, 0.0); fs = b
        fs = FlowsheetEditing.connect(fs, feed.id, valve.id, port(ObjectType.Valve, PortSide.Inlet))
        val (c, product) = FlowsheetEditing.connectNewStream(fs, valve.id, port(ObjectType.Valve, PortSide.Outlet), 200.0, 0.0); fs = c
        assertEquals(
            listOf(Connection(feed.id, 0, valve.id, 0), Connection(valve.id, 0, product.id, 0)),
            fs.connections,
        )
        assertTrue(fs.isFeed(feed))
        assertTrue(!fs.isFeed(product))
        assertEquals(product, fs.streamAt(valve.id, port(ObjectType.Valve, PortSide.Outlet)))
    }

    @Test
    fun portsCanBeWiredWithoutDragging() {
        var fs = base
        val (a, feed) = FlowsheetEditing.add(fs, ObjectType.MaterialStream, 0.0, 0.0); fs = a
        val (b, q) = FlowsheetEditing.add(fs, ObjectType.EnergyStream, 0.0, 0.0); fs = b
        val (c, valve) = FlowsheetEditing.add(fs, ObjectType.Valve, 240.0, 0.0); fs = c
        val (d, sep) = FlowsheetEditing.add(fs, ObjectType.Separator, 480.0, 0.0); fs = d
        val inlet = port(ObjectType.Valve, PortSide.Inlet)
        val outlet = port(ObjectType.Valve, PortSide.Outlet)

        // Only free material streams are offered; energy streams never go on a material port.
        assertEquals(listOf(feed), FlowsheetEditing.attachableStreams(fs, valve.id, inlet))
        fs = FlowsheetEditing.connect(fs, feed.id, valve.id, inlet)
        assertEquals(emptyList(), FlowsheetEditing.attachableStreams(fs, valve.id, outlet)) // feed is already on this valve
        assertEquals(listOf(feed).map { it.id }, FlowsheetEditing.attachableStreams(fs, sep.id, port(ObjectType.Separator, PortSide.Outlet)).map { it.id })
        assertTrue(FlowsheetEditing.attachableStreams(fs, sep.id, port(ObjectType.Separator, PortSide.Inlet)).isEmpty()) // feed is consumed
        assertEquals(listOf(q), FlowsheetEditing.attachableStreams(fs, valve.id, port(ObjectType.Heater, PortSide.Inlet, 1)))

        assertEquals(360.0 to 0.0, FlowsheetEditing.newStreamPosition(valve, outlet))
        assertEquals(120.0 to 0.0, FlowsheetEditing.newStreamPosition(valve, inlet))

        fs = FlowsheetEditing.disconnectPort(fs, valve.id, inlet)
        assertTrue(fs.connections.isEmpty())
        assertTrue(fs.objects.any { it.id == feed.id }) // the stream stays
    }

    @Test
    fun refusesInvalidConnections() {
        var fs = base
        val (a, s1) = FlowsheetEditing.add(fs, ObjectType.MaterialStream, 0.0, 0.0); fs = a
        val (b, s2) = FlowsheetEditing.add(fs, ObjectType.MaterialStream, 0.0, 0.0); fs = b
        val (c, q) = FlowsheetEditing.add(fs, ObjectType.EnergyStream, 0.0, 0.0); fs = c
        val (d, heater) = FlowsheetEditing.add(fs, ObjectType.Heater, 0.0, 0.0); fs = d
        val inlet = port(ObjectType.Heater, PortSide.Inlet, 0)
        val duty = port(ObjectType.Heater, PortSide.Inlet, 1)

        assertEquals("Duty on E-100 needs an energy stream",
            assertFailsWith<EditRejected> { ThermoDockAssert.connect(fs, s1.id, heater.id, duty) }.message)
        assertEquals("Inlet on E-100 needs a material stream",
            assertFailsWith<EditRejected> { ThermoDockAssert.connect(fs, q.id, heater.id, inlet) }.message)

        fs = FlowsheetEditing.connect(fs, s1.id, heater.id, inlet)
        assertEquals("Inlet on E-100 is already connected",
            assertFailsWith<EditRejected> { ThermoDockAssert.connect(fs, s2.id, heater.id, inlet) }.message)
        assertFailsWith<EditRejected> { FlowsheetEditing.connect(fs, heater.id, s1.id, inlet) }
        // Same connection twice is a no-op, not an error.
        assertEquals(fs, FlowsheetEditing.connect(fs, s1.id, heater.id, inlet))
        fs = FlowsheetEditing.connect(fs, q.id, heater.id, duty)
        assertEquals(2, fs.connections.size)
    }

    @Test
    fun aStreamHasOneSourceAndOneDestination() {
        var fs = base
        val (a, s) = FlowsheetEditing.add(fs, ObjectType.MaterialStream, 0.0, 0.0); fs = a
        val (b, v1) = FlowsheetEditing.add(fs, ObjectType.Valve, 0.0, 0.0); fs = b
        val (c, v2) = FlowsheetEditing.add(fs, ObjectType.Valve, 0.0, 0.0); fs = c
        fs = FlowsheetEditing.connect(fs, s.id, v1.id, port(ObjectType.Valve, PortSide.Inlet))
        assertEquals("1 already flows into another unit",
            assertFailsWith<EditRejected> { FlowsheetEditing.connect(fs, s.id, v2.id, port(ObjectType.Valve, PortSide.Inlet)) }.message)
        fs = FlowsheetEditing.connect(fs, s.id, v2.id, port(ObjectType.Valve, PortSide.Outlet))
        assertEquals("1 already comes from another unit",
            assertFailsWith<EditRejected> { FlowsheetEditing.connect(fs, s.id, v1.id, port(ObjectType.Valve, PortSide.Outlet)) }.message)
    }

    @Test
    fun connectingAFeedAsAProductClearsItsSpecs() {
        var fs = base
        val (a, s) = FlowsheetEditing.add(fs, ObjectType.MaterialStream, 0.0, 0.0); fs = a
        val (b, v) = FlowsheetEditing.add(fs, ObjectType.Valve, 0.0, 0.0); fs = b
        fs = FlowsheetEditing.setSpec(fs, s.id, SpecKey.TEMPERATURE, Spec(300.0, "K"))
        fs = FlowsheetEditing.setComposition(fs, s.id, mapOf("Methane" to 1.0))
        fs = FlowsheetEditing.connect(fs, s.id, v.id, port(ObjectType.Valve, PortSide.Outlet))
        assertTrue(fs.obj(s.id)!!.specs.isEmpty())
        assertTrue(fs.obj(s.id)!!.composition.isEmpty())
    }

    @Test
    fun portToPortCreatesAStreamBetweenUnits() {
        var fs = base
        val (a, v1) = FlowsheetEditing.add(fs, ObjectType.Valve, 0.0, 0.0); fs = a
        val (b, sep) = FlowsheetEditing.add(fs, ObjectType.Separator, 200.0, 0.0); fs = b
        fs = FlowsheetEditing.connectUnits(fs, v1.id, port(ObjectType.Valve, PortSide.Outlet), sep.id,
            port(ObjectType.Separator, PortSide.Inlet), 100.0, 0.0)
        val stream = fs.objects.single { it.type == ObjectType.MaterialStream }
        assertEquals(listOf(Connection(v1.id, 0, stream.id, 0), Connection(stream.id, 0, sep.id, 0)), fs.connections)
        assertFailsWith<EditRejected> {
            FlowsheetEditing.connectUnits(fs, v1.id, port(ObjectType.Valve, PortSide.Inlet), sep.id,
                port(ObjectType.Separator, PortSide.Inlet, 1), 0.0, 0.0)
        }
    }

    @Test
    fun freePortLookup() {
        var fs = base
        val (a, sep) = FlowsheetEditing.add(fs, ObjectType.Separator, 0.0, 0.0); fs = a
        val (b, s1) = FlowsheetEditing.add(fs, ObjectType.MaterialStream, 0.0, 0.0); fs = b
        val vapour = port(ObjectType.Separator, PortSide.Outlet, 0)
        assertEquals(vapour, FlowsheetEditing.firstFreePortFor(fs, sep.id, s1, PortSide.Outlet))
        fs = FlowsheetEditing.connect(fs, s1.id, sep.id, vapour)
        assertEquals(port(ObjectType.Separator, PortSide.Outlet, 1), FlowsheetEditing.firstFreePortFor(fs, sep.id, s1, PortSide.Outlet))
        assertEquals(port(ObjectType.Separator, PortSide.Inlet, 0),
            FlowsheetEditing.firstFreePort(fs, sep.id, port(ObjectType.Valve, PortSide.Outlet)))
    }

    @Test
    fun deleteRemovesConnections() {
        var fs = base
        val (a, s) = FlowsheetEditing.add(fs, ObjectType.MaterialStream, 0.0, 0.0); fs = a
        val (b, v) = FlowsheetEditing.add(fs, ObjectType.Valve, 0.0, 0.0); fs = b
        fs = FlowsheetEditing.connect(fs, s.id, v.id, port(ObjectType.Valve, PortSide.Inlet))
        fs = FlowsheetEditing.delete(fs, v.id)
        assertEquals(listOf(s.id), fs.objects.map { it.id })
        assertTrue(fs.connections.isEmpty())
    }

    @Test
    fun renameKeepsTagsUnique() {
        var fs = base
        val (a, s1) = FlowsheetEditing.add(fs, ObjectType.MaterialStream, 0.0, 0.0); fs = a
        val (b, _) = FlowsheetEditing.add(fs, ObjectType.MaterialStream, 0.0, 0.0); fs = b
        assertEquals("FEED", FlowsheetEditing.rename(fs, s1.id, " FEED ").obj(s1.id)!!.tag)
        assertEquals("Tag 2 is already used", assertFailsWith<EditRejected> { FlowsheetEditing.rename(fs, s1.id, "2") }.message)
        assertFailsWith<EditRejected> { FlowsheetEditing.rename(fs, s1.id, "  ") }
    }

    @Test
    fun exclusiveSpecReplacesTheAlternative() {
        var fs = base
        val (a, v) = FlowsheetEditing.add(fs, ObjectType.Valve, 0.0, 0.0); fs = a
        fs = FlowsheetEditing.setSpec(fs, v.id, SpecKey.PRESSURE_DROP, Spec(1e5, "dbar"))
        fs = FlowsheetEditing.setExclusiveSpec(fs, v.id, SpecKey.OUTLET_PRESSURE, Spec(2e6, "bara"),
            listOf(SpecKey.OUTLET_PRESSURE, SpecKey.PRESSURE_DROP))
        assertEquals(setOf(SpecKey.OUTLET_PRESSURE), fs.obj(v.id)!!.specs.keys)
    }

    @Test
    fun compositionEditing() {
        var fs = base
        val (a, s) = FlowsheetEditing.add(fs, ObjectType.MaterialStream, 0.0, 0.0); fs = a
        assertEquals(mapOf("Methane" to 0.8, "Ethane" to 0.2),
            FlowsheetEditing.normalise(mapOf("Methane" to 80.0, "Ethane" to 20.0)))
        assertFailsWith<EditRejected> { FlowsheetEditing.normalise(mapOf("Methane" to 0.0)) }
        assertFailsWith<EditRejected> { FlowsheetEditing.setComposition(fs, s.id, mapOf("Propane" to 1.0)) }
        fs = FlowsheetEditing.setComposition(fs, s.id, mapOf("Methane" to 1.0, "Ethane" to 0.0))
        assertEquals(mapOf("Methane" to 1.0), fs.obj(s.id)!!.composition)
        // Removing a compound drops it from every composition.
        fs = FlowsheetEditing.setCompounds(fs, listOf("Ethane"))
        assertTrue(fs.obj(s.id)!!.composition.isEmpty())
    }

    @Test
    fun specificationStatusForFeedsAndUnitOps() {
        var fs = base
        val (a, s) = FlowsheetEditing.add(fs, ObjectType.MaterialStream, 0.0, 0.0); fs = a
        val needs = assertIs<SpecStatus.UnderSpecified>(Specification.status(fs, fs.obj(s.id)!!)).needs
        assertEquals(listOf("two of temperature, pressure, vapour fraction", "a molar or mass flow", "composition"), needs)

        fs = FlowsheetEditing.setSpec(fs, s.id, SpecKey.TEMPERATURE, Spec(300.0, "C"))
        fs = FlowsheetEditing.setSpec(fs, s.id, SpecKey.PRESSURE, Spec(5e6, "bara"))
        fs = FlowsheetEditing.setSpec(fs, s.id, SpecKey.MOLAR_FLOW, Spec(10.0, "kgmol/h"))
        fs = FlowsheetEditing.setComposition(fs, s.id, mapOf("Methane" to 0.9, "Ethane" to 0.1))
        assertEquals(SpecStatus.Complete, Specification.status(fs, fs.obj(s.id)!!))

        val over = FlowsheetEditing.setSpec(fs, s.id, SpecKey.VAPOUR_FRACTION, Spec(1.0, "frac"))
        assertIs<SpecStatus.OverSpecified>(Specification.status(over, over.obj(s.id)!!))
        val badPair = FlowsheetEditing.setSpec(FlowsheetEditing.setSpec(fs, s.id, SpecKey.PRESSURE, null), s.id,
            SpecKey.MASS_ENTHALPY, Spec(1e5, "kJ/kg"))
        assertIs<SpecStatus.Invalid>(Specification.status(badPair, badPair.obj(s.id)!!))

        val (b, v) = FlowsheetEditing.add(fs, ObjectType.Valve, 0.0, 0.0); fs = b
        assertEquals(listOf("inlet stream", "outlet stream", "outlet pressure or pressure drop"),
            assertIs<SpecStatus.UnderSpecified>(Specification.status(fs, fs.obj(v.id)!!)).needs)

        val (c, sep) = FlowsheetEditing.add(fs, ObjectType.Separator, 0.0, 0.0); fs = c
        // Optional second feed is not demanded.
        assertEquals(listOf("feed stream", "vapour stream", "liquid stream"),
            assertIs<SpecStatus.UnderSpecified>(Specification.status(fs, fs.obj(sep.id)!!)).needs)

        val problems = Specification.problems(fs)
        assertTrue(problems.none { it.first == s.id })
        assertTrue(problems.any { it.first == v.id })
    }
}

/** Thin alias so failure messages read naturally in the assertions above. */
private object ThermoDockAssert {
    fun connect(fs: Flowsheet, stream: String, unit: String, port: Port) = FlowsheetEditing.connect(fs, stream, unit, port)
}

class RemainingUnitOpsSpecificationTest {
    private fun status(type: ObjectType, specs: Map<String, Spec>): SpecStatus {
        var fs = Flowsheet(propertyPackage = "Peng-Robinson (PR)", compounds = listOf("Methane"))
        val (a, o) = FlowsheetEditing.add(fs, type, 0.0, 0.0); fs = a
        // Connect every required port so only the specs decide.
        for (p in Ports.of(type).filter { it.required }) {
            fs = FlowsheetEditing.connectNewStream(fs, o.id, p, 0.0, 0.0).first
        }
        fs = fs.copy(objects = fs.objects.map { if (it.id == o.id) it.copy(specs = specs) else it })
        return Specification.status(fs, fs.obj(o.id)!!)
    }

    private fun s(si: Double, unit: String = "x") = Spec(si, unit)

    @Test
    fun rotatingEquipment() {
        assertEquals(listOf("outlet pressure or pressure rise"), assertIs<SpecStatus.UnderSpecified>(status(ObjectType.Pump, emptyMap())).needs)
        assertEquals(SpecStatus.Complete, status(ObjectType.Pump, mapOf(SpecKey.PRESSURE_RISE to s(5e5))))
        assertEquals(SpecStatus.Complete, status(ObjectType.Compressor, mapOf(SpecKey.PRESSURE_RATIO to s(3.0), SpecKey.EFFICIENCY to s(0.75))))
        assertIs<SpecStatus.OverSpecified>(status(ObjectType.Compressor, mapOf(SpecKey.PRESSURE_RATIO to s(3.0), SpecKey.OUTLET_PRESSURE to s(1e6))))
        assertIs<SpecStatus.Invalid>(status(ObjectType.Compressor, mapOf(SpecKey.PRESSURE_RATIO to s(3.0), SpecKey.EFFICIENCY to s(1.2))))
        assertEquals(SpecStatus.Complete, status(ObjectType.Expander, mapOf(SpecKey.PRESSURE_DROP to s(2e6))))
    }

    @Test
    fun heatExchangerSplitterMixerRecycle() {
        assertEquals(SpecStatus.Complete, status(ObjectType.HeatExchanger, mapOf(SpecKey.COLD_OUTLET_TEMPERATURE to s(393.15))))
        assertIs<SpecStatus.OverSpecified>(status(ObjectType.HeatExchanger, mapOf(SpecKey.DUTY to s(1e5), SpecKey.HOT_OUTLET_TEMPERATURE to s(350.0))))
        assertEquals(listOf("fraction to outlet 1"), assertIs<SpecStatus.UnderSpecified>(status(ObjectType.Splitter, emptyMap())).needs)
        assertEquals(SpecStatus.Complete, status(ObjectType.Splitter, mapOf(SpecKey.SPLIT_1 to s(0.3), SpecKey.SPLIT_2 to s(0.7))))
        assertIs<SpecStatus.Invalid>(status(ObjectType.Splitter, mapOf(SpecKey.SPLIT_1 to s(0.6), SpecKey.SPLIT_2 to s(0.6))))
        assertEquals(SpecStatus.Complete, status(ObjectType.Mixer, emptyMap()))
        assertEquals(SpecStatus.Complete, status(ObjectType.Recycle, emptyMap()))
    }

    @Test
    fun adjustNeedsReferencesAndAStartingValue() {
        var fs = Flowsheet(propertyPackage = "Steam Tables (IAPWS-IF97)", compounds = listOf("Water"))
        val (a, h) = FlowsheetEditing.add(fs, ObjectType.Heater, 0.0, 0.0); fs = a
        val (b, adj) = FlowsheetEditing.add(fs, ObjectType.Adjust, 0.0, 0.0); fs = b
        assertEquals("ADJ-1", adj.tag)
        assertEquals(listOf("variable to vary", "result to control", "target value"),
            assertIs<SpecStatus.UnderSpecified>(Specification.status(fs, fs.obj(adj.id)!!)).needs)
        fs = FlowsheetEditing.setRef(fs, adj.id, AdjustKeys.MANIPULATED_OBJECT, h.id)
        fs = FlowsheetEditing.setRef(fs, adj.id, AdjustKeys.MANIPULATED_SPEC, SpecKey.DUTY)
        fs = FlowsheetEditing.setRef(fs, adj.id, AdjustKeys.CONTROLLED_OBJECT, h.id)
        fs = FlowsheetEditing.setRef(fs, adj.id, AdjustKeys.CONTROLLED_PROPERTY, "outletTemperature")
        fs = FlowsheetEditing.setSpec(fs, adj.id, AdjustKeys.TARGET, Spec(350.0, "K"))
        assertEquals("E-100 has no duty specified to start from",
            assertIs<SpecStatus.Invalid>(Specification.status(fs, fs.obj(adj.id)!!)).problem)
        fs = FlowsheetEditing.setSpec(fs, h.id, SpecKey.DUTY, Spec(1e5, "kW"))
        assertEquals(SpecStatus.Complete, Specification.status(fs, fs.obj(adj.id)!!))
        val (c, second) = FlowsheetEditing.add(fs, ObjectType.Adjust, 0.0, 0.0); fs = c
        assertTrue(Specification.problems(fs).any { it.first == second.id && "only one Adjust" in it.second })
    }

    @Test
    fun energyPortsAreOptional() {
        assertTrue(Ports.of(ObjectType.Compressor).filter { it.kind == PortKind.Energy }.none { it.required })
    }
}

