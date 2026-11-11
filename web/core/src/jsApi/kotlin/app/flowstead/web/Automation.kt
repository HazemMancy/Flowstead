/*
 * Flowstead web (D140): the automation interface's model side. Everything an agent (or a script) needs to build, read,
 * change and size a case through the page's own code, as JSON in and out: what Flowstead offers (types, ports,
 * specifications and their units, results, tools), objects found by tag or id, specifications typed in any unit,
 * results with their units and whether they are current, and the app's sizing (separators, equipment, lines).
 * The page side (web/src/automation.js) commits each edit as the user's own (undoable) and calls the engine.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:OptIn(ExperimentalJsExport::class)

package app.flowstead.web

import app.flowstead.core.api.SolveResult
import app.flowstead.core.format.ValueFormat
import app.flowstead.core.model.Connection
import app.flowstead.core.model.EditRejected
import app.flowstead.core.model.EquipmentSizing
import app.flowstead.core.model.EquipmentSizingInput
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.FlowsheetEditing
import app.flowstead.core.model.FlowsheetJson
import app.flowstead.core.model.LineCriteria
import app.flowstead.core.model.LineService
import app.flowstead.core.model.LineSizing
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.PortKind
import app.flowstead.core.model.PortSide
import app.flowstead.core.model.Ports
import app.flowstead.core.model.ResultKeys
import app.flowstead.core.model.ResultView
import app.flowstead.core.model.SeparatorSizing
import app.flowstead.core.model.SeparatorSizingInput
import app.flowstead.core.model.SimObject
import app.flowstead.core.model.SizingCriteria
import app.flowstead.core.model.Spec
import app.flowstead.core.model.SpecKey
import app.flowstead.core.model.Specification
import app.flowstead.core.model.SpecStatus
import app.flowstead.core.model.StoredSolve
import app.flowstead.core.model.Subflowsheets
import app.flowstead.core.model.Readiness
import app.flowstead.core.model.displayName
import app.flowstead.core.model.modelHash
import app.flowstead.core.model.obj
import app.flowstead.core.units.Quantity
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.Units
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

@JsExport
object Automation {
    /** The interface's version: a call's name, inputs or outputs change only with it. */
    const val VERSION = "1.0"

    private fun fs(json: String): Flowsheet = FlowsheetJson.decodeFromString(Flowsheet.serializer(), json)
    private fun out(f: Flowsheet, extra: (kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = {}) =
        buildJsonObject { put("flowsheet", FlowsheetJson.encodeToJsonElement(Flowsheet.serializer(), f)); extra() }.toString()
    private fun err(e: Throwable) = buildJsonObject { put("error", e.message ?: "Not allowed") }.toString()
    private inline fun edit(block: () -> String): String = try { block() } catch (e: Exception) { err(e) }
    private fun view(f: Flowsheet, solveJson: String?, solvedHash: String): ResultView {
        val s = solveJson?.takeIf { it.isNotBlank() && it != "null" }?.let { StoredSolve(FlowsheetJson.decodeFromString(SolveResult.serializer(), it), solvedHash, 0L) }
        return ResultView(s, f.modelHash())
    }
    private fun typeId(t: ObjectType): String = ObjectType.serializer().descriptor.getElementName(t.ordinal)
    private fun type(id: String): ObjectType = ObjectType.entries.firstOrNull { typeId(it) == id || it.name.equals(id, true) }
        ?: throw EditRejected("Unknown object type '$id': see describe().types")
    private fun quantityId(q: Quantity) = q.name

    /** The object [ref] names: its id, or its tag (any case). */
    private fun find(f: Flowsheet, ref: String): SimObject =
        f.obj(ref) ?: f.objects.firstOrNull { it.tag.equals(ref.trim(), ignoreCase = true) } ?: throw EditRejected("No object '$ref'")

    /** The sub-flowsheet [ref] names: its id or tag. */
    private fun block(f: Flowsheet, ref: String) = Subflowsheets.of(f, ref) ?: f.subflowsheets.firstOrNull { it.tag.equals(ref.trim(), true) }
        ?: throw EditRejected("No sub-flowsheet '$ref'")

    /** A unit of [q] by its id ("dbar") or its symbol ("bar", "°C", "kg/h"), within that quantity so neither is ambiguous. */
    private fun unitOf(q: Quantity, text: String): app.flowstead.core.units.EngUnit {
        val t = text.trim()
        return Units.byIdOrNull(t)?.takeIf { it.quantity == q } ?: Units.forQuantity(q).firstOrNull { it.symbol == t || it.id.equals(t, true) || it.symbol.equals(t, true) }
            ?: throw EditRejected("'$text' is not a unit of ${q.name}: one of ${Units.forQuantity(q).joinToString(", ") { it.symbol.ifEmpty { it.id } }}")
    }

    private fun unitsOf(q: Quantity) = buildJsonArray { Units.forQuantity(q).forEach { add(JsonPrimitive(it.id)) } }

    // ---------- what Flowstead offers ----------

    /**
     * Every object type (id as in project files, label, category, whether it is a stream, its ports and the
     * specifications it takes, each with its quantity), the result keys it gives, every quantity's units (ids to pass
     * as a unit), and the interface version.
     */
    fun describe(): String = buildJsonObject {
        put("version", VERSION)
        putJsonArray("types") {
            ObjectType.entries.filter { it.available }.forEach { t ->
                addJsonObject {
                    put("id", typeId(t)); put("label", t.displayName()); put("category", t.category.label); put("isStream", t.isStream)
                    putJsonArray("ports") {
                        Ports.of(t).forEach { p -> addJsonObject { put("side", p.side.name.lowercase()); put("kind", p.kind.name.lowercase()); put("index", p.index); put("label", p.label); put("required", p.required) } }
                    }
                    putJsonArray("specs") {
                        SpecKey.forType(t).forEach { k ->
                            val q = runCatching { SpecKey.quantity(k) }.getOrNull() ?: return@forEach
                            addJsonObject { put("key", k); put("label", SpecKey.label(k)); put("quantity", quantityId(q)) }
                        }
                    }
                    putJsonArray("results") {
                        (if (t == ObjectType.MaterialStream) ResultKeys.STREAM else ResultKeys.forType(t)).forEach { k ->
                            addJsonObject { put("key", k); put("label", ResultKeys.label(k)); put("quantity", quantityId(ResultKeys.quantity(k))) }
                        }
                    }
                }
            }
        }
        putJsonObject("units") { Quantity.entries.forEach { q -> put(quantityId(q), unitsOf(q)) } }
        putJsonObject("displayUnits") { Quantity.entries.forEach { q -> put(quantityId(q), displayUnits.unitFor(q).id) } }
    }.toString()

    // ---------- reading ----------

    /**
     * The case as an engineer reads it: the fluid, and every object with its tag, type, flowsheet (null: the main one),
     * specifications (value in their own unit and SI), composition, what joins it (port and stream or unit), whether it
     * is ready to solve and why not; the sub-flowsheets with their streams in and out.
     */
    fun objects(flowsheetJson: String): String {
        val f = fs(flowsheetJson)
        val ctx = UnitContext(f.atmosphericPressurePa)
        val status = Readiness.statusOf(f)
        val notReady = Readiness.of(f).notReady
        return buildJsonObject {
            put("propertyPackage", f.propertyPackage ?: ""); putJsonArray("compounds") { f.compounds.forEach { add(JsonPrimitive(it)) } }
            put("solverActive", f.solverActive)
            putJsonArray("objects") {
                f.objects.forEach { o ->
                    addJsonObject {
                        put("id", o.id); put("tag", o.tag); put("type", typeId(o.type)); o.parent?.let { put("flowsheet", Subflowsheets.of(f, it)?.tag ?: it) }
                        putJsonObject("specs") {
                            o.specs.forEach { (k, sp) ->
                                val q = runCatching { SpecKey.quantity(k) }.getOrNull()
                                val u = Units.byIdOrNull(sp.unit)?.takeIf { it.quantity == q } ?: q?.let { displayUnits.unitFor(it) }
                                putJsonObject(k) { put("si", sp.si); u?.let { put("value", u.fromSi(sp.si, ctx)); put("unit", it.id) } }
                            }
                        }
                        if (o.composition.isNotEmpty()) putJsonObject("composition") { o.composition.forEach { (c, x) -> put(c, x) } }
                        if (o.refs.isNotEmpty()) putJsonObject("refs") { o.refs.forEach { (k, v) -> put(k, v) } }
                        putJsonArray("in") { f.connections.filter { it.toObject == o.id }.forEach { c -> addJsonObject { put("port", c.toPort); put("from", f.obj(c.fromObject)?.tag ?: c.fromObject) } } }
                        putJsonArray("out") { f.connections.filter { it.fromObject == o.id }.forEach { c -> addJsonObject { put("port", c.fromPort); put("to", f.obj(c.toObject)?.tag ?: c.toObject) } } }
                        when (val st = status[o.id]) {
                            is SpecStatus.UnderSpecified -> putJsonArray("needs") { st.needs.forEach { add(JsonPrimitive(it)) } }
                            is SpecStatus.OverSpecified -> put("problem", st.problem)
                            is SpecStatus.Invalid -> put("problem", st.problem)
                            else -> Unit
                        }
                        notReady[o.id]?.let { put("notReady", it) }
                    }
                }
            }
            putJsonArray("subflowsheets") {
                f.subflowsheets.forEach { b ->
                    addJsonObject {
                        put("id", b.id); put("tag", b.tag); b.parent?.let { put("flowsheet", Subflowsheets.of(f, it)?.tag ?: it) }
                        put("propertyPackage", Subflowsheets.fluidAt(f, b.id).propertyPackage ?: ""); put("ownPackage", b.fluid != null)
                        putJsonArray("streams") {
                            Subflowsheets.boundary(f, b.id).forEach { bd ->
                                addJsonObject { put("inside", bd.stream.tag); put("direction", if (bd.feed) "in" else "out"); bd.port?.outer?.let { o -> put("outside", f.obj(o)?.tag ?: o) } }
                            }
                        }
                    }
                }
            }
        }.toString()
    }

    /**
     * One object's results from the last solve: every property in SI and in the display unit, its phases (fraction,
     * properties and composition), the engine's messages for it, its status, and [stale] when the case changed since
     * (the numbers are then the last solve's, not this case's). Nothing is computed here: every number is the engine's.
     */
    fun results(flowsheetJson: String, solveJson: String?, solvedHash: String, ref: String): String = try {
        val f = fs(flowsheetJson)
        val o = find(f, ref)
        val ctx = UnitContext(f.atmosphericPressurePa)
        val rv = view(f, solveJson, solvedHash)
        val raw = solveJson?.takeIf { it.isNotBlank() && it != "null" }?.let { FlowsheetJson.decodeFromString(SolveResult.serializer(), it) }
        val r = raw?.objects?.get(o.id) ?: raw?.objects?.get(Subflowsheets.joinedAll(f)[o.id] ?: "")
        buildJsonObject {
            put("id", o.id); put("tag", o.tag); put("type", typeId(o.type))
            put("solved", raw != null); put("stale", rv.stale)
            if (r == null) { put("status", "none"); return@buildJsonObject }
            put("status", r.status.name.lowercase()); r.error?.let { put("error", it) }
            putJsonObject("properties") {
                r.properties.forEach { (k, si) ->
                    val q = ResultKeys.quantityFor(o, k)
                    val u = displayUnits.unitFor(q)
                    putJsonObject(k) { put("label", ResultKeys.label(k)); put("si", si); put("siUnit", Units.si(q).id); put("value", u.fromSi(si, ctx)); put("unit", u.id) }
                }
            }
            r.phases?.let { ph ->
                putJsonObject("phases") {
                    ph.forEach { (name, p) ->
                        putJsonObject(name) {
                            putJsonObject("properties") { p.properties.forEach { (k, v) -> put(k, v) } }
                            putJsonObject("composition") { p.composition.forEach { (c, x) -> put(c, x) } }
                        }
                    }
                }
            }
            r.texts?.let { t -> putJsonObject("texts") { t.forEach { (k, v) -> put(k, v) } } }
            r.profiles?.let { p -> putJsonArray("profiles") { p.keys.forEach { add(JsonPrimitive(it)) } } }
            putJsonArray("messages") { rv.messages(o.id).forEach { m -> addJsonObject { put("level", m.level.name.lowercase()); put("text", m.text) } } }
        }.toString()
    } catch (e: Exception) { err(e) }

    // ---------- writing (each returns {flowsheet} for the page to commit, or {error} with the app's reason) ----------

    /** Adds an object of [typeId] at ([x], [y]), tagged [tag] when given: {flowsheet, id, tag}. */
    fun add(flowsheetJson: String, typeId: String, x: Double, y: Double, tag: String): String = edit {
        var (f, o) = FlowsheetEditing.add(fs(flowsheetJson), type(typeId), x, y)
        if (tag.isNotBlank()) f = FlowsheetEditing.rename(f, o.id, tag.trim())
        out(f) { put("id", o.id); put("tag", f.obj(o.id)!!.tag) }
    }

    /**
     * Sets [key] of [ref] to [value] in unit [unit] (a unit id: describe().units; empty: SI). A specification that
     * excludes others (outlet pressure and pressure rise, say) replaces them, as the worksheet does.
     */
    fun setSpec(flowsheetJson: String, ref: String, key: String, value: Double, unit: String): String = edit {
        val f = fs(flowsheetJson)
        val o = find(f, ref)
        val q = runCatching { SpecKey.quantity(key) }.getOrNull() ?: throw EditRejected("Unknown specification '$key': see describe().types[].specs")
        if (key !in SpecKey.forType(o.type) && !key.startsWith("split:") && !key.startsWith("feedStage")) throw EditRejected("${o.tag} takes no '$key'")
        val u = if (unit.isBlank()) Units.si(q) else unitOf(q, unit)
        if (!value.isFinite()) throw EditRejected("Not a number")
        val clears = Specification.exclusiveGroups(o.type).firstOrNull { key in it.second }?.second?.filter { it != key }.orEmpty()
        out(FlowsheetEditing.setExclusiveSpec(f, o.id, key, Spec(u.toSi(value, UnitContext(f.atmosphericPressurePa)), u.id), clears))
    }

    fun clearSpec(flowsheetJson: String, ref: String, key: String): String = edit {
        val f = fs(flowsheetJson)
        out(FlowsheetEditing.setSpec(f, find(f, ref).id, key, null))
    }

    /** A feed's composition as mole fractions ({"Methane": 0.8, ...}); normalised when they add up to within 1 %. */
    fun setComposition(flowsheetJson: String, ref: String, fractionsJson: String): String = edit {
        val f = fs(flowsheetJson)
        val o = find(f, ref)
        val x = FlowsheetJson.parseToJsonElement(fractionsJson).jsonObject.mapValues { (c, v) ->
            if (c !in f.compounds && c !in Subflowsheets.fluidAt(f, o.parent).compounds) throw EditRejected("$c is not a component of the fluid: add it first")
            v.jsonPrimitive.doubleOrNull ?: throw EditRejected("Not a number for $c")
        }
        val sum = x.values.sum()
        if (!(sum > 0)) throw EditRejected("The fractions add up to nothing")
        if (kotlin.math.abs(sum - 1) > 0.01) throw EditRejected("The fractions add up to ${ValueFormat.number(sum, 4)}, not 1")
        out(FlowsheetEditing.setComposition(f, o.id, FlowsheetEditing.normalise(x)))
    }

    /** A choice an object keeps as text (a column's condenser, a reactor's mode, a line's service…): empty clears it. */
    fun setRef(flowsheetJson: String, ref: String, key: String, value: String): String = edit {
        val f = fs(flowsheetJson)
        out(FlowsheetEditing.setRef(f, find(f, ref).id, key, value.ifEmpty { null }))
    }

    /** Joins stream [streamRef] to [unitRef]'s [side] ("inlet" or "outlet") port [index] of [kind] ("material" or "energy"). */
    fun connect(flowsheetJson: String, streamRef: String, unitRef: String, side: String, index: Int, kind: String): String = edit {
        val f = fs(flowsheetJson)
        val s = find(f, streamRef); val u = find(f, unitRef)
        val p = Ports.of(u.type).firstOrNull { it.side.name.equals(side, true) && it.kind.name.equals(kind.ifEmpty { "material" }, true) && it.index == index }
            ?: throw EditRejected("${u.tag} has no $kind ${side.lowercase()} port $index: see describe().types[].ports")
        out(FlowsheetEditing.connect(f, s.id, u.id, p))
    }

    /** A new stream on [unitRef]'s port, tagged [tag] when given: {flowsheet, id, tag}. */
    fun connectNew(flowsheetJson: String, unitRef: String, side: String, index: Int, kind: String, tag: String): String = edit {
        val f0 = fs(flowsheetJson)
        val u = find(f0, unitRef)
        val p = Ports.of(u.type).firstOrNull { it.side.name.equals(side, true) && it.kind.name.equals(kind.ifEmpty { "material" }, true) && it.index == index }
            ?: throw EditRejected("${u.tag} has no $kind ${side.lowercase()} port $index")
        val (x, y) = FlowsheetEditing.newStreamPosition(u, p)
        var (f, s) = FlowsheetEditing.connectNewStream(f0, u.id, p, x, y)
        if (u.parent != null) f = Subflowsheets.place(f, s.id, u.parent)
        if (tag.isNotBlank()) f = FlowsheetEditing.rename(f, s.id, tag.trim())
        out(f) { put("id", s.id); put("tag", f.obj(s.id)!!.tag) }
    }

    fun disconnect(flowsheetJson: String, fromRef: String, toRef: String): String = edit {
        val f = fs(flowsheetJson)
        val a = find(f, fromRef); val b = find(f, toRef)
        val c = f.connections.firstOrNull { it.fromObject == a.id && it.toObject == b.id } ?: throw EditRejected("${a.tag} does not run into ${b.tag}")
        out(FlowsheetEditing.disconnect(f, c))
    }

    fun rename(flowsheetJson: String, ref: String, tag: String): String = edit { val f = fs(flowsheetJson); out(FlowsheetEditing.rename(f, find(f, ref).id, tag)) }
    fun delete(flowsheetJson: String, ref: String): String = edit { val f = fs(flowsheetJson); out(FlowsheetEditing.delete(f, find(f, ref).id)) }

    /** Puts [refsJson]'s objects in a new sub-flowsheet: {flowsheet, id, tag}. */
    fun group(flowsheetJson: String, refsJson: String): String = edit {
        val f = fs(flowsheetJson)
        val chosen = refs(f, refsJson)
        val (g, b) = Subflowsheets.group(f, chosen.map { it.id }, chosen.map { it.x }.average(), chosen.map { it.y }.average() + 120)
        out(g) { put("id", b.id); put("tag", b.tag) }
    }

    /** Moves [refsJson]'s objects into sub-flowsheet [blockRef] (D139). */
    fun moveInto(flowsheetJson: String, refsJson: String, blockRef: String): String = edit {
        val f = fs(flowsheetJson)
        out(Subflowsheets.moveInto(f, refs(f, refsJson).map { it.id }, block(f, blockRef).id))
    }

    /** A copy of sub-flowsheet [blockRef] with everything inside: {flowsheet, id, tag, notes}. */
    fun duplicateBlock(flowsheetJson: String, blockRef: String): String = edit {
        val f = fs(flowsheetJson)
        val (g, b, notes) = Subflowsheets.duplicate(f, block(f, blockRef).id)
        out(g) { put("id", b.id); put("tag", b.tag); putJsonArray("notes") { notes.forEach { add(JsonPrimitive(it)) } } }
    }

    /** Matches stream [outsideRef] of the block's flowsheet to the block as a new stream in ([feed]) or out: {flowsheet, id}. */
    fun connectBlock(flowsheetJson: String, blockRef: String, outsideRef: String, feed: Boolean): String = edit {
        val f = fs(flowsheetJson)
        val (g, id) = Subflowsheets.connect(f, block(f, blockRef).id, find(f, outsideRef).id, feed)
        out(g) { put("id", id); put("tag", g.obj(id)!!.tag) }
    }

    private fun refs(f: Flowsheet, refsJson: String): List<SimObject> =
        kotlinx.serialization.json.Json.parseToJsonElement(refsJson).let { it as? kotlinx.serialization.json.JsonArray ?: throw EditRejected("Give a list of tags") }
            .map { find(f, it.jsonPrimitive.content) }.ifEmpty { throw EditRejected("Give at least one object") }

    // ---------- safety cases (D54–D57): defined whole, in SI, equipment by tag ----------

    /**
     * Adds or replaces a relief valve ([kind] "relief": ReliefDeviceDef), a depressuring case ("depressuring":
     * DepressuringCase) or a pipeline pressurization ("pressurization": PressurizationCase) from [defJson], in SI, the
     * protected equipment, supply and pipes named by tag or id. Without an id a new one is made. {flowsheet, id}.
     */
    fun safetyCase(flowsheetJson: String, kind: String, defJson: String): String = edit {
        val f = fs(flowsheetJson)
        val o = FlowsheetJson.parseToJsonElement(defJson).jsonObject.toMutableMap()
        fun resolve(k: String) { o[k]?.jsonPrimitive?.content?.let { o[k] = JsonPrimitive(find(f, it).id) } }
        val id = o["id"]?.jsonPrimitive?.content ?: FlowsheetEditing.newId()
        o["id"] = JsonPrimitive(id)
        resolve("protectedId"); resolve("supplyId")
        (o["pipeIds"] as? kotlinx.serialization.json.JsonArray)?.let { a -> o["pipeIds"] = kotlinx.serialization.json.JsonArray(a.map { JsonPrimitive(find(f, it.jsonPrimitive.content).id) }) }
        val next = when (kind) {
            "relief" -> {
                if (o["tag"] == null) o["tag"] = JsonPrimitive("PSV-${(f.safety?.devices?.size ?: 0) + 100}")
                FlowsheetEditing.upsertReliefDevice(f, FlowsheetJson.decodeFromJsonElement(app.flowstead.core.model.ReliefDeviceDef.serializer(), JsonObject(o)))
            }
            "depressuring" -> {
                if (o["name"] == null) o["name"] = JsonPrimitive("Depressuring ${(f.safety?.depressuring?.size ?: 0) + 1}")
                FlowsheetEditing.upsertDepressuring(f, FlowsheetJson.decodeFromJsonElement(app.flowstead.core.model.DepressuringCase.serializer(), JsonObject(o)))
            }
            "pressurization" -> {
                if (o["name"] == null) o["name"] = JsonPrimitive("Pressurization ${(f.safety?.pressurization?.size ?: 0) + 1}")
                FlowsheetEditing.upsertPressurization(f, FlowsheetJson.decodeFromJsonElement(app.flowstead.core.model.PressurizationCase.serializer(), JsonObject(o)))
            }
            else -> throw EditRejected("Safety cases are relief, depressuring or pressurization")
        }
        out(next) { put("id", id) }
    }

    /** What stops a safety case from running, or "" (the app's checks: equipment, set pressure, a solve…). */
    fun safetyProblem(flowsheetJson: String, solveJson: String?, solvedHash: String, kind: String, id: String): String {
        val f = fs(flowsheetJson)
        val rv = view(f, solveJson, solvedHash)
        return when (kind) {
            "relief" -> f.safety?.devices?.firstOrNull { it.id == id }?.let { app.flowstead.core.model.Safety.problem(f, rv, it) }
            "depressuring" -> f.safety?.depressuring?.firstOrNull { it.id == id }?.let { app.flowstead.core.model.Safety.depressuringProblem(f, rv, it) }
            "pressurization" -> f.safety?.pressurization?.firstOrNull { it.id == id }?.let { app.flowstead.core.model.Safety.pressurizationProblem(f, rv, it) }
            else -> "Safety cases are relief, depressuring or pressurization"
        } ?: ""
    }

    // ---------- sizing (the app's: separators, equipment, lines), on the engine's results ----------

    /**
     * The sizing Flowstead does for [ref] on the last solve, as the app's sheets: a separator (GPSA, Svrcek–Monnery,
     * API 12J), exchangers, heaters, coolers, air coolers, pumps, compressors, expanders, tanks and columns (D62, D65,
     * D106), or a pipe's line-sizing checks against the case's criteria (D47). Lengths in m, areas m², volumes m³,
     * velocities m/s: the report says each quantity. {kind, ..., notes, warnings} or {error} (with what to do).
     */
    fun sizing(flowsheetJson: String, solveJson: String?, solvedHash: String, ref: String): String = try {
        val f = fs(flowsheetJson)
        val o = find(f, ref)
        val rv = view(f, solveJson, solvedHash)
        if (rv.stale) throw EditRejected("The results are from before the last change: solve first")
        buildJsonObject {
            when (o.type) {
                ObjectType.Separator, ObjectType.ThreePhaseSeparator -> {
                    val (loads, why) = SeparatorSizing.loads(f, rv, o)
                    if (loads == null) throw EditRejected(why ?: "Solve the flowsheet first")
                    val s = SeparatorSizing.size(loads, o.sizing ?: SeparatorSizingInput(), o.type == ObjectType.ThreePhaseSeparator)
                    put("kind", "separator"); put("orientation", if (s.horizontal) "horizontal" else "vertical"); put("threePhase", s.threePhase)
                    put("diameter_m", s.diameter); put("length_m", s.length); put("slenderness", s.slenderness)
                    put("k_m_s", s.k); put("maxGasVelocity_m_s", s.maxGasVelocity); put("gasVelocity_m_s", s.gasVelocity)
                    put("liquidVolume_m3", s.liquidVolume); s.mistEliminatorArea?.let { put("mistEliminatorArea_m2", it) }
                    putJsonArray("levels") { s.levels.forEach { l -> addJsonObject { put("name", l.name); put("height_m", l.height) } } }
                    putJsonArray("nozzles") { s.nozzles.forEach { n -> addJsonObject { put("name", n.name); put("requiredDiameter_m", n.requiredDiameter); n.nps?.let { put("nps", it) }; n.insideDiameter?.let { put("insideDiameter_m", it) }; put("limit", n.limit) } } }
                    putJsonObject("basis") { val i = o.sizing ?: SeparatorSizingInput(); put("mistEliminator", i.mistEliminator.label); put("inletDevice", i.inletDevice.label); put("holdupTime_s", i.holdupTime); put("surgeTime_s", i.surgeTime) }
                    putJsonArray("notes") { s.notes.forEach { add(JsonPrimitive(it)) } }
                    putJsonArray("warnings") { s.warnings.forEach { add(JsonPrimitive(it)) } }
                }
                ObjectType.PipeSegment -> {
                    val service = LineSizing.service(f, o, rv) ?: throw EditRejected("Solve first: the service comes from the solved streams")
                    val c = (f.sizingCriteria ?: SizingCriteria()).forService(service)
                    put("kind", "line"); put("service", service.name.lowercase()); put("criteriaSet", !c.isEmpty)
                    putJsonArray("checks") {
                        LineSizing.checks(c, o.id, rv).forEach { ch ->
                            addJsonObject { put("label", ch.label); put("quantity", ch.quantity.name); put("value", ch.value); put("limit", ch.limit); put("maximum", ch.isMaximum); put("passes", ch.passes); ch.note?.let { put("note", it) } }
                        }
                    }
                    if (c.isEmpty) putJsonArray("warnings") { add(JsonPrimitive("No line-sizing criteria set for ${service.label.lowercase()} lines: set them with setLineCriteria (the project's design basis)")) }
                }
                in EquipmentSizing.TYPES -> {
                    val s = EquipmentSizing.size(f, rv, o, o.equipmentSizing ?: EquipmentSizingInput())
                        ?: throw EditRejected("Solve the flowsheet (and connect the streams) to size it")
                    put("kind", "equipment")
                    putJsonArray("lines") {
                        s.lines.forEach { l -> addJsonObject { put("label", l.label); l.si?.let { put("si", it) }; l.quantity?.let { put("quantity", it.name); put("siUnit", Units.si(it).id) }; l.text?.let { put("text", it) } } }
                    }
                    putJsonArray("notes") { s.notes.forEach { add(JsonPrimitive(it)) } }
                    putJsonArray("warnings") { s.warnings.forEach { add(JsonPrimitive(it)) } }
                }
                else -> throw EditRejected("Flowstead sizes separators, exchangers, heaters, coolers, air coolers, pumps, compressors, expanders, tanks, columns and pipes, not ${o.type.displayName()}")
            }
        }.toString()
    } catch (e: Exception) { err(e) }

    /** A separator's sizing basis ({horizontal, mistEliminator, inletDevice, k, holdupTime, surgeTime, ...}, SI) or an equipment's ({service, u, margin, ...}); an empty object restores the library's. */
    fun setSizingInput(flowsheetJson: String, ref: String, inputJson: String): String = edit {
        val f = fs(flowsheetJson)
        val o = find(f, ref)
        val empty = FlowsheetJson.parseToJsonElement(inputJson).jsonObject.isEmpty()
        when (o.type) {
            ObjectType.Separator, ObjectType.ThreePhaseSeparator ->
                out(FlowsheetEditing.setSizing(f, o.id, if (empty) null else FlowsheetJson.decodeFromString(SeparatorSizingInput.serializer(), inputJson)))
            in EquipmentSizing.TYPES ->
                out(FlowsheetEditing.setEquipmentSizing(f, o.id, if (empty) null else FlowsheetJson.decodeFromString(EquipmentSizingInput.serializer(), inputJson)))
            else -> throw EditRejected("${o.tag} has no sizing basis")
        }
    }

    /**
     * The case's line-sizing criteria for [service] ("gas", "liquid" or "two_phase"), in SI ({maxVelocity, minVelocity,
     * maxMomentumFlux, maxFrictionGradient (Pa/m), maxMach, erosionalC}); an absent field is not checked.
     */
    fun setLineCriteria(flowsheetJson: String, service: String, criteriaJson: String): String = edit {
        val f = fs(flowsheetJson)
        val s = when (service) { "gas" -> LineService.Gas; "liquid" -> LineService.Liquid; "two_phase", "twoPhase" -> LineService.TwoPhase; else -> throw EditRejected("Service is gas, liquid or two_phase") }
        val o = FlowsheetJson.parseToJsonElement(criteriaJson).jsonObject
        fun sp(k: String, q: Quantity) = o[k]?.jsonPrimitive?.doubleOrNull?.let { Spec(it, Units.si(q).id) }
        val c = LineCriteria(sp("maxVelocity", Quantity.Velocity), sp("minVelocity", Quantity.Velocity), sp("maxMomentumFlux", Quantity.MomentumFlux),
            sp("maxFrictionGradient", Quantity.PressureGradient), sp("maxMach", Quantity.Ratio), o["erosionalC"]?.jsonPrimitive?.doubleOrNull?.let { Spec(it, "") })
        out(FlowsheetEditing.setSizingCriteria(f, (f.sizingCriteria ?: SizingCriteria()).with(s, c)))
    }
}
