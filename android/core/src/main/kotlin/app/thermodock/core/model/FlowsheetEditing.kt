/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.core.model

import app.thermodock.core.api.DepressuringResult
import app.thermodock.core.api.ReliefResult

import java.util.UUID

/** Why an edit was refused, in words the user sees. */
class EditRejected(message: String) : IllegalArgumentException(message)

/**
 * Pure editing operations on a [Flowsheet]. Every function returns a new flowsheet or throws
 * [EditRejected]; nothing here touches Android, so the rules are unit-tested in :core.
 *
 * Connection rules (they mirror DWSIM): a connection always joins a stream and a unit
 * operation; a unit-op port carries one stream; a stream has at most one source and one
 * destination; port kind (material/energy) must match the stream kind.
 */
object FlowsheetEditing {
    const val GRID = 24.0

    fun snap(v: Double): Double = Math.round(v / GRID) * GRID

    fun nextTag(fs: Flowsheet, type: ObjectType): String {
        val tags = fs.objects.map { it.tag }.toSet()
        val prefix = type.tagPrefix()
        return if (prefix == null) {
            generateSequence(1) { it + 1 }.map { it.toString() }.first { it !in tags }
        } else {
            val start = if (type == ObjectType.Recycle || type == ObjectType.Adjust || type == ObjectType.Set) 1 else 100
            generateSequence(start) { it + 1 }.map { "$prefix-$it" }.first { it !in tags }
        }
    }

    fun add(fs: Flowsheet, type: ObjectType, x: Double, y: Double, id: String = newId()): Pair<Flowsheet, SimObject> {
        val obj = SimObject(id = id, tag = nextTag(fs, type), type = type, x = snap(x), y = snap(y))
        val added = fs.copy(objects = fs.objects + obj)
        // A PSV drawn on the flowsheet is a Safety analysis relief valve with the same id and tag.
        return (if (type == ObjectType.ReliefValve) upsertReliefDevice(added, ReliefDeviceDef(id = id, tag = obj.tag)) else added) to obj
    }

    fun move(fs: Flowsheet, id: String, x: Double, y: Double): Flowsheet =
        update(fs, id) { it.copy(x = snap(x), y = snap(y)) }

    fun rename(fs: Flowsheet, id: String, tag: String): Flowsheet {
        val clean = tag.trim()
        if (clean.isEmpty()) throw EditRejected("A tag can't be empty")
        if (clean.length > 24) throw EditRejected("Keep tags to 24 characters")
        if (fs.objects.any { it.id != id && it.tag.equals(clean, ignoreCase = true) }) {
            throw EditRejected("Tag $clean is already used")
        }
        val renamed = update(fs, id) { it.copy(tag = clean) }
        val device = renamed.safety?.devices?.firstOrNull { it.id == id } ?: return renamed
        return upsertReliefDevice(renamed, device.copy(tag = clean))
    }

    fun delete(fs: Flowsheet, id: String): Flowsheet {
        val removed = fs.copy(
            objects = fs.objects.filterNot { it.id == id },
            connections = fs.connections.filterNot { it.fromObject == id || it.toObject == id },
            tables = fs.tables.filterNot { it.objectId == id },
        )
        // A PSV's relief valve goes with it.
        return if (fs.obj(id)?.type == ObjectType.ReliefValve) deleteReliefDevice(removed, id) else removed
    }

    /** Shows (or updates) [id]'s property table with [keys]; no keys removes it. Keeps its position. */
    fun setTable(fs: Flowsheet, id: String, keys: List<String>): Flowsheet {
        val o = fs.obj(id) ?: throw EditRejected("That object no longer exists")
        val allowed = PropertyTables.available(fs, o)
        val chosen = keys.distinct().filter { it in allowed }
        val others = fs.tables.filterNot { it.objectId == id }
        if (chosen.isEmpty()) return fs.copy(tables = others)
        val table = fs.tables.firstOrNull { it.objectId == id }?.copy(keys = chosen) ?: PropertyTable(id, chosen)
        return fs.copy(tables = others + table)
    }

    /** Moves [id]'s table to an offset from the object, in world units. */
    fun moveTable(fs: Flowsheet, id: String, dx: Double, dy: Double): Flowsheet =
        fs.copy(tables = fs.tables.map { if (it.objectId == id) it.copy(dx = dx, dy = dy) else it })

