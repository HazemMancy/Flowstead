/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package app.flowstead.web

import app.flowstead.core.api.CompoundBasis
import app.flowstead.core.api.HypotheticalProperties
import app.flowstead.core.format.ValueFormat
import app.flowstead.core.model.CompositionBasis
import app.flowstead.core.model.EditRejected
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.FlowsheetEditing
import app.flowstead.core.model.FlowsheetJson
import app.flowstead.core.model.Hypothetical
import app.flowstead.core.model.obj
import app.flowstead.core.units.EngUnit
import app.flowstead.core.units.Quantity
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.Units
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The fluid's hypothetical components (the app's HypotheticalSheet, D44/D104) and composition bases (the app's
 * CompositionEditor, D78), for the page. Values go to and from the page in display units ({value, unit}); the
 * flowsheet keeps SI and mole fractions, as the app does.
 */
@JsExport
object Fluids {
    private fun fs(json: String): Flowsheet = FlowsheetJson.decodeFromString(Flowsheet.serializer(), json)
    private fun err(message: String) = buildJsonObject { put("error", message) }.toString()

    /** Critical pressures are absolute: a gauge display unit is shown as its absolute twin, as in the app. */
    private fun absolute(unit: EngUnit): EngUnit = when (unit) {
        Units.barg -> Units.bara
        Units.psig -> Units.psia
        Units.kPag -> Units.kPa
        else -> unit
    }
    private fun trim(t: String) = if ('.' in t) t.trimEnd('0').trimEnd('.') else t
    private val tUnit get() = displayUnits.unitFor(Quantity.Temperature)
    private val pUnit get() = absolute(displayUnits.unitFor(Quantity.Pressure))

    /** The fields of the hypothetical form: {key, label, unit, hint}; the unit is the one the page sends values in. */
    private val fields get() = listOf(
        Triple("nbp", "Normal boiling point", tUnit) to "",
        Triple("mw", "Molecular weight", Units.kgPerKmol) to "",
        Triple("sg", "Specific gravity (60 °F/60 °F)", Units.ratio) to "estimated if empty",
        Triple("tc", "Critical temperature", tUnit) to "Riazi–Daubert if empty",
        Triple("pc", "Critical pressure", pUnit) to "Riazi–Daubert if empty",
        Triple("w", "Acentric factor", Units.ratio) to "fitted to the boiling point if empty",
    )

    /**
     * The hypotheticals of the flowsheet and the form to edit them: {fields: [{key, label, unit, symbol, hint}],
     * hypotheticals: [{name, byStructure, values: {key: text}}]}. Values are in the form's units, blank if not given.
     */
    fun hypotheticals(flowsheetJson: String): String {
        val f = fs(flowsheetJson)
        val ctx = UnitContext(f.atmosphericPressurePa)
        return buildJsonObject {
            putJsonArray("fields") {
                fields.forEach { (t, hint) ->
                    addJsonObject {
                        put("key", t.first); put("label", t.second); put("unit", t.third.id); put("symbol", t.third.symbol); put("hint", hint)
                        // Any unit of the quantity (critical pressures are absolute, so no gauge units): the form lets the user type in another.
                        putJsonArray("units") { choices(t.third).forEach { u -> addJsonObject { put("id", u.id); put("symbol", u.symbol) } } }
                    }
                }
            }
            putJsonArray("hypotheticals") {
                f.hypotheticals.forEach { h ->
                    addJsonObject {
                        put("name", h.name); put("byStructure", h.byStructure)
                        putJsonObject("values") {
                            listOf("nbp" to h.normalBoilingPoint, "mw" to h.molecularWeight, "sg" to h.specificGravity, "tc" to h.criticalTemperature, "pc" to h.criticalPressure, "w" to h.acentricFactor)
                                .forEach { (k, si) -> put(k, si?.let { v -> val u = fields.first { it.first.first == k }.first.third; trim(ValueFormat.number(u.fromSi(v, ctx), 6).replace(",", "")) } ?: "") }
                        }
                    }
                }
            }
        }.toString()
    }

