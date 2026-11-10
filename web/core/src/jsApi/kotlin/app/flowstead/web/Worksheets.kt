/*
 * Flowstead web (D111): an object's worksheet, as the app's ObjectEditorSheet builds it (ObjectEditor.kt, PipeSizing.kt):
 * the same fields per type, in the same order, with the same hints and choices. Kotlin describes the form as JSON and
 * applies every change with the app's FlowsheetEditing; the page only lays the form out.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:OptIn(ExperimentalJsExport::class)

package app.flowstead.web

import app.flowstead.core.format.ValueFormat
import app.flowstead.core.model.AdjustKeys
import app.flowstead.core.model.ChoiceKey
import app.flowstead.core.model.Columns
import app.flowstead.core.model.CompositionBasis
import app.flowstead.core.model.EditRejected
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.FlowsheetEditing
import app.flowstead.core.model.FlowsheetJson
import app.flowstead.core.model.LineSizing
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.Port
import app.flowstead.core.model.PortKind
import app.flowstead.core.model.PortSide
import app.flowstead.core.model.Ports
import app.flowstead.core.model.ResultKeys
import app.flowstead.core.model.SetKeys
import app.flowstead.core.model.SimObject
import app.flowstead.core.model.Spec
import app.flowstead.core.model.SpecKey
import app.flowstead.core.model.ReactionType
import app.flowstead.core.model.Reactors
import app.flowstead.core.model.ReactorKeys
import app.flowstead.core.model.PipeSizes
import app.flowstead.core.model.Specification
import app.flowstead.core.model.ValveLibrary
import app.flowstead.core.model.displayName
import app.flowstead.core.model.isFeed
import app.flowstead.core.model.isLoop
import app.flowstead.core.model.obj
import app.flowstead.core.model.streamAt
import app.flowstead.core.units.EngUnit
import app.flowstead.core.units.Quantity
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.UnitSet
import app.flowstead.core.units.Units
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val NEW_STREAM = "__new"
private const val DISCONNECT = "__disconnect"
private const val CUSTOM = "__custom"
private const val AUTO = "__auto"

/** A tolerance on a temperature or pressure is a difference, not an absolute value (as the app). */
private fun differenceOf(q: Quantity): Quantity = when (q) {
    Quantity.Temperature -> Quantity.TemperatureDifference
    Quantity.Pressure -> Quantity.PressureDifference
    else -> q
}

/** The unit set's unit, except that diameters and roughness use mm or inches (the app's defaultUnit). */
private fun defaultUnit(key: String, quantity: Quantity, unitSet: UnitSet): EngUnit {
    if (key == SpecKey.EFFICIENCY) return Units.percent
    val u = unitSet.unitFor(quantity)
    if (key !in SpecKey.SMALL_LENGTHS) return u
    return when (u) { Units.m -> Units.mm; Units.ft -> Units.inch; else -> u }
}

@JsExport
object Worksheets {
    private val unitSet: UnitSet get() = displayUnits
    private fun fs(json: String): Flowsheet = FlowsheetJson.decodeFromString(Flowsheet.serializer(), json)
    private fun out(f: Flowsheet): String = FlowsheetJson.encodeToString(Flowsheet.serializer(), f)

    /** The worksheet of object [id]: {id, tag, type, typeLabel, isStream, items: [...]}. */
    fun form(flowsheetJson: String, id: String): String {
        val f = fs(flowsheetJson)
        val o = f.obj(id) ?: return "null"
        val ctx = UnitContext(f.atmosphericPressurePa)
        val b = Form(f, o, ctx)
        b.build()
        return buildJsonObject {
            put("id", o.id); put("tag", o.tag); put("type", ObjectType.serializer().descriptor.getElementName(o.type.ordinal))
            put("typeLabel", o.type.displayName()); put("isStream", o.type.isStream)
            put("items", JsonArray(b.items))
        }.toString()
    }