    /** Removes every connection on one unit-op port, or all connections of a stream end. */
    fun disconnect(fs: Flowsheet, connection: Connection): Flowsheet = fs.copy(connections = fs.connections - connection)

    fun setSpec(fs: Flowsheet, id: String, key: String, spec: Spec?): Flowsheet = update(fs, id) {
        if (spec == null) it.copy(specs = it.specs - key) else {
            require(spec.si.isFinite()) { "Not a number" }
            it.copy(specs = it.specs + (key to spec))
        }
    }

    /** Sets one spec and drops the ones it replaces, e.g. choosing outlet pressure clears ΔP. */
    fun setExclusiveSpec(fs: Flowsheet, id: String, key: String, spec: Spec, clears: Collection<String>): Flowsheet =
        update(fs, id) { o -> o.copy(specs = o.specs - clears.filter { it != key }.toSet() + (key to spec)) }

    fun setRef(fs: Flowsheet, id: String, key: String, value: String?): Flowsheet = update(fs, id) {
        if (value == null) it.copy(refs = it.refs - key) else it.copy(refs = it.refs + (key to value))
    }

    /** Adds [r], or replaces the reaction with its id. */
    /**
     * A pipe's size from ASME B36.10M/B36.19M: sets the inside and outside diameters and remembers the
     * NPS and schedule. Null [nps] switches to a custom inside diameter (kept) without an outside diameter.
     */
    fun setPipeSize(fs: Flowsheet, id: String, nps: String?, schedule: String?): Flowsheet = update(fs, id) { o ->
        val size = nps?.let { PipeSizes.size(it) }
        val sch = size?.let { s -> schedule?.let { s.schedule(it) } ?: s.schedules.firstOrNull { "40" in it.label.split(" / ") } ?: s.schedules.first() }
        // Which inside diameter the size sets: a pipe segment's own, or the line an orifice plate sits in.
        val inside = PipeSizes.insideDiameterKey(o.type)
        val outside = SpecKey.OUTER_DIAMETER.takeIf { o.type == ObjectType.PipeSegment }
        if (size == null || sch == null) {
            o.copy(refs = o.refs - LineSizing.NPS - LineSizing.SCHEDULE, specs = if (outside != null) o.specs - outside else o.specs)
        } else {
            o.copy(
                refs = o.refs + (LineSizing.NPS to size.nps) + (LineSizing.SCHEDULE to sch.label),
                specs = o.specs + (inside to Spec(size.insideDiameterM(sch), "in")) +
                    listOfNotNull(outside?.let { it to Spec(size.outsideDiameterM, "in") }),
            )
        }
    }

    fun setSizingCriteria(fs: Flowsheet, c: SizingCriteria): Flowsheet = fs.copy(sizingCriteria = c)

    fun setDocumentInfo(fs: Flowsheet, info: DocumentInfo): Flowsheet = fs.copy(documentInfo = info.takeIf { it != DocumentInfo() })

    /**
     * Adds or replaces a case study (D51). Changing what it varies or records drops its old results, which no
     * longer describe it; renaming keeps them.
     */
    fun upsertCaseStudy(fs: Flowsheet, cs: CaseStudy): Flowsheet {
        val old = fs.caseStudies.firstOrNull { it.id == cs.id }
        val kept = if (old != null && old.copy(name = cs.name, results = null) == cs.copy(results = null)) cs.copy(results = cs.results ?: old.results) else cs
        return fs.copy(caseStudies = if (old == null) fs.caseStudies + kept else fs.caseStudies.map { if (it.id == cs.id) kept else it })
    }

    /**
     * Adds or replaces a relief valve (D54) as the user edits it. A change to what it is (anything but its tag) drops
     * its last result, which no longer describes it; [setReliefResult] stores a new one.
     */
    fun upsertReliefDevice(fs: Flowsheet, d: ReliefDeviceDef): Flowsheet {
        val study = fs.safety ?: SafetyStudy()
        val old = study.devices.firstOrNull { it.id == d.id }
        fun def(x: ReliefDeviceDef) = x.copy(tag = "", result = null, resultHash = null)
        val kept = if (old != null && def(old) == def(d)) d.copy(result = old.result, resultHash = old.resultHash) else d.copy(result = null, resultHash = null)
        // Its PSV on the flowsheet, if it has one, carries the same tag.
        val objects = fs.objects.map { if (it.id == d.id && it.type == ObjectType.ReliefValve && d.tag.isNotBlank()) it.copy(tag = d.tag) else it }
        return fs.copy(objects = objects, safety = study.copy(devices = if (old == null) study.devices + kept else study.devices.map { if (it.id == d.id) kept else it }))
    }

