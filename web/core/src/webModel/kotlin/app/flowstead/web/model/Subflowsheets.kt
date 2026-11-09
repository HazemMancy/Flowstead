/*
 * Flowstead web (D132): sub-flowsheets, as HYSYS's. A sub-flowsheet is a block on its parent flowsheet; inside it is a
 * flowsheet of its own. Its boundary streams (a stream inside with one open end) are matched to streams of the parent,
 * as HYSYS's internal and external streams. A sub-flowsheet may use its own fluid package: then its boundary streams
 * pass across with a transfer basis (temperature and pressure, or pressure and vapour fraction, with the molar flow and
 * the composition), as HYSYS's.
 *
 * The engine never sees a sub-flowsheet. A case on one fluid package is sent as the one flat flowsheet it is, so every
 * number is what the flat case gives. A case with sub-flowsheets on their own packages is solved region by region, each
 * region one ordinary engine solve on its own package; the browser carries the boundary streams across and repeats
 * until they stop changing (D132). Pure model code on the app's Flowsheet, in commonMain so it is tested on the JVM
 * and in JavaScript. android/ is unchanged.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:OptIn(ExperimentalSerializationApi::class)

package app.flowstead.web.model

import app.flowstead.core.api.ObjectResult
import app.flowstead.core.api.SolveResult
import app.flowstead.core.api.SolveStatus
import app.flowstead.core.api.SolverMessage
import app.flowstead.core.model.BinaryParameter
import app.flowstead.core.model.BlackOil
import app.flowstead.core.model.Connection
import app.flowstead.core.model.EditRejected
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.FlowsheetEditing
import app.flowstead.core.model.Hypothetical
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.SimObject
import app.flowstead.core.model.Spec
import app.flowstead.core.model.SpecKey
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max

/** A sub-flowsheet: a block at [parent] (null: the main flowsheet) holding the objects whose parent is [id]. */
@Serializable
data class Subflowsheet(
    val id: String,
    val tag: String,
    val x: Double = 0.0,
    val y: Double = 0.0,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val parent: String? = null,
    /** Its own fluid package; null: the parent's (then it is solved with its parent as one flowsheet). */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val fluid: SubFluid? = null,
    /** Its boundary streams matched to streams of the parent. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val ports: List<SubPort> = emptyList(),
)

/** A sub-flowsheet's own fluid package: the same fields as a flowsheet's. */
@Serializable
data class SubFluid(
    val propertyPackage: String? = null,
    val compounds: List<String> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val hypotheticals: List<Hypothetical> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val blackOils: List<BlackOil> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val liquidDensity: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val volumeShift: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val vapourViscosity: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val interactionParameters: List<BinaryParameter> = emptyList(),
)

/**
 * [inner], a boundary stream inside, is [outer], a stream of the parent. [basis] matters only when the sub-flowsheet
 * has its own fluid package: [TransferBasis.TP] (default) or [TransferBasis.PVF].
 */
@Serializable
data class SubPort(
    val inner: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val outer: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val basis: String? = null,
)

object TransferBasis {
    /** Temperature and pressure carried across, the stream flashed on the new package there (HYSYS's default). */
    const val TP = "tp"
    /** Pressure and vapour fraction carried across: a saturated stream stays saturated on the new package. */
    const val PVF = "pvf"
    fun label(b: String?) = if (b == PVF) "P–VF (pressure and vapour fraction)" else "T–P (temperature and pressure)"
}

/** A boundary stream of a sub-flowsheet: [feed] true when it enters the sub-flowsheet. */
data class Boundary(val stream: SimObject, val feed: Boolean, val port: SubPort?)

/** One fluid package's part of the case: [id] null for the main flowsheet's package, else the sub-flowsheet's id. */
data class Region(val id: String?, val tag: String, val flowsheet: Flowsheet)

/** A boundary stream carried from one region's result into another region's stream, as a feed there. */
@Serializable
data class Transfer(
    val sourceRegion: String?,
    val sourceStream: String,
    val targetRegion: String?,
    val targetStream: String,
    val basis: String = TransferBasis.TP,
)

object Subflowsheets {
    fun of(fs: Flowsheet, id: String?): Subflowsheet? = id?.let { b -> fs.subflowsheets.firstOrNull { it.id == b } }
    fun has(fs: Flowsheet) = fs.subflowsheets.isNotEmpty()

    /** The blocks whose parent is [level] (null: the main flowsheet). */
    fun blocksAt(fs: Flowsheet, level: String?) = fs.subflowsheets.filter { it.parent == level }
    fun objectsAt(fs: Flowsheet, level: String?) = fs.objects.filter { it.parent == level }

    /** The path from the main flowsheet down to [level], outermost first. */
    fun path(fs: Flowsheet, level: String?): List<Subflowsheet> {
        val out = ArrayList<Subflowsheet>()
        var l = level
        while (l != null) { val b = of(fs, l) ?: break; out.add(0, b); l = b.parent }
        return out
    }

    /** The region whose fluid package objects at [level] use: the nearest sub-flowsheet with its own, or null. */
    fun regionOf(fs: Flowsheet, level: String?): String? {
        var l = level
        while (l != null) { val b = of(fs, l) ?: return null; if (b.fluid != null) return b.id; l = b.parent }
        return null
    }

    /** Whether any sub-flowsheet has its own fluid package (the case then solves region by region). */
    fun multiPackage(fs: Flowsheet) = fs.subflowsheets.any { it.fluid != null }

    private fun isStream(o: SimObject) = o.type == ObjectType.MaterialStream || o.type == ObjectType.EnergyStream
    private fun hasSource(fs: Flowsheet, id: String) = fs.connections.any { it.toObject == id }
    private fun hasDestination(fs: Flowsheet, id: String) = fs.connections.any { it.fromObject == id }

    /** The streams inside [blockId] with one open end: its feeds and products, as HYSYS's boundary streams. */
    fun boundary(fs: Flowsheet, blockId: String): List<Boundary> {
        val b = of(fs, blockId) ?: return emptyList()
        return objectsAt(fs, blockId).filter(::isStream).mapNotNull { s ->
            val src = hasSource(fs, s.id); val dst = hasDestination(fs, s.id)
            val port = b.ports.firstOrNull { it.inner == s.id }
            when {
                !src && dst -> Boundary(s, true, port)
                src && !dst -> Boundary(s, false, port)
                else -> null
            }
        }
    }

    /** Streams of the block's parent that can be matched to [innerId]: same kind, open at the right end, not matched yet. */
    fun outerCandidates(fs: Flowsheet, blockId: String, innerId: String): List<SimObject> {
        val b = of(fs, blockId) ?: return emptyList()
        val bd = boundary(fs, blockId).firstOrNull { it.stream.id == innerId } ?: return emptyList()
        val taken = fs.subflowsheets.flatMap { s -> s.ports.mapNotNull { it.outer } }.toSet() - setOfNotNull(bd.port?.outer)
        return objectsAt(fs, b.parent).filter { o ->
            o.type == bd.stream.type && o.id !in taken &&
                if (bd.feed) !hasDestination(fs, o.id) else !hasSource(fs, o.id)
        }
    }

    // ---------- editing ----------

    fun add(fs: Flowsheet, level: String?, x: Double, y: Double): Pair<Flowsheet, Subflowsheet> {
        val b = Subflowsheet(FlowsheetEditing.newId(), nextTag(fs), x, y, parent = level)
        return fs.copy(subflowsheets = fs.subflowsheets + b) to b
    }

    fun nextTag(fs: Flowsheet): String {
        val used = (fs.subflowsheets.map { it.tag } + fs.objects.map { it.tag }).map { it.lowercase() }.toSet()
        return generateSequence(1) { it + 1 }.map { "SUB-${it}" }.first { it.lowercase() !in used }
    }

    private fun update(fs: Flowsheet, id: String, change: (Subflowsheet) -> Subflowsheet): Flowsheet {
        if (of(fs, id) == null) throw EditRejected("That sub-flowsheet no longer exists")
        return fs.copy(subflowsheets = fs.subflowsheets.map { if (it.id == id) change(it) else it })
    }

    fun rename(fs: Flowsheet, id: String, tag: String): Flowsheet {
        val t = tag.trim()
        if (t.isEmpty()) throw EditRejected("Give the sub-flowsheet a name")
        val others = (fs.subflowsheets.filter { it.id != id }.map { it.tag } + fs.objects.map { it.tag }).map { it.lowercase() }
        if (t.lowercase() in others) throw EditRejected("$t is already used")
        return update(fs, id) { it.copy(tag = t) }
    }

    fun move(fs: Flowsheet, id: String, x: Double, y: Double) = update(fs, id) { it.copy(x = x, y = y) }

    /** Puts [objectId] (just added) on [level]. */
    fun place(fs: Flowsheet, objectId: String, level: String?): Flowsheet =
        if (level == null) fs else fs.copy(objects = fs.objects.map { if (it.id == objectId) it.copy(parent = level) else it })

    /** Matches boundary stream [innerId] of [blockId] to [outerId] of the parent (null: unmatched). */
    fun link(fs: Flowsheet, blockId: String, innerId: String, outerId: String?): Flowsheet {
        if (outerId != null && outerCandidates(fs, blockId, innerId).none { it.id == outerId })
            throw EditRejected("That stream can't be matched here: it must be an open ${if (boundary(fs, blockId).firstOrNull { it.stream.id == innerId }?.feed == true) "product" else "feed"} of the parent flowsheet, not matched yet")
        return update(fs, blockId) { b ->
            val old = b.ports.firstOrNull { it.inner == innerId }
            b.copy(ports = b.ports.filter { it.inner != innerId } + listOfNotNull(outerId?.let { SubPort(innerId, it, old?.basis) }))
        }
    }

    fun setBasis(fs: Flowsheet, blockId: String, innerId: String, basis: String?) = update(fs, blockId) { b ->
        b.copy(ports = b.ports.map { if (it.inner == innerId) it.copy(basis = basis?.takeIf { v -> v == TransferBasis.PVF }) else it })
    }

    /** Gives the sub-flowsheet its own fluid package (a copy of what it uses now), or (null) the parent's again. */
    fun setFluid(fs: Flowsheet, blockId: String, fluid: SubFluid?) = update(fs, blockId) { it.copy(fluid = fluid) }

    /** The fluid package objects at [level] use, as a [SubFluid]. */
    fun fluidAt(fs: Flowsheet, level: String?): SubFluid =
        of(fs, regionOf(fs, level))?.fluid ?: SubFluid(fs.propertyPackage, fs.compounds, fs.hypotheticals, fs.blackOils, fs.liquidDensity, fs.volumeShift, fs.vapourViscosity, fs.interactionParameters)

    /** [fs] with its own fluid fields replaced by [fluid]: the flowsheet a basis editor works on. */
    fun withFluid(fs: Flowsheet, fluid: SubFluid) = fs.copy(
        propertyPackage = fluid.propertyPackage, compounds = fluid.compounds, hypotheticals = fluid.hypotheticals, blackOils = fluid.blackOils,
        liquidDensity = fluid.liquidDensity, volumeShift = fluid.volumeShift, vapourViscosity = fluid.vapourViscosity, interactionParameters = fluid.interactionParameters,
    )

    fun fluidOf(fs: Flowsheet) = SubFluid(fs.propertyPackage, fs.compounds, fs.hypotheticals, fs.blackOils, fs.liquidDensity, fs.volumeShift, fs.vapourViscosity, fs.interactionParameters)

    /** Every sub-flowsheet inside [id], [id] included. */
    fun descendants(fs: Flowsheet, id: String): Set<String> {
        val out = linkedSetOf(id)
        var grew = true
        while (grew) { grew = false; fs.subflowsheets.forEach { if (it.parent in out && out.add(it.id)) grew = true } }
        return out
    }

    /** Deletes a sub-flowsheet with everything inside it. */
    fun delete(fs: Flowsheet, id: String): Flowsheet {
        val blocks = descendants(fs, id)
        val inside = fs.objects.filter { it.parent in blocks }.map { it.id }
        val f = inside.fold(fs) { acc, o -> if (acc.objects.any { it.id == o }) FlowsheetEditing.delete(acc, o) else acc }
        return clean(f.copy(subflowsheets = f.subflowsheets.filter { it.id !in blocks }))
    }

    /** Drops matches to streams that no longer exist or are no longer open at the boundary. */
    fun clean(fs: Flowsheet): Flowsheet {
        if (fs.subflowsheets.isEmpty()) return fs
        val ids = fs.objects.map { it.id }.toSet()
        val next = fs.subflowsheets.map { b ->
            val open = boundary(fs, b.id).map { it.stream.id }.toSet()
            b.copy(ports = b.ports.filter { it.inner in open && (it.outer == null || it.outer in ids) })
        }
        return if (next == fs.subflowsheets) fs else fs.copy(subflowsheets = next)
    }

    /**
     * Turns [ids] (objects on one flowsheet) into a new sub-flowsheet there, as HYSYS's: a stream that joined a unit
     * inside to one outside stays outside and gets a boundary stream inside, matched to it, and a stream inside that ran
     * to a unit outside gets a matching stream outside. Results are unchanged: the case solves as before.
     */
    fun group(fs: Flowsheet, ids: Collection<String>, x: Double, y: Double): Pair<Flowsheet, Subflowsheet> {
        val chosen = fs.objects.filter { it.id in ids }
        if (chosen.isEmpty()) throw EditRejected("Select the equipment to put in the sub-flowsheet")
        val level = chosen.first().parent
        if (chosen.any { it.parent != level }) throw EditRejected("Select objects on one flowsheet")
        val inSet = chosen.map { it.id }.toSet()
        var (f, block) = add(fs, level, x, y)
        val ports = ArrayList<SubPort>()
        var conns = f.connections
        val newObjects = ArrayList<SimObject>()
        fun tagLike(base: String): String {
            val used = (f.objects + newObjects).map { it.tag.lowercase() }.toSet() + f.subflowsheets.map { it.tag.lowercase() }
            return generateSequence(1) { it + 1 }.map { if (it == 1) "$base-${block.tag}" else "$base-${block.tag}-$it" }.first { it.lowercase() !in used }
        }
        for (c in f.connections) {
            val fromIn = c.fromObject in inSet; val toIn = c.toObject in inSet
            if (fromIn == toIn) continue
            val inside = f.objects.first { it.id == if (fromIn) c.fromObject else c.toObject }
            val outside = f.objects.first { it.id == if (fromIn) c.toObject else c.fromObject }
            val mid = (inside.x + outside.x) / 2 to (inside.y + outside.y) / 2
            if (isStream(outside)) {
                // A stream outside joins a unit inside: the stream stays, a boundary stream inside takes its place.
                val twin = outside.copy(id = FlowsheetEditing.newId(), tag = tagLike(outside.tag), parent = block.id, x = mid.first, y = mid.second,
                    specs = emptyMap(), composition = emptyMap(), sheet = emptyMap(), refs = emptyMap())
                newObjects += twin
                conns = conns.map { if (it == c) (if (fromIn) it.copy(toObject = twin.id) else it.copy(fromObject = twin.id)) else it }
                ports += SubPort(twin.id, outside.id)
            } else if (isStream(inside)) {
                // A stream inside runs to a unit outside: a matching stream outside takes the outside end.
                if (f.connections.count { (it.fromObject == inside.id || it.toObject == inside.id) && (it.fromObject !in inSet || it.toObject !in inSet) } > 1)
                    throw EditRejected("${inside.tag} joins two units outside the selection: leave it out of the sub-flowsheet")
                val twin = inside.copy(id = FlowsheetEditing.newId(), tag = tagLike(inside.tag), parent = level, x = mid.first, y = mid.second,
                    specs = emptyMap(), composition = emptyMap(), sheet = emptyMap(), refs = emptyMap())
                newObjects += twin
                conns = conns.map { if (it == c) (if (fromIn) it.copy(fromObject = twin.id) else it.copy(toObject = twin.id)) else it }
                ports += SubPort(inside.id, twin.id)
            } else throw EditRejected("${inside.tag} and ${outside.tag} are joined directly")
        }
        val after = f.copy(connections = conns)
        // A matched pair whose outside stream is a feed of the parent is set there: typed specs move outside.
        val specsOut = ports.filter { p -> !hasSource(after, p.outer!!) && f.objects.any { it.id == p.inner && it.specs.isNotEmpty() } }
            .associate { p -> p.outer!! to f.objects.first { it.id == p.inner } }
        f = after.copy(
            objects = f.objects.map { o ->
                when {
                    o.id in inSet && specsOut.values.any { it.id == o.id } -> o.copy(parent = block.id, specs = emptyMap(), composition = emptyMap())
                    o.id in inSet -> o.copy(parent = block.id)
                    else -> o
                }
            } + newObjects.map { n -> specsOut[n.id]?.let { src -> n.copy(specs = src.specs, composition = src.composition) } ?: n },
        )
        block = block.copy(ports = ports)
        f = f.copy(subflowsheets = f.subflowsheets.map { if (it.id == block.id) block else it })
        return clean(f) to block
    }

    /** Puts a sub-flowsheet's contents back on its parent, joining each matched pair into one stream. */
    fun ungroup(fs: Flowsheet, id: String): Flowsheet {
        val b = of(fs, id) ?: throw EditRejected("That sub-flowsheet no longer exists")
        if (b.fluid != null) throw EditRejected("${b.tag} has its own fluid package: give it the parent's first")
        val merged = mergePorts(fs, b.ports)
        return merged.copy(
            objects = merged.objects.map { if (it.parent == id) it.copy(parent = b.parent) else it },
            subflowsheets = merged.subflowsheets.filter { it.id != id }.map { if (it.parent == id) it.copy(parent = b.parent) else it },
        )
    }

    // ---------- solving ----------

    /** Joins each matched pair of [ports] into the outer stream: connections and references to the inner one move to it. */
    private fun mergePorts(fs: Flowsheet, ports: List<SubPort>): Flowsheet {
        val map = ports.mapNotNull { p -> p.outer?.let { p.inner to it } }.toMap()
        if (map.isEmpty()) return fs
        fun r(id: String) = map[id] ?: id
        return fs.copy(
            objects = fs.objects.filter { it.id !in map }.map { o -> if (o.refs.values.any { it in map }) o.copy(refs = o.refs.mapValues { r(it.value) }) else o },
            connections = fs.connections.map { it.copy(fromObject = r(it.fromObject), toObject = r(it.toObject)) },
            tables = fs.tables.filter { it.objectId !in map },
        )
    }

    /**
     * The case as one flat flowsheet, every matched pair joined: what the engine solves when every sub-flowsheet uses
     * the main package, and what readiness is checked on.
     */
    fun flatten(fs: Flowsheet): Flowsheet {
        if (!has(fs)) return fs
        val m = mergePorts(fs, fs.subflowsheets.flatMap { it.ports })
        return m.copy(objects = m.objects.map { if (it.parent != null) it.copy(parent = null) else it }, subflowsheets = emptyList())
    }

    /** Inner streams joined into their outer stream by [flatten]: inner id → outer id (their results are the same). */
    fun joined(fs: Flowsheet): Map<String, String> =
        fs.subflowsheets.filter { it.fluid == null || !multiPackage(fs) }.flatMap { b -> b.ports.mapNotNull { p -> p.outer?.let { p.inner to it } } }.toMap()

    /** A flat solve's result with each joined inner stream given its outer stream's result. */
    fun expand(fs: Flowsheet, result: SolveResult): SolveResult {
        val add = joined(fs).mapNotNull { (inner, outer) -> result.objects[outer]?.let { inner to it } }.toMap()
        return if (add.isEmpty()) result else result.copy(objects = result.objects + add)
    }

    /** The regions of a case with own-package sub-flowsheets, and the boundary streams carried between them. */
    fun regions(fs: Flowsheet): Pair<List<Region>, List<Transfer>> {
        val own = fs.subflowsheets.filter { it.fluid != null }
        val regionIds = listOf<String?>(null) + own.map { it.id }
        // Pairs inside one region are joined; pairs across a package boundary are carried.
        val crossing = own.flatMap { b -> b.ports.filter { it.outer != null }.map { b to it } }
        val joinedPorts = fs.subflowsheets.filter { it.fluid == null }.flatMap { it.ports }
        val merged = mergePorts(fs, joinedPorts)
        val transfers = crossing.map { (b, p) ->
            val inner = fs.objects.first { it.id == p.inner }
            if (inner.type == ObjectType.EnergyStream)
                throw EditRejected("${inner.tag}: an energy stream can't cross into ${b.tag}, which has its own fluid package; put the unit it serves on one side")
            val feed = boundary(fs, b.id).firstOrNull { it.stream.id == p.inner }?.feed ?: true
            val outerRegion = regionOf(fs, b.parent)
            if (feed) Transfer(outerRegion, p.outer!!, b.id, p.inner, p.basis ?: TransferBasis.TP)
            else Transfer(b.id, p.inner, outerRegion, p.outer!!, p.basis ?: TransferBasis.TP)
        }
        val regions = regionIds.map { rid ->
            val objs = merged.objects.filter { regionOf(fs, it.parent) == rid }
            val ids = objs.map { it.id }.toSet()
            val fluid = of(fs, rid)?.fluid
            val base = if (fluid != null) withFluid(merged, fluid) else merged
            Region(rid, of(fs, rid)?.tag ?: "Main", base.copy(
                objects = objs.map { it.copy(parent = null) },
                connections = merged.connections.filter { it.fromObject in ids && it.toObject in ids },
                subflowsheets = emptyList(),
                tables = merged.tables.filter { it.objectId in ids },
            ))
        }.filter { it.flowsheet.objects.isNotEmpty() }
        val inner = regions.map { it.id }.toSet()
        return regions to transfers.filter { it.sourceRegion in inner && it.targetRegion in inner }
    }

    /** Order to solve regions in: each after the regions it takes streams from, where the streams allow (a loop is iterated). */
    fun order(regions: List<Region>, transfers: List<Transfer>): List<String?> {
        val left = regions.map { it.id }.toMutableList()
        val done = ArrayList<String?>()
        while (left.isNotEmpty()) {
            val next = left.firstOrNull { r -> transfers.none { it.targetRegion == r && it.sourceRegion != r && it.sourceRegion in left } } ?: left.first()
            done += next; left.remove(next)
        }
        return done
    }

    /** The specs a transfer puts on its target stream, from the source stream's result (SI). Null: the source has no result. */
    fun transferSpecs(t: Transfer, source: ObjectResult?, sourceTag: String, target: Flowsheet): Pair<Map<String, Spec>, Map<String, Double>>? {
        val p = source?.properties ?: return null
        val pressure = p[SpecKey.PRESSURE] ?: return null
        val flow = p[SpecKey.MOLAR_FLOW] ?: return null
        val second = if (t.basis == TransferBasis.PVF) SpecKey.VAPOUR_FRACTION to (p[SpecKey.VAPOUR_FRACTION] ?: return null)
        else SpecKey.TEMPERATURE to (p[SpecKey.TEMPERATURE] ?: return null)
        val x = source.phases?.get("overall")?.composition ?: return null
        val missing = x.filter { (c, v) -> v > TRACE && c !in target.compounds }
        if (missing.isNotEmpty())
            throw EditRejected("$sourceTag carries ${missing.keys.joinToString()}, which the fluid package on the other side doesn't have: add ${if (missing.size == 1) "it" else "them"} there")
        val kept = x.filter { (c, v) -> v > 0 && c in target.compounds }
        val total = kept.values.sum()
        val specs = mapOf(
            SpecKey.PRESSURE to Spec(pressure, "Pa"),
            second.first to Spec(second.second, if (second.first == SpecKey.TEMPERATURE) "K" else "frac"),
            SpecKey.MOLAR_FLOW to Spec(flow, "mol/s"),
        )
        return specs to kept.mapValues { it.value / total }
    }

    /** Below this mole fraction a compound the other side lacks is taken as absent (D132). */
    const val TRACE = 1e-12

    /** [region]'s flowsheet with transfer target [streamId] set to [specs] and [composition]. */
    fun applyTransfer(region: Flowsheet, streamId: String, specs: Map<String, Spec>, composition: Map<String, Double>): Flowsheet =
        region.copy(objects = region.objects.map { if (it.id == streamId) it.copy(specs = specs, composition = composition) else it })

    /** Largest relative change between two sets of transferred values (temperature and pressure relative, vapour fraction absolute). */
    fun change(a: Pair<Map<String, Spec>, Map<String, Double>>?, b: Pair<Map<String, Spec>, Map<String, Double>>): Double {
        if (a == null) return Double.POSITIVE_INFINITY
        var d = 0.0
        for ((k, s) in b.first) {
            val o = a.first[k]?.si ?: return Double.POSITIVE_INFINITY
            d = max(d, if (k == SpecKey.VAPOUR_FRACTION) abs(o - s.si) else abs(o - s.si) / max(abs(s.si), 1e-12))
        }
        for ((c, v) in b.second) d = max(d, abs((a.second[c] ?: 0.0) - v))
        return d
    }

    /** The regions' results as one: every object's, the worst status, the messages labelled by region. */
    fun combine(fs: Flowsheet, parts: List<Pair<Region, SolveResult>>, sweeps: Int): SolveResult {
        val worst = parts.map { it.second.status }.maxByOrNull { listOf(SolveStatus.Converged, SolveStatus.Timeout, SolveStatus.Invalid, SolveStatus.Failed).indexOf(it) } ?: SolveStatus.Converged
        val first = parts.firstOrNull()?.second
        return SolveResult(
            status = worst,
            elapsedMs = parts.sumOf { it.second.elapsedMs },
            iterations = sweeps,
            engineVersion = first?.engineVersion ?: "",
            dwsimVersion = first?.dwsimVersion ?: "",
            messages = parts.flatMap { (r, s) -> s.messages.map { m -> if (r.id == null) m else SolverMessage(m.level, "${r.tag}: ${m.text}", m.objectId) } },
            objects = parts.fold(emptyMap()) { acc, (_, s) -> acc + s.objects },
        ).let { expand(fs, it) }
    }
}