    /**
     * Applies one worksheet change, as the app's EditorActions do. [actionJson] is what the form gave the page, with
     * the user's input: {op: "spec"|"exclusive"|"ref"|"refs"|"valveType"|"pipeSize"|"composition"|"normalise"|
     * "connect"|"disconnect"|"rename", ...}. Returns {flowsheet} or {error}.
     */
    fun apply(flowsheetJson: String, actionJson: String): String = try {
        val f = fs(flowsheetJson)
        val a = FlowsheetJson.parseToJsonElement(actionJson).jsonObject
        fun s(k: String) = a[k]?.jsonPrimitive?.content
        val id = s("id") ?: throw EditRejected("No object")
        val ctx = UnitContext(f.atmosphericPressurePa)
        fun spec(): Spec? {
            val text = s("value")?.trim()?.replace(',', '.').orEmpty()
            if (text.isEmpty()) return null
            val v = text.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw EditRejected("Not a number: $text")
            val u = Units.byIdOrNull(s("unit") ?: "") ?: throw EditRejected("Unknown unit")
            return Spec(u.toSi(v, ctx), u.id)
        }
        val next = when (s("op")) {
            "spec" -> FlowsheetEditing.setSpec(f, id, s("key")!!, spec())
            "exclusive" -> {
                val group = a["group"]!!.jsonArray.map { it.jsonPrimitive.content }
                val sp = spec()
                if (sp == null) FlowsheetEditing.setSpec(f, id, s("key")!!, null)
                else FlowsheetEditing.setExclusiveSpec(f, id, s("key")!!, sp, group)
            }
            // A unit change re-expresses the stored value in the new unit, keeping its physical size (QuantityField).
            "unit" -> {
                val key = s("key")!!
                val cur = f.obj(id)?.specs?.get(key) ?: return "{}"
                FlowsheetEditing.setSpec(f, id, key, cur.copy(unit = s("unit")!!))
            }
            "ref" -> FlowsheetEditing.setRef(f, id, s("key")!!, s("value")?.takeIf { it.isNotEmpty() })
            "refs" -> a["refs"]!!.jsonObject.entries.fold(f) { acc, (k, v) ->
                FlowsheetEditing.setRef(acc, id, k, (v as? JsonPrimitive)?.takeIf { it.isString }?.content)
            }
            "valveType" -> {
                val t = ValveLibrary.TYPES.first { it.id == s("value") }
                FlowsheetEditing.setSpec(FlowsheetEditing.setSpec(f, id, SpecKey.VALVE_FL, Spec(t.fl, "ratio")), id, SpecKey.VALVE_XT, Spec(t.xt, "ratio"))
            }
            "pipeSize" -> FlowsheetEditing.setPipeSize(f, id, s("nps")?.takeIf { it != CUSTOM && it.isNotEmpty() }, s("schedule")?.takeIf { it.isNotEmpty() })
            "composition", "normalise" -> {
                val basis = CompositionBasis.valueOf(s("basis") ?: "MoleFraction")
                val entered = a["values"]!!.jsonObject.mapNotNull { (c, v) ->
                    val t = v.jsonPrimitive.content.trim().replace(',', '.')
                    if (t.isEmpty()) null else c to (t.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: throw EditRejected("$c: numbers ≥ 0 only"))
                }.toMap()
                val mole = Fluids.toMole(basis, entered, a["basisData"]?.takeIf { it !is kotlinx.serialization.json.JsonNull })
                if (s("op") == "normalise") {
                    if (mole.values.sum() <= 0) throw EditRejected("Nothing to normalise")
                    FlowsheetEditing.setComposition(f, id, FlowsheetEditing.normalise(mole))
                } else FlowsheetEditing.setComposition(f, id, mole)
            }
            "connect" -> {
                val port = port(f, id, a)
                when (val v = s("value")) {
                    NEW_STREAM -> {
                        // A port takes one stream: a new one replaces what is attached.
                        val freed = if (f.streamAt(id, port) != null) FlowsheetEditing.disconnectPort(f, id, port) else f
                        val unit = freed.obj(id)!!
                        val (x, y) = FlowsheetEditing.newStreamPosition(unit, port)
                        FlowsheetEditing.connectNewStream(freed, id, port, x, y).first
                    }
                    DISCONNECT -> FlowsheetEditing.disconnectPort(f, id, port)
                    else -> {
                        val freed = if (f.streamAt(id, port) != null) FlowsheetEditing.disconnectPort(f, id, port) else f
                        FlowsheetEditing.connect(freed, v!!, id, port)
                    }
                }
            }
            "rename" -> FlowsheetEditing.rename(f, id, s("value").orEmpty().trim())
            else -> throw EditRejected("Unknown change")
        }
        buildJsonObject { put("flowsheet", FlowsheetJson.parseToJsonElement(out(next))) }.toString()
    } catch (e: IllegalArgumentException) {
        buildJsonObject { put("error", e.message ?: "Not allowed") }.toString()
    } catch (e: NullPointerException) {
        buildJsonObject { put("error", "Incomplete change") }.toString()
    }