    fun setReliefResult(fs: Flowsheet, id: String, result: ReliefResult, hash: String): Flowsheet =
        fs.copy(safety = fs.safety?.let { s -> s.copy(devices = s.devices.map { if (it.id == id) it.copy(result = result, resultHash = hash) else it }) })

    /** Removes the relief valve and its PSV on the flowsheet, if it has one. */
    fun deleteReliefDevice(fs: Flowsheet, id: String): Flowsheet =
        fs.copy(
            objects = fs.objects.filterNot { it.id == id && it.type == ObjectType.ReliefValve },
            tables = fs.tables.filterNot { it.objectId == id && fs.obj(id)?.type == ObjectType.ReliefValve },
            safety = fs.safety?.let { it.copy(devices = it.devices.filterNot { d -> d.id == id }) },
        )

    /** As [upsertReliefDevice], for depressuring cases (D57); renaming keeps the result. */
    fun upsertDepressuring(fs: Flowsheet, c: DepressuringCase): Flowsheet {
        val study = fs.safety ?: SafetyStudy()
        val old = study.depressuring.firstOrNull { it.id == c.id }
        fun def(x: DepressuringCase) = x.copy(name = "", result = null, resultHash = null)
        val kept = if (old != null && def(old) == def(c)) c.copy(result = old.result, resultHash = old.resultHash) else c.copy(result = null, resultHash = null)
        return fs.copy(safety = study.copy(depressuring = if (old == null) study.depressuring + kept else study.depressuring.map { if (it.id == c.id) kept else it }))
    }

    fun setDepressuringResult(fs: Flowsheet, id: String, result: DepressuringResult, hash: String): Flowsheet =
        fs.copy(safety = fs.safety?.let { s -> s.copy(depressuring = s.depressuring.map { if (it.id == id) it.copy(result = result, resultHash = hash) else it }) })

    fun deleteDepressuring(fs: Flowsheet, id: String): Flowsheet =
        fs.copy(safety = fs.safety?.let { it.copy(depressuring = it.depressuring.filterNot { c -> c.id == id }) })

    /** As [upsertDepressuring], for pipeline pressurization cases; renaming keeps the result. */
    fun upsertPressurization(fs: Flowsheet, c: PressurizationCase): Flowsheet {
        val study = fs.safety ?: SafetyStudy()
        val old = study.pressurization.firstOrNull { it.id == c.id }
        fun def(x: PressurizationCase) = x.copy(name = "", result = null, resultHash = null)
        val kept = if (old != null && def(old) == def(c)) c.copy(result = old.result, resultHash = old.resultHash) else c.copy(result = null, resultHash = null)
        return fs.copy(safety = study.copy(pressurization = if (old == null) study.pressurization + kept else study.pressurization.map { if (it.id == c.id) kept else it }))
    }

    fun setPressurizationResult(fs: Flowsheet, id: String, result: DepressuringResult, hash: String): Flowsheet =
        fs.copy(safety = fs.safety?.let { s -> s.copy(pressurization = s.pressurization.map { if (it.id == id) it.copy(result = result, resultHash = hash) else it }) })

    fun deletePressurization(fs: Flowsheet, id: String): Flowsheet =
        fs.copy(safety = fs.safety?.let { it.copy(pressurization = it.pressurization.filterNot { c -> c.id == id }) })

    fun deleteCaseStudy(fs: Flowsheet, id: String): Flowsheet = fs.copy(caseStudies = fs.caseStudies.filterNot { it.id == id })

    fun setCaseResults(fs: Flowsheet, id: String, results: CaseResults): Flowsheet =
        fs.copy(caseStudies = fs.caseStudies.map { if (it.id == id) it.copy(results = results) else it })

    /** Sets or clears a pump or compressor curve (D48). Running on a curve replaces the outlet pressure spec. */
    /** Sets or clears a separator's sizing basis (D61); it changes no result. */
    fun setSizing(fs: Flowsheet, id: String, sizing: SeparatorSizingInput?): Flowsheet = update(fs, id) { it.copy(sizing = sizing) }