    /** The units a field can be typed in: its quantity's units, without gauge pressures. Quantities with one unit offer none. */
    private fun choices(unit: EngUnit): List<EngUnit> =
        Units.forQuantity(unit.quantity).filter { it != Units.barg && it != Units.psig && it != Units.kPag }.takeIf { it.size > 1 } ?: emptyList()

    /** A value re-expressed in another unit of the same quantity (the form's unit menus); {value} or {error}. */
    fun convert(value: Double, fromId: String, toId: String, atmosphericPa: Double): String = try {
        val from = Units.byId(fromId); val to = Units.byId(toId)
        require(from.quantity == to.quantity) { "Not the same quantity" }
        val ctx = UnitContext(atmosphericPa)
        buildJsonObject { put("value", to.fromSi(from.toSi(value, ctx), ctx)) }.toString()
    } catch (e: IllegalArgumentException) { err(e.message ?: "Cannot convert") }

    /** The hypothetical the page's form describes, in SI; the structure (SMILES, groups) of one of that name is kept. */
    private fun parse(f: Flowsheet, inputJson: String): Hypothetical {
        val o = FlowsheetJson.parseToJsonElement(inputJson).jsonObject
        val ctx = UnitContext(f.atmosphericPressurePa)
        val name = (o["name"] as? JsonPrimitive)?.content?.trim().orEmpty()
        fun v(k: String): Double? {
            val text = (o[k] as? JsonPrimitive)?.content?.trim()?.replace(',', '.').orEmpty()
            if (text.isEmpty()) return null
            val x = text.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw EditRejected("Not a number: $text")
            val default = fields.first { it.first.first == k }.first.third
            // The unit the user picked for this field, if it is one of the field's own quantity.
            val unit = (o["${k}_unit"] as? JsonPrimitive)?.content?.let { Units.byIdOrNull(it) }?.takeIf { it.quantity == default.quantity } ?: default
            return unit.toSi(x, ctx)
        }
        val old = f.hypothetical(name)
        val h = Hypothetical(
            name = name, normalBoilingPoint = v("nbp"), molecularWeight = v("mw"), specificGravity = v("sg"),
            criticalTemperature = v("tc"), criticalPressure = v("pc"), acentricFactor = v("w"),
            smiles = old?.smiles, jobackGroups = old?.jobackGroups,
        )
        h.problem()?.let { throw EditRejected(it) }
        return h
    }

    /** The engine call that estimates the hypothetical's constants: {method, params} or {error}. */
    fun estimateRequest(flowsheetJson: String, inputJson: String): String = try {
        val h = parse(fs(flowsheetJson), inputJson)
        buildJsonObject { put("method", "estimateHypotheticals"); put("params", FlowsheetJson.encodeToJsonElement(ListSerializer(Hypothetical.serializer()), listOf(h))) }.toString()
    } catch (e: IllegalArgumentException) { err(e.message ?: "Not valid") }

    /** The engine's constants for the hypothetical, as the app's EstimateCard: "· est." marks what it estimated. */
    fun estimateReport(flowsheetJson: String, resultJson: String): String {
        val f = fs(flowsheetJson)
        val p = FlowsheetJson.decodeFromString(ListSerializer(HypotheticalProperties.serializer()), resultJson).single()
        val rep = Report(UnitContext(f.atmosphericPressurePa))
        fun row(label: String, key: String, si: Double, u: EngUnit) =
            Triple(label + if (key in p.estimated) "  · est." else "", ValueFormat.number(u.fromSi(si, rep.ctx), u.decimals), u.symbol)
        rep.heading("Engine values")
        rep.kv(
            listOf(
                row("Normal boiling point", "normalBoilingPoint", p.normalBoilingPoint, tUnit),
                row("Molecular weight", "molecularWeight", p.molecularWeight, Units.kgPerKmol),
                row("Specific gravity", "specificGravity", p.specificGravity, Units.ratio),
                row("Critical temperature", "criticalTemperature", p.criticalTemperature, tUnit),
                row("Critical pressure", "criticalPressure", p.criticalPressure, pUnit),
                row("Acentric factor", "acentricFactor", p.acentricFactor, Units.ratio),
                row("Watson K", "", p.watsonK, Units.ratio),
            ),
        )
        if (p.formula.isNotBlank()) rep.note("Approximate formula ${p.formula}")
        rep.note("The acentric factor is fitted so Peng–Robinson boils the component at its normal boiling point, and the liquid-density parameters so it matches the specific gravity.")
        return rep.json()
    }