    private fun port(f: Flowsheet, id: String, a: JsonObject): Port {
        val unit = f.obj(id) ?: throw EditRejected("That unit operation no longer exists")
        val side = a["side"]!!.jsonPrimitive.content; val kind = a["kind"]!!.jsonPrimitive.content; val index = a["index"]!!.jsonPrimitive.content.toInt()
        return Ports.of(unit.type).first { it.side.name == side && it.kind.name == kind && it.index == index }
    }

    /** The fluid package (HYSYS's basis): {propertyPackage, compounds, usedBy: {compound: count}}. */
    fun basis(flowsheetJson: String): String {
        val f = fs(flowsheetJson)
        return buildJsonObject {
            put("propertyPackage", f.propertyPackage ?: "")
            putJsonArray("compounds") { f.compounds.forEach { add(JsonPrimitive(it)) } }
            putJsonObject("usedBy") { f.compounds.forEach { c -> put(c, f.objects.count { c in it.composition }) } }
            putJsonArray("hypotheticals") { f.hypotheticals.forEach { add(JsonPrimitive(it.name)) } }
            putJsonArray("blackOils") { f.blackOils.forEach { add(JsonPrimitive(it.name)) } }
        }.toString()
    }

    fun setPropertyPackage(flowsheetJson: String, name: String): String = edit { fs(flowsheetJson).copy(propertyPackage = name) }

    /** Adds a compound to the fluid, as the app's Fluid package screen (FlowsheetEditing.setCompounds). */
    fun addCompound(flowsheetJson: String, name: String): String = edit {
        val f = fs(flowsheetJson)
        if (name in f.compounds) throw EditRejected("$name is already in the list")
        FlowsheetEditing.setCompounds(f, f.compounds + name)
    }

    /** Removes a compound from the fluid and from every stream's composition. */
    fun removeCompound(flowsheetJson: String, name: String): String = edit { val f = fs(flowsheetJson); FlowsheetEditing.setCompounds(f, f.compounds - name) }

    private fun edit(block: () -> Flowsheet): String = try {
        buildJsonObject { put("flowsheet", FlowsheetJson.parseToJsonElement(out(block()))) }.toString()
    } catch (e: IllegalArgumentException) {
        buildJsonObject { put("error", e.message ?: "Not allowed") }.toString()
    }
}

/** Builds one worksheet in the order of the app's ObjectEditorSheet. */
private class Form(val fs: Flowsheet, val obj: SimObject, val ctx: UnitContext) {
    val items = mutableListOf<JsonElement>()
    val units = displayUnits

    fun note(text: String) { items += buildJsonObject { put("kind", "note"); put("text", text) } }
    fun label(text: String) { items += buildJsonObject { put("kind", "label"); put("text", text) } }

    /** A number field with its unit picker (the app's SpecField): blank means "not given", never a guessed value. */
    fun specField(key: String, hint: String? = null, quantity: Quantity? = null, label: String? = null, group: List<String>? = null): JsonObject {
        val q = quantity ?: SpecKey.quantity(key)
        val spec = obj.specs[key]
        val def = defaultUnit(key, q, units)
        val unit = spec?.unit?.let { Units.byIdOrNull(it) }?.takeIf { it.quantity == q } ?: def
        return buildJsonObject {
            put("kind", "spec"); put("key", key); put("label", label ?: SpecKey.label(key))
            put("value", spec?.let { ValueFormat.number(unit.fromSi(it.si, ctx), unit.decimals).replace(",", "") } ?: "")
            put("specified", spec != null); put("unit", unit.id)
            putJsonArray("units") { Units.forQuantity(q).forEach { u -> addJsonObject { put("id", u.id); put("symbol", u.symbol.ifEmpty { u.id }) } } }
            hint?.let { put("hint", it) }
            group?.let { g -> putJsonArray("group") { g.forEach { add(JsonPrimitive(it)) } } }
        }
    }

    fun spec(key: String, hint: String? = null, quantity: Quantity? = null, label: String? = null) { items += specField(key, hint, quantity, label) }