    /** Sets or clears the sizing basis of an exchanger, heater, cooler, machine or tank (D62). */
    fun setEquipmentSizing(fs: Flowsheet, id: String, sizing: EquipmentSizingInput?): Flowsheet = update(fs, id) { it.copy(equipmentSizing = sizing) }

    fun setCurve(fs: Flowsheet, id: String, curve: PerformanceCurve?): Flowsheet = update(fs, id) {
        if (curve == null) it.copy(curve = null)
        else it.copy(curve = curve, specs = it.specs - listOf(SpecKey.OUTLET_PRESSURE, SpecKey.PRESSURE_RISE, SpecKey.PRESSURE_RATIO).toSet())
    }

    /** One data-sheet field of an object ([SheetKeys]); blank removes it. */
    fun setSheetField(fs: Flowsheet, id: String, key: String, value: String): Flowsheet = update(fs, id) {
        it.copy(sheet = if (value.isBlank()) it.sheet - key else it.sheet + (key to value.trim()))
    }

    /**
     * Adds or replaces a hypothetical (by name) and puts it in the component list. A name already used by another
     * component is refused.
     */
    fun upsertHypothetical(fs: Flowsheet, h: Hypothetical): Flowsheet {
        h.problem()?.let { throw EditRejected(it) }
        val existing = fs.hypothetical(h.name)
        if (existing == null && h.name in fs.compounds) throw EditRejected("A component called ${h.name} is already in the list")
        val hypos = if (existing == null) fs.hypotheticals + h else fs.hypotheticals.map { if (it.name == h.name) h else it }
        return fs.copy(hypotheticals = hypos, compounds = if (h.name in fs.compounds) fs.compounds else fs.compounds + h.name)
    }

    /** Removes a hypothetical and, like any component, its fractions and references. */
    fun deleteHypothetical(fs: Flowsheet, name: String): Flowsheet = setCompounds(fs, fs.compounds - name)

    fun upsertReaction(fs: Flowsheet, r: Reaction): Flowsheet =
        fs.copy(reactions = if (fs.reaction(r.id) == null) fs.reactions + r else fs.reactions.map { if (it.id == r.id) r else it })

    /** Removes a reaction and takes it out of every reactor that ran it. */
    fun deleteReaction(fs: Flowsheet, id: String): Flowsheet = fs.copy(
        reactions = fs.reactions.filter { it.id != id },
        objects = fs.objects.map { o ->
            val ids = Reactors.reactionIds(o)
            if (id !in ids) o
            else (ids - id).let { left -> o.copy(refs = if (left.isEmpty()) o.refs - ReactorKeys.REACTIONS else o.refs + (ReactorKeys.REACTIONS to left.joinToString(","))) }
        },
    )

    fun setComposition(fs: Flowsheet, id: String, fractions: Map<String, Double>): Flowsheet {
        fractions.forEach { (c, x) ->
            if (c !in fs.compounds) throw EditRejected("$c is not in the component list")
            if (!x.isFinite() || x < 0) throw EditRejected("Mole fractions can't be negative")
        }
        return update(fs, id) { it.copy(composition = fractions.filterValues { x -> x > 0.0 }) }
    }

    fun normalise(fractions: Map<String, Double>): Map<String, Double> {
        val sum = fractions.values.filter { it.isFinite() && it > 0 }.sum()
        if (sum <= 0.0) throw EditRejected("Enter at least one non-zero fraction")
        return fractions.mapValues { (_, x) -> if (x.isFinite() && x > 0) x / sum else 0.0 }
    }

    fun setCompounds(fs: Flowsheet, compounds: List<String>): Flowsheet {
        val distinct = compounds.distinct()
        // Compositions keep only compounds that are still present.
        val objects = fs.objects.map { o -> o.copy(composition = o.composition.filterKeys { it in distinct }) }
        // A hypothetical taken out of the list goes with it.
        return fs.copy(
            compounds = distinct, objects = objects, hypotheticals = fs.hypotheticals.filter { it.name in distinct },
            interactionParameters = fs.interactionParameters.filter { it.a in distinct && it.b in distinct },
        )
    }