    /** Adds or replaces the hypothetical and puts it in the components (FlowsheetEditing.upsertHypothetical). */
    fun saveHypothetical(flowsheetJson: String, inputJson: String): String = try {
        val f = fs(flowsheetJson)
        val next = FlowsheetEditing.upsertHypothetical(f, parse(f, inputJson))
        buildJsonObject { put("flowsheet", FlowsheetJson.encodeToJsonElement(Flowsheet.serializer(), next)) }.toString()
    } catch (e: IllegalArgumentException) { err(e.message ?: "Not saved") }

    // ---------- composition bases (D78) ----------

    /** Every basis the app offers: [{id, label, total, needsEngineData, percent}]. */
    fun compositionBases(): String = buildJsonArray {
        CompositionBasis.entries.forEach { b -> addJsonObject { put("id", b.name); put("label", b.label); put("total", b.total); put("needsEngineData", b.needsEngineData); put("percent", b.percent) } }
    }.toString()

    private fun data(dataJson: String): Map<String, CompoundBasis>? = dataJson.takeIf { it.isNotBlank() && it != "null" }
        ?.let { FlowsheetJson.decodeFromString(ListSerializer(CompoundBasis.serializer()), it).associateBy { c -> c.name } }

    /**
     * Stream [id]'s composition shown in [basis]: {values: {compound: number}} (empty when none is given), or
     * {missing: [..]} when the engine gave no molecular weight or liquid density for some compound. [dataJson] is the
     * engine's compoundBasis answer (null for the mole bases). Mole bases show what was typed, even unnormalised.
     */
    fun compositionIn(flowsheetJson: String, id: String, basis: String, dataJson: String): String {
        val f = fs(flowsheetJson)
        val b = CompositionBasis.valueOf(basis)
        val d = data(dataJson)
        if (b.needsEngineData) b.missing(f.compounds, d).takeIf { it.isNotEmpty() }?.let { m -> return buildJsonObject { putJsonArray("missing") { m.forEach { add(JsonPrimitive(it)) } } }.toString() }
        val o = f.obj(id) ?: return err("No such object")
        val x = f.compounds.associateWith { o.composition[it] ?: 0.0 }
        val shown = if (x.values.all { it == 0.0 }) emptyMap() else if (b.needsEngineData) b.fromMole(x, d).orEmpty() else x.mapValues { it.value * b.total }
        return buildJsonObject {
            putJsonObject("values") { shown.forEach { (c, v) -> if (v > 0 || c in o.composition) put(c, v) } }
        }.toString()
    }

    /**
     * Mole fractions from values typed in [basis] ({compound: text}); mass and volume are normalised (only their ratios
     * define the mixture), mole bases keep their sum as typed. Used by Worksheets.apply's composition op.
     */
    internal fun toMole(basis: CompositionBasis, entered: Map<String, Double>, data: JsonElement?): Map<String, Double> {
        if (!basis.needsEngineData) return basis.toMole(entered, null)!!
        val d = data?.let { data(it.toString()) }
        val missing = basis.missing(entered.keys.toList(), d)
        if (missing.isNotEmpty()) throw EditRejected("The engine has no ${if (basis.kind == CompositionBasis.Kind.Volume) "liquid density" else "molecular weight"} for ${missing.joinToString()}: use another basis")
        return basis.toMole(entered, d)!!
    }
}