    /** One of several alternative specs (outlet pressure OR pressure drop): the one given is shown first. */
    fun exclusive(choices: List<Pair<String, String>>, style: String = "segmented", title: String? = null, componentRef: String? = null) {
        val keys = choices.map { it.first }
        items += buildJsonObject {
            put("kind", "exclusive"); put("style", style); title?.let { put("label", it) }
            put("chosen", keys.firstOrNull { it in obj.specs } ?: keys.first())
            putJsonArray("choices") {
                choices.forEach { (k, l) -> addJsonObject { put("key", k); put("label", l); put("field", specField(k, group = keys)) } }
            }
            componentRef?.let { ref ->
                putJsonObject("component") {
                    put("ref", ref); put("value", obj.refs[ref] ?: "")
                    putJsonArray("options") { fs.compounds.forEach { add(JsonPrimitive(it)) } }
                }
            }
        }
    }

    /** A drop-down. [op] is the change it makes: {op: "ref", key} (and [clears]), "valveType", "pipeSize". */
    fun choice(label: String, selected: String?, options: List<Pair<String, String>>, op: JsonObject, placeholder: String = "Choose…") {
        items += buildJsonObject {
            put("kind", "choice"); put("label", label); put("value", selected ?: ""); put("placeholder", placeholder)
            putJsonArray("options") { options.forEach { (id, text) -> addJsonObject { put("id", id); put("label", text) } } }
            put("op", op)
        }
    }

    fun refOp(key: String, clears: List<String> = emptyList(), unsetIf: String? = null) = buildJsonObject {
        put("op", "ref"); put("key", key)
        if (clears.isNotEmpty()) putJsonArray("clears") { clears.forEach { add(JsonPrimitive(it)) } }
        unsetIf?.let { put("unsetIf", it) }
    }

    /** A method stored in the object's refs; the first option is the engine's default (MethodChoice). */
    fun method(label: String, key: String, options: List<Pair<String, String>>) {
        val current = obj.refs[key] ?: options.first().first
        choice(label, current, options, refOp(key, unsetIf = options.first().first))
    }