    /** Sets the user's kij for a pair (D58), or with null goes back to DWSIM's value. */
    fun setInteractionParameter(fs: Flowsheet, a: String, b: String, kij: Double?): Flowsheet {
        if (a == b || a !in fs.compounds || b !in fs.compounds) throw EditRejected("kij needs two different compounds of the fluid")
        if (kij != null && !(kij > -1 && kij < 1)) throw EditRejected("kij must be between −1 and 1")
        val others = fs.interactionParameters.filterNot { (it.a == a && it.b == b) || (it.a == b && it.b == a) }
        return fs.copy(interactionParameters = if (kij == null) others else others + BinaryParameter(a, b, kij))
    }

    /**
     * Connects a stream to a unit-op port. Direction comes from the port: a stream feeding an
     * inlet port is upstream of the unit op; an outlet port feeds the stream.
     */
    fun connect(fs: Flowsheet, streamId: String, unitId: String, port: Port): Flowsheet {
        val stream = fs.obj(streamId) ?: throw EditRejected("That stream no longer exists")
        val unit = fs.obj(unitId) ?: throw EditRejected("That unit operation no longer exists")
        if (!stream.type.isStream) throw EditRejected("Connect a stream to a unit operation")
        if (unit.type.isStream) throw EditRejected("Streams connect to unit operations, not to other streams")
        if (Ports.find(unit.type, port.side, port.index) != port) throw EditRejected("${unit.tag} has no such port")
        val kindOk = (port.kind == PortKind.Material) == (stream.type == ObjectType.MaterialStream)
        if (!kindOk) {
            throw EditRejected(
                if (port.kind == PortKind.Energy) "${port.label} on ${unit.tag} needs an energy stream"
                else "${port.label} on ${unit.tag} needs a material stream",
            )
        }

        val connection = when (port.side) {
            PortSide.Inlet -> Connection(streamId, 0, unitId, port.index)
            PortSide.Outlet -> Connection(unitId, port.index, streamId, 0)
        }
        if (connection in fs.connections) return fs

        val portTaken = fs.connections.any {
            when (port.side) {
                PortSide.Inlet -> it.toObject == unitId && it.toPort == port.index
                PortSide.Outlet -> it.fromObject == unitId && it.fromPort == port.index
            }
        }
        if (portTaken) throw EditRejected("${port.label} on ${unit.tag} is already connected")

        when (port.side) {
            PortSide.Inlet -> if (fs.connections.any { it.fromObject == streamId }) {
                throw EditRejected("${stream.tag} already flows into another unit")
            }
            PortSide.Outlet -> if (fs.connections.any { it.toObject == streamId }) {
                throw EditRejected("${stream.tag} already comes from another unit")
            }
        }
        if (port.side == PortSide.Outlet && stream.hasFeedSpecs()) {
            // A product stream is calculated; its old feed specs would over-specify it.
            return fs.copy(
                objects = fs.objects.map { if (it.id == streamId) it.copy(specs = emptyMap(), composition = emptyMap()) else it },
                connections = fs.connections + connection,
            )
        }
        return fs.copy(connections = fs.connections + connection)
    }

    /**
     * Drag from a unit-op port to empty canvas: create a new stream of the right kind there and
     * connect it. Returns the new stream.
     */
    fun connectNewStream(fs: Flowsheet, unitId: String, port: Port, x: Double, y: Double): Pair<Flowsheet, SimObject> {
        val type = if (port.kind == PortKind.Energy) ObjectType.EnergyStream else ObjectType.MaterialStream
        val (withStream, stream) = add(fs, type, x, y)
        return connect(withStream, stream.id, unitId, port) to stream
    }

    /**
     * Port-to-port drag between two unit operations: an outlet and an inlet of the same kind get
     * a new stream placed halfway between them.
     */
    fun connectUnits(fs: Flowsheet, aId: String, aPort: Port, bId: String, bPort: Port, x: Double, y: Double): Flowsheet {
        if (aId == bId) throw EditRejected("Connect two different units")
        if (aPort.side == bPort.side) {
            throw EditRejected(if (aPort.side == PortSide.Outlet) "Join an outlet to an inlet" else "Join an inlet to an outlet")
        }
        if (aPort.kind != bPort.kind) throw EditRejected("Material and energy ports can't be joined")
        val (withStream, stream) = connectNewStream(fs, aId, aPort, x, y)
        return connect(withStream, stream.id, bId, bPort)
    }