    fun build() {
        when (obj.type) {
            ObjectType.MaterialStream -> if (fs.isFeed(obj)) feed() else note("This stream is calculated from the unit it comes from.")
            ObjectType.EnergyStream -> note("Energy streams carry the duty or power of the unit they connect to.")
            ObjectType.Valve -> {
                exclusive(listOf(SpecKey.OUTLET_PRESSURE to "Outlet pressure", SpecKey.PRESSURE_DROP to "Pressure drop"))
                spec(SpecKey.RATED_CV, "for the opening; leave empty to size only")
                choice("Valve type", ValveLibrary.of(obj)?.id, ValveLibrary.TYPES.map { it.id to it.label }, buildJsonObject { put("op", "valveType") },
                    placeholder = "Custom (vendor's FL and xT)")
                spec(SpecKey.VALVE_FL, "typical for the type; 0.9 if empty")
                spec(SpecKey.VALVE_XT, "typical for the type; 0.72 if empty")
                note("Sized by IEC 60534 with these factors (typical values, IEC 60534-2-1 Annex A): the Cv it needs, and choked flow or cavitation.")
            }
            ObjectType.AirCooler -> {
                note("Process side only: the engine finds the duty to air; fan power is not modelled.")
                exclusive(listOf(SpecKey.OUTLET_TEMPERATURE to "Outlet temperature", SpecKey.DUTY to "Duty"))
                spec(SpecKey.PRESSURE_DROP, "0 if left empty")
            }
            ObjectType.Heater, ObjectType.Cooler -> {
                exclusive(listOf(SpecKey.OUTLET_TEMPERATURE to "Outlet temperature", SpecKey.DUTY to "Duty"))
                spec(SpecKey.PRESSURE_DROP, "0 if left empty")
            }
            ObjectType.Separator, ObjectType.ThreePhaseSeparator ->
                note("Adiabatic flash at the lowest feed pressure. Nothing to specify: connect the feed and the product streams.")
            ObjectType.Pump -> {
                if (obj.curve?.solvesSpeed != false) exclusive(listOf(SpecKey.OUTLET_PRESSURE to "Outlet P", SpecKey.PRESSURE_RISE to "ΔP rise"))
                spec(SpecKey.EFFICIENCY, if (obj.curve == null) "75 % if empty" else "used if the curve has no efficiency")
                curveNote()
            }
            ObjectType.Compressor -> {
                if (obj.curve?.solvesSpeed != false) exclusive(listOf(SpecKey.OUTLET_PRESSURE to "Outlet P", SpecKey.PRESSURE_RISE to "ΔP", SpecKey.PRESSURE_RATIO to "Ratio"))
                spec(SpecKey.EFFICIENCY, if (obj.curve == null) "75 % adiabatic if empty" else "used if the curve has no efficiency")
                curveNote()
            }
            ObjectType.Expander -> {
                exclusive(listOf(SpecKey.OUTLET_PRESSURE to "Outlet P", SpecKey.PRESSURE_DROP to "ΔP", SpecKey.PRESSURE_RATIO to "Ratio"))
                spec(SpecKey.EFFICIENCY, "75 % adiabatic if empty")
            }
            ObjectType.HeatExchanger -> {
                note("Counter-current. The engine finds the hot and cold sides from the inlet temperatures.")
                exclusive(listOf(SpecKey.DUTY to "Duty", SpecKey.HOT_OUTLET_TEMPERATURE to "Hot out T", SpecKey.COLD_OUTLET_TEMPERATURE to "Cold out T"))
                spec(SpecKey.HOT_PRESSURE_DROP, "0 if empty")
                spec(SpecKey.COLD_PRESSURE_DROP, "0 if empty")
            }
            ObjectType.Splitter -> {
                spec(SpecKey.SPLIT_1)
                spec(SpecKey.SPLIT_2, "the rest if empty")
                note("Outlet 3, when connected, takes whatever is left.")
            }
            ObjectType.Mixer -> note("Adiabatic mixing; the outlet is at the lowest inlet pressure. Nothing to specify.")
            ObjectType.PipeSegment -> pipe()
            ObjectType.ComponentSplitter -> {
                note("Fraction of each component's feed sent to the overhead; the rest goes to the bottoms. Both leave at the feed temperature and pressure.")
                if (fs.compounds.isEmpty()) note("Add components in the Basis first.")
                for (c in fs.compounds) spec(SpecKey.split(c), "0 (all to bottoms)")
            }
            ObjectType.Tank -> {
                note("Flash at the feed pressure, like a separator. Give the volume to see the liquid residence time.")
                spec(SpecKey.VOLUME, "optional")
            }
            ObjectType.Set -> set()
            ObjectType.DistillationColumn, ObjectType.Absorber, ObjectType.ReboiledAbsorber, ObjectType.RefluxedAbsorber -> column()
            ObjectType.ShortcutColumn -> shortcut()
            ObjectType.OrificePlate -> {
                spec(SpecKey.ORIFICE_DIAMETER)
                label("Pipe the plate is in")
                pipeSize()
                method("Pressure taps", ChoiceKey.ORIFICE_TAPS, ChoiceKey.ORIFICE_TAP_OPTIONS)
                note("ISO 5167 orifice meter. The outlet is at the permanent (recovered) pressure.")
            }
            ObjectType.Adjust, ObjectType.Controller -> adjust()
            ObjectType.Recycle -> note("Tear stream: the engine iterates until the assumed and calculated streams agree (1e-6 kg/s, 0.001 K). Iterations are reported in the results.")
            ObjectType.ConversionReactor, ObjectType.EquilibriumReactor, ObjectType.GibbsReactor, ObjectType.Cstr, ObjectType.Pfr -> {
                // As the app's ReactorEditor (D136): operating mode, size and catalyst here; the reactions below.
                if (obj.type == ObjectType.GibbsReactor) note("Minimises Gibbs energy over every component in the fluid package: no reactions to define.")
                method("Operation", ReactorKeys.MODE, ReactorKeys.MODES)
                if (obj.refs[ReactorKeys.MODE] == "outlet_temperature") spec(SpecKey.OUTLET_TEMPERATURE)
                if (obj.type == ObjectType.Cstr || obj.type == ObjectType.Pfr) spec(SpecKey.VOLUME)
                if (obj.type == ObjectType.Pfr) spec(SpecKey.REACTOR_LENGTH)
                spec(SpecKey.PRESSURE_DROP, "0 if empty")
                if (Reactors.reactionType(obj.type) == ReactionType.Kinetic && Reactors.reactions(fs, obj).any { it.type == ReactionType.Catalytic }) {
                    label("Catalyst")
                    note("A catalytic rate is per kg of catalyst; the reactor holds loading × volume of it.")
                    spec(SpecKey.CATALYST_LOADING)
                    if (obj.type == ObjectType.Pfr) { spec(SpecKey.CATALYST_VOID_FRACTION); spec(SpecKey.CATALYST_PARTICLE_DIAMETER, "Ergun pressure drop if given") }
                }
            }
            ObjectType.ReliefValve -> note("Pressure safety valve: its relief cases are set up in the Android app's Safety analysis for now. It takes no part in the solve.")
            else -> note("${obj.type.displayName()} editing arrives with the remaining unit operations.")
        }
        if (!obj.type.isStream && Ports.of(obj.type).isNotEmpty()) connections()
    }

    private fun curveNote() { if (obj.curve != null) note("This machine runs on its performance curve, below.") }

    /** A feed: conditions (give two), flow (give one), composition (the app's FeedStreamEditor). */
    private fun feed() {
        label("Conditions — give two")
        for (k in SpecKey.STREAM_STATE) spec(k)
        label("Flow — give one")
        for (k in SpecKey.STREAM_FLOW) spec(k)
        label("Composition")
        items += buildJsonObject {
            put("kind", "composition")
            putJsonArray("bases") { CompositionBasis.entries.forEach { b -> addJsonObject { put("id", b.name); put("label", b.label); put("total", b.total); put("needsEngineData", b.needsEngineData) } } }
            put("tolerance", Specification.COMPOSITION_TOLERANCE)
            putJsonArray("compounds") {
                fs.compounds.forEach { c -> addJsonObject { put("name", c); obj.composition[c]?.let { put("mole", it) } } }
            }
        }
    }

    private fun pipe() {
        val sections = !obj.pipeSections.isNullOrEmpty()
        if (!sections) spec(SpecKey.LENGTH)
        label("Pipe size")
        pipeSize()
        if (!sections) spec(SpecKey.ELEVATION_CHANGE, "0 (horizontal) if empty")
        spec(SpecKey.ROUGHNESS, "0.0457 mm (commercial steel) if empty")
        if (!sections) spec(SpecKey.INCREMENTS, "10 if empty; 20–50 for long gas lines")
        if (sections) note("This line has ${obj.pipeSections!!.size} sections with fittings, below.")
        label("Flow model")
        note("All gas or all liquid: Darcy–Weisbach with the Chen friction factor, applied in every increment; gas density is re-evaluated along the line. When gas and liquid flow together, the correlation below applies.")
        method("Two-phase correlation", ChoiceKey.PIPE_CORRELATION, ChoiceKey.PIPE_CORRELATIONS)
        choice("Service (sizing criteria)", obj.refs[LineSizing.SERVICE] ?: AUTO, LineSizing.SERVICE_OPTIONS.map { (it.first ?: AUTO) to it.second },
            refOp(LineSizing.SERVICE, unsetIf = AUTO))
        label("Heat transfer")
        spec(SpecKey.HEAT_TRANSFER_COEFFICIENT, "adiabatic if empty")
        spec(SpecKey.AMBIENT_TEMPERATURE, "needed with U")
    }

    private fun pipeSize() {
        val size = obj.refs[LineSizing.NPS]?.let { PipeSizes.size(it) }
        val schedule = size?.let { s -> obj.refs[LineSizing.SCHEDULE]?.let { s.schedule(it) } }
        choice("Nominal size", size?.nps ?: CUSTOM, listOf(CUSTOM to "Custom inside diameter") + PipeSizes.ALL.map { it.nps to it.label },
            buildJsonObject { put("op", "pipeSize"); put("schedule", schedule?.label ?: "") })
        if (size != null && schedule != null) {
            choice("Schedule", schedule.label, size.schedules.map { it.label to it.label }, buildJsonObject { put("op", "pipeSize"); put("nps", size.nps) })
            val mm = Units.mm
            note("OD ${ValueFormat.number(mm.fromSi(size.outsideDiameterM), 2)} mm · wall ${ValueFormat.number(mm.fromSi(schedule.wallM), 2)} mm · ID ${ValueFormat.number(mm.fromSi(size.insideDiameterM(schedule)), 2)} mm (ASME B36.10M / B36.19M)")
        } else {
            spec(PipeSizes.insideDiameterKey(obj.type))
            if (obj.type == ObjectType.PipeSegment) spec(SpecKey.OUTER_DIAMETER, "1.15 × inside if empty (heat transfer only)")
        }
    }