    /** First unconnected port on [unitId] that a wire from [from] could join (opposite side, same kind). */
    /** Removes whatever stream is attached at [port] of [unitId]; the stream itself stays. */
    fun disconnectPort(fs: Flowsheet, unitId: String, port: Port): Flowsheet = fs.copy(
        connections = fs.connections.filterNot {
            when (port.side) {
                PortSide.Inlet -> it.toObject == unitId && it.toPort == port.index
                PortSide.Outlet -> it.fromObject == unitId && it.fromPort == port.index
            }
        },
    )

    /**
     * Existing streams that could go on [port] without a drag: the right kind, and still free on
     * that end (an inlet takes a stream nothing consumes yet, an outlet one nothing produces yet).
     */
    fun attachableStreams(fs: Flowsheet, unitId: String, port: Port): List<SimObject> {
        val type = if (port.kind == PortKind.Energy) ObjectType.EnergyStream else ObjectType.MaterialStream
        return fs.objects.filter { s ->
            s.type == type && fs.connections.none { c -> (c.fromObject == s.id && c.toObject == unitId) || (c.toObject == s.id && c.fromObject == unitId) } &&
                when (port.side) {
                    PortSide.Inlet -> fs.connections.none { it.fromObject == s.id }
                    PortSide.Outlet -> fs.connections.none { it.toObject == s.id }
                }
        }
    }

    /** Where a stream made from a port (not dragged) is placed: beside the port, on its side of the unit. */
    fun newStreamPosition(unit: SimObject, port: Port): Pair<Double, Double> {
        // Nozzles a little off the centreline (valve, pump) keep the stream level with the unit;
        // ports clearly above or below (separator outlets, tee branches) get their own row.
        val dy = when {
            port.dy <= -0.3f -> -72.0
            port.dy >= 0.3f -> 72.0
            else -> 0.0
        }
        return when {
            port.kind == PortKind.Energy -> unit.x to unit.y + 96.0
            port.side == PortSide.Inlet -> unit.x - 120.0 to unit.y + dy
            else -> unit.x + 120.0 to unit.y + dy
        }
    }

    fun firstFreePort(fs: Flowsheet, unitId: String, from: Port): Port? {
        val unit = fs.obj(unitId) ?: return null
        return Ports.of(unit.type).firstOrNull { p ->
            p.side != from.side && p.kind == from.kind && fs.streamAt(unitId, p) == null
        }
    }

    /** First unconnected port on [unitId] that can take [stream], given the side the stream is free on. */
    fun firstFreePortFor(fs: Flowsheet, unitId: String, stream: SimObject, side: PortSide): Port? {
        val unit = fs.obj(unitId) ?: return null
        val kind = if (stream.type == ObjectType.EnergyStream) PortKind.Energy else PortKind.Material
        return Ports.of(unit.type).firstOrNull { p -> p.side == side && p.kind == kind && fs.streamAt(unitId, p) == null }
    }

    fun newId(): String = UUID.randomUUID().toString()

    private fun update(fs: Flowsheet, id: String, f: (SimObject) -> SimObject): Flowsheet {
        if (fs.objects.none { it.id == id }) throw EditRejected("That object no longer exists")
        return fs.copy(objects = fs.objects.map { if (it.id == id) f(it) else it })
    }

    private fun SimObject.hasFeedSpecs() = specs.isNotEmpty() || composition.isNotEmpty()
}

fun Flowsheet.obj(id: String): SimObject? = objects.firstOrNull { it.id == id }

/** The stream entering [port] of unit [unitId], or leaving it for outlet ports. */
fun Flowsheet.streamAt(unitId: String, port: Port): SimObject? {
    val c = connections.firstOrNull {
        when (port.side) {
            PortSide.Inlet -> it.toObject == unitId && it.toPort == port.index
            PortSide.Outlet -> it.fromObject == unitId && it.fromPort == port.index
        }
    } ?: return null
    return obj(if (port.side == PortSide.Inlet) c.fromObject else c.toObject)
}

/** True when nothing flows into this stream: the user must specify it (a feed). */
fun Flowsheet.isFeed(stream: SimObject): Boolean = stream.type.isStream && connections.none { it.toObject == stream.id }