    private fun column() {
        note(when (obj.type) {
            ObjectType.Absorber -> "No condenser or reboiler: liquid enters on tray 1, gas under the last tray."
            ObjectType.ReboiledAbsorber -> "Stripper: liquid feed on tray 1, a reboiler at the bottom, vapour leaves the top."
            else -> "Trays are theoretical stages counted from the top, condenser and reboiler not included."
        })
        spec(SpecKey.TRAYS)
        for (port in Columns.variableFeedPorts(obj.type)) {
            if (fs.connections.any { it.toObject == obj.id && it.toPort == port }) spec(SpecKey.feedStage(port))
        }
        spec(SpecKey.TOP_PRESSURE)
        spec(SpecKey.COLUMN_PRESSURE_DROP, "0 if empty")
        if (Columns.hasCondenser(obj.type)) {
            label("Condenser")
            method("Type", ChoiceKey.CONDENSER, ChoiceKey.CONDENSER_OPTIONS)
            if (obj.refs[ChoiceKey.CONDENSER] == "partial") spec(SpecKey.VAPOUR_RATE)
            specChoice(SpecKey.CONDENSER_SPECS, ChoiceKey.DISTILLATE_COMPONENT)
        }
        if (Columns.hasReboiler(obj.type)) {
            label("Reboiler")
            specChoice(SpecKey.REBOILER_SPECS, ChoiceKey.BOTTOMS_COMPONENT)
        }
        if (obj.type != ObjectType.Absorber) {
            label("Solver")
            method("Method", ChoiceKey.COLUMN_SOLVER, ChoiceKey.COLUMN_SOLVERS)
            note("Most reliable: reflux ratio with a bottoms rate, boil-up ratio or bottoms purity. A column that misses its specs or its balances is shown as an error, never with numbers.")
        }
    }

    private fun specChoice(keys: List<String>, componentRef: String) =
        exclusive(keys.map { it to SpecKey.label(it) }, style = "select", title = "Specification", componentRef = componentRef)

    private fun shortcut() {
        note("Shortcut design: minimum reflux and stages, the stages for this reflux, and the duties.")
        choice("Light key", obj.refs[ChoiceKey.LIGHT_KEY], fs.compounds.map { it to it }, refOp(ChoiceKey.LIGHT_KEY))
        choice("Heavy key", obj.refs[ChoiceKey.HEAVY_KEY], fs.compounds.map { it to it }, refOp(ChoiceKey.HEAVY_KEY))
        spec(SpecKey.LIGHT_KEY_IN_BOTTOMS)
        spec(SpecKey.HEAVY_KEY_IN_DISTILLATE)
        spec(SpecKey.REFLUX_RATIO)
        spec(SpecKey.TOP_PRESSURE, label = "Condenser pressure")
        spec(SpecKey.REBOILER_PRESSURE, "condenser pressure if empty")
        method("Condenser", ChoiceKey.CONDENSER, ChoiceKey.CONDENSER_OPTIONS)
    }

    private fun set() {
        val candidates = fs.objects.filter { it.id != obj.id && SpecKey.forType(it.type).isNotEmpty() && (it.type != ObjectType.MaterialStream || fs.isFeed(it)) }
        val target = candidates.firstOrNull { it.id == obj.refs[SetKeys.TARGET_OBJECT] }
        val key = obj.refs[SetKeys.VARIABLE]
        label("Target")
        choice("Object", target?.id, candidates.map { it.id to "${it.tag} · ${it.type.displayName()}" }, refOp(SetKeys.TARGET_OBJECT, listOf(SetKeys.VARIABLE, SetKeys.SOURCE_OBJECT)))
        if (target != null) choice("Variable", key, SpecKey.forType(target.type).map { it to SpecKey.label(it) }, refOp(SetKeys.VARIABLE, listOf(SetKeys.SOURCE_OBJECT)))
        if (target != null && key != null) {
            label("Source")
            val sources = fs.objects.filter { it.id != target.id && it.id != obj.id && key in it.specs }
            val source = sources.firstOrNull { it.id == obj.refs[SetKeys.SOURCE_OBJECT] }
            choice("Object", source?.id, sources.map { it.id to "${it.tag} · ${it.type.displayName()}" }, refOp(SetKeys.SOURCE_OBJECT))
            val q = SpecKey.quantity(key)
            spec(SetKeys.MULTIPLIER, "1 if empty", Quantity.Ratio, "Multiplier")
            spec(SetKeys.OFFSET, "0 if empty", differenceOf(q), "Offset")
            note("${target.tag} ${SpecKey.label(key).lowercase()} = multiplier × ${source?.tag ?: "source"} ${SpecKey.label(key).lowercase()} + offset")
        }
    }

    private fun adjust() {
        val others = fs.objects.filter { it.id != obj.id && !it.type.isLoop && it.type != ObjectType.Set }
        val controller = obj.type == ObjectType.Controller
        val mKey = obj.refs[AdjustKeys.MANIPULATED_SPEC]
        val cKey = obj.refs[AdjustKeys.CONTROLLED_PROPERTY]
        val manipulated = others.firstOrNull { it.id == obj.refs[AdjustKeys.MANIPULATED_OBJECT] }
        val controlled = others.firstOrNull { it.id == obj.refs[AdjustKeys.CONTROLLED_OBJECT] }
        label(if (controller) "Moves (output)" else "Vary")
        choice("Object", manipulated?.id, others.filter { it.specs.isNotEmpty() }.map { it.id to "${it.tag} · ${it.type.displayName()}" },
            refOp(AdjustKeys.MANIPULATED_OBJECT, listOf(AdjustKeys.MANIPULATED_SPEC)))
        val levelPv = cKey in app.flowstead.core.model.Dynamics.DYNAMIC_ONLY
        val dynamicOnly = controller && (levelPv || obj.refs[AdjustKeys.STEADY_STATE] == "off")
        if (manipulated != null && !dynamicOnly) choice("Specification", mKey, manipulated.specs.keys.map { it to SpecKey.label(it) }, refOp(AdjustKeys.MANIPULATED_SPEC))
        if (controller) {
            items += buildJsonObject {
                put("kind", "switch"); put("label", "Acts in the steady state"); put("checked", !dynamicOnly); put("disabled", levelPv)
                put("hint", if (levelPv) "A level exists only in dynamic runs, so this controller acts there only."
                    else if (dynamicOnly) "Off: it acts in dynamic runs only, moving its valve's opening."
                    else "On: the steady solve meets its setpoint; in dynamic runs it acts as a PID.")
                put("key", AdjustKeys.STEADY_STATE)
            }
        }
        label(if (controller) "Measures (PV)" else "Until")
        choice("Object", controlled?.id, others.map { it.id to "${it.tag} · ${it.type.displayName()}" }, refOp(AdjustKeys.CONTROLLED_OBJECT, listOf(AdjustKeys.CONTROLLED_PROPERTY)))
        if (controlled != null) {
            val steady = if (controlled.type == ObjectType.MaterialStream) ResultKeys.STREAM else ResultKeys.forType(controlled.type)
            val keys = if (controller) (steady + app.flowstead.core.model.Dynamics.measurable(controlled)).distinct() else steady
            choice("Result", cKey, keys.map { it to ResultKeys.label(it) }, refOp(AdjustKeys.CONTROLLED_PROPERTY))
        }
        if (cKey != null) {
            val q = ResultKeys.quantity(cKey)
            spec(AdjustKeys.TARGET, quantity = q, label = if (controller) "Setpoint (SP) ${ResultKeys.label(cKey).lowercase()}" else "Target ${ResultKeys.label(cKey).lowercase()}")
            if (!dynamicOnly) spec(AdjustKeys.TOLERANCE, "1e-6 relative if empty", differenceOf(q), "Tolerance (optional)")
        }
        if (controller) note("Tuning (gain, integral and derivative times) is set in the Android app's Dynamics.")
        if (mKey != null && !dynamicOnly) {
            val q = SpecKey.quantity(mKey)
            val what = SpecKey.label(mKey).lowercase()
            spec(AdjustKeys.MINIMUM, "no limit", q, if (controller) "Output 0 %: $what" else "Lowest allowed $what")
            spec(AdjustKeys.MAXIMUM, "no limit", q, if (controller) "Output 100 %: $what" else "Highest allowed $what")
        }
    }

    /** Every port with what is attached, changeable from a list (the app's ConnectionsSection). */
    private fun connections() {
        label("Connections")
        for (port in Ports.of(obj.type)) {
            val attached = fs.streamAt(obj.id, port)
            val options = buildList {
                add(NEW_STREAM to if (port.kind == PortKind.Energy) "New energy stream" else "New stream")
                FlowsheetEditing.attachableStreams(fs, obj.id, port).forEach { add(it.id to it.tag) }
                if (attached != null) add(DISCONNECT to "Disconnect ${attached.tag}")
            }
            val side = if (port.side == PortSide.Inlet) "in" else "out"
            choice("${port.label} ($side)" + if (port.required) "" else " · optional", attached?.id,
                (if (attached != null) listOf(attached.id to attached.tag) else emptyList()) + options,
                buildJsonObject { put("op", "connect"); put("side", port.side.name); put("kind", port.kind.name); put("index", port.index) },
                placeholder = "Not connected")
        }
    }
}
