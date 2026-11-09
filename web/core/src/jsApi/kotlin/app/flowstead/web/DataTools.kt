/*
 * Flowstead web (D127): the Fluid package's compound and data tools, as the app's DataToolsSheet (D104, D106): a
 * compound from its structure (SMILES, Joback groups by hand, or a name looked up on PubChem), binary interaction
 * parameters regressed from measured VLE, and DWSIM's reaction finder. The requests, the PubChem address and parser,
 * and every edit are the app's core (PubChem, FlowsheetEditing); every property is the engine's (DWSIM's Joback class,
 * its bubble-point routines, its reaction tables), shown in the units the app shows them.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:OptIn(ExperimentalJsExport::class)

package app.flowstead.web

import app.flowstead.core.api.BinaryDiagramKinds
import app.flowstead.core.api.ReactionFinderRequest
import app.flowstead.core.api.ReactionFinderResult
import app.flowstead.core.api.RegressionParameters
import app.flowstead.core.api.StructureCompoundRequest
import app.flowstead.core.api.StructureCompoundResult
import app.flowstead.core.api.VlePoint
import app.flowstead.core.api.VleRegressionRequest
import app.flowstead.core.api.VleRegressionResult
import app.flowstead.core.compounds.PubChem
import app.flowstead.core.format.ValueFormat
import app.flowstead.core.model.BinaryParameter
import app.flowstead.core.model.EditRejected
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.FlowsheetEditing
import app.flowstead.core.model.FlowsheetJson
import app.flowstead.core.model.Hypothetical
import app.flowstead.core.model.Reaction
import app.flowstead.core.units.Quantity
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.Units
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

@JsExport
object DataTools {
    private fun fs(json: String): Flowsheet = FlowsheetJson.decodeFromString(Flowsheet.serializer(), json)
    private fun err(message: String) = buildJsonObject { put("error", message) }.toString()
    private fun out(f: Flowsheet) = buildJsonObject { put("flowsheet", FlowsheetJson.encodeToJsonElement(Flowsheet.serializer(), f)) }.toString()

    /** Joback's group names by DWSIM's number (1–41), for counting groups by hand (the app's JOBACK list). */
    private val JOBACK = listOf(
        "-CH3", "-CH2-", ">CH-", ">C<", "=CH2", "=CH-", "=C<", "=C=", "≡CH", "≡C-",
        "-CH2- (ring)", ">CH- (ring)", ">C< (ring)", "=CH- (ring)", "=C< (ring)",
        "-F", "-Cl", "-Br", "-I",
        "-OH (alcohol)", "-OH (phenol)", "-O- (non-ring)", "-O- (ring)", ">C=O (non-ring)", ">C=O (ring)", "O=CH- (aldehyde)",
        "-COOH (acid)", "-COO- (ester)", "=O (other)",
        "-NH2", ">NH (non-ring)", ">NH (ring)", ">N- (non-ring)", "-N= (non-ring)", "-N= (ring)", "=NH", "-CN", "-NO2",
        "-SH", "-S- (non-ring)", "-S- (ring)",
    )

    fun jobackGroups(): String = kotlinx.serialization.json.buildJsonArray {
        JOBACK.forEachIndexed { i, n -> addJsonObject { put("id", i + 1); put("name", n) } }
    }.toString()

    // ---------- compound from structure ----------

    /** PubChem's address for a name or CAS number (the app's PubChem.url: the structure only, no key needed). */
    fun pubChemUrl(query: String): String = PubChem.url(query)

    /** PubChem's answer as [{cid, title, formula, molecularWeight, smiles}], or {error} (its fault, or nothing found). */
    fun pubChemParse(query: String, body: String): String {
        val found = runCatching { PubChem.parse(body) }.getOrDefault(emptyList())
        if (found.isEmpty()) return err(PubChem.fault(body)?.let { "PubChem: $it" } ?: "PubChem found no compound called “${query.trim()}”")
        return kotlinx.serialization.json.buildJsonArray {
            found.take(3).forEach { c ->
                addJsonObject {
                    put("cid", c.cid); put("title", c.title); put("formula", c.formula); put("smiles", c.smiles)
                    put("label", "${c.title} · ${c.formula}${c.molecularWeight?.let { " · M ${ValueFormat.number(it, 2)}" } ?: ""} · CID ${c.cid}")
                }
            }
        }.toString()
    }

    /** The compound as the app builds it: a SMILES, else the group counts ({"1": 2, …}); the known boiling point (K) optional. */
    private fun compound(name: String, smiles: String, groupsJson: String, nbpKelvin: Double): Hypothetical {
        val n = name.trim()
        if (n.isEmpty()) throw EditRejected("Give the component a name")
        val groups = FlowsheetJson.parseToJsonElement(groupsJson.ifBlank { "{}" }).jsonObject
            .mapNotNull { (k, v) -> val c = v.jsonPrimitive.content.toDoubleOrNull()?.toInt() ?: 0; k.toIntOrNull()?.takeIf { it in 1..JOBACK.size && c > 0 }?.let { it to c } }.toMap()
        val s = smiles.trim()
        if (s.isEmpty() && groups.isEmpty()) throw EditRejected("Give a SMILES structure or count the groups")
        return Hypothetical(name = n, normalBoilingPoint = nbpKelvin.takeIf { it.isFinite() && it > 0 }, smiles = s.ifEmpty { null }, jobackGroups = if (s.isEmpty()) groups else null)
    }

    /** {method: "structureCompound", params} or {error}. [nbpKelvin]: NaN when not given. */
    fun structureRequest(name: String, smiles: String, groupsJson: String, nbpKelvin: Double): String = try {
        buildJsonObject { put("method", "structureCompound"); put("params", FlowsheetJson.encodeToJsonElement(StructureCompoundRequest.serializer(), StructureCompoundRequest(compound(name, smiles, groupsJson, nbpKelvin)))) }.toString()
    } catch (e: IllegalArgumentException) { err(e.message ?: "Not allowed") }

    /** Joback's estimate as the app shows it: formula and M, the groups found, then the constants. */
    fun structureReport(resultJson: String): String {
        val r = FlowsheetJson.decodeFromString(StructureCompoundResult.serializer(), resultJson)
        val rep = Report(UnitContext())
        rep.heading("${r.formula} · M ${ValueFormat.number(r.molecularWeight, 3)}")
        rep.note(r.groups.joinToString(" · ") { "${it.count} × ${it.name}" })
        rep.kv(listOf(
            Triple("Normal boiling point", ValueFormat.number(r.normalBoilingPoint, 2), "K"),
            Triple("Critical temperature", ValueFormat.number(r.criticalTemperature, 2), "K"),
            Triple("Critical pressure", ValueFormat.number(r.criticalPressure / 1e5, 3), "bar"),
            Triple("Critical volume", ValueFormat.number(r.criticalVolume * 1e6, 1), "cm³/mol"),
            Triple("Acentric factor", ValueFormat.number(r.acentricFactor, 4), ""),
            Triple("ΔHf° (ideal gas, 25 °C)", ValueFormat.number(r.enthalpyOfFormation / 1000, 2), "kJ/mol"),
            Triple("ΔGf° (ideal gas, 25 °C)", ValueFormat.number(r.gibbsEnergyOfFormation / 1000, 2), "kJ/mol"),
        ))
        r.warnings.forEach { rep.note(it, "warn") }
        return rep.json()
    }

    /** Adds the compound to the components as a hypothetical defined by its structure (the app's saveHypothetical). */
    fun saveStructure(flowsheetJson: String, name: String, smiles: String, groupsJson: String, nbpKelvin: Double): String = try {
        out(FlowsheetEditing.upsertHypothetical(fs(flowsheetJson), compound(name, smiles, groupsJson, nbpKelvin)))
    } catch (e: IllegalArgumentException) { err(e.message ?: "Not saved") }

    // ---------- VLE regression ----------

    private fun regressionKind(f: Flowsheet): String {
        val pkg = f.propertyPackage.orEmpty()
        return when { "NRTL" in pkg -> RegressionParameters.NRTL; "UNIQUAC" in pkg -> RegressionParameters.UNIQUAC; else -> RegressionParameters.KIJ }
    }

    /** What the regression fits with this package, the components, and the temperature and pressure units. */
    fun regressionSetup(flowsheetJson: String): String {
        val f = fs(flowsheetJson)
        val k = regressionKind(f)
        return buildJsonObject {
            put("fits", if (k == RegressionParameters.KIJ) "the pair's kij" else "the pair's ${k.uppercase()} A12 and A21")
            put("package", f.propertyPackage.orEmpty())
            putJsonArray("compounds") { f.compounds.forEach { add(JsonPrimitive(it)) } }
            put("tUnit", displayUnits.unitFor(Quantity.Temperature).id); put("pUnit", displayUnits.unitFor(Quantity.Pressure).id)
            putJsonArray("tUnits") { Units.forQuantity(Quantity.Temperature).forEach { u -> addJsonObject { put("id", u.id); put("symbol", u.symbol) } } }
            putJsonArray("pUnits") { Units.forQuantity(Quantity.Pressure).forEach { u -> addJsonObject { put("id", u.id); put("symbol", u.symbol) } } }
        }.toString()
    }

    /** "x, y, value" per line (y optional), as the app's parsePoints; null if a line doesn't read. */
    private fun parsePoints(text: String): List<Triple<Double, Double?, Double>>? {
        val rows = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        return rows.map { line ->
            val parts = line.split(Regex("[,;\\t ]+")).filter { it.isNotEmpty() }.map { it.replace(',', '.').toDoubleOrNull() ?: return null }
            when (parts.size) { 2 -> Triple(parts[0], null, parts[1]); 3 -> Triple(parts[0], parts[1], parts[2]); else -> return null }
        }
    }

    /**
     * {method: "regressVle", params} or {error}. [kind] "txy" (the fixed value a pressure, each point's a temperature) or
     * "pxy"; [fixed] in [fixedUnit], each point's value in [valueUnit].
     */
    fun regressionRequest(flowsheetJson: String, first: String, second: String, kind: String, fixed: String, fixedUnit: String, valueUnit: String, data: String): String = try {
        val f = fs(flowsheetJson)
        val ctx = UnitContext(f.atmosphericPressurePa)
        if (first !in f.compounds || second !in f.compounds || first == second) throw EditRejected("Choose two different components")
        val txy = kind == BinaryDiagramKinds.TXY
        val fu = Units.byIdOrNull(fixedUnit) ?: throw EditRejected("Unknown unit")
        val vu = Units.byIdOrNull(valueUnit) ?: throw EditRejected("Unknown unit")
        val fixedSi = fu.toSi(fixed.trim().replace(',', '.').toDoubleOrNull() ?: throw EditRejected("Give the ${if (txy) "pressure" else "temperature"}"), ctx)
        val pts = parsePoints(data) ?: throw EditRejected("Each line needs x and the ${if (txy) "temperature" else "pressure"}, with y between them if measured")
        if (pts.size < 2) throw EditRejected("Give at least two measured points")
        if (pts.any { it.first !in 0.0..1.0 || (it.second != null && it.second!! !in 0.0..1.0) }) throw EditRejected("x and y are mole fractions, between 0 and 1")
        val points = pts.map { (x, y, v) -> val si = vu.toSi(v, ctx); if (txy) VlePoint(x, y, si, fixedSi) else VlePoint(x, y, fixedSi, si) }
        val req = VleRegressionRequest(f, first, second, if (txy) BinaryDiagramKinds.TXY else BinaryDiagramKinds.PXY, points, regressionKind(f))
        buildJsonObject { put("method", "regressVle"); put("params", FlowsheetJson.encodeToJsonElement(VleRegressionRequest.serializer(), req)) }.toString()
    } catch (e: IllegalArgumentException) { err(e.message ?: "Not allowed") }

    /** The fit as the app shows it: the parameters, the deviations before and after, each point measured and fitted. {report, parameter}. */
    fun regressionReport(flowsheetJson: String, kind: String, valueUnit: String, resultJson: String): String {
        val f = fs(flowsheetJson)
        val r = FlowsheetJson.decodeFromString(VleRegressionResult.serializer(), resultJson)
        val ctx = UnitContext(f.atmosphericPressurePa)
        val vu = Units.byIdOrNull(valueUnit) ?: displayUnits.unitFor(if (kind == BinaryDiagramKinds.TXY) Quantity.Temperature else Quantity.Pressure)
        val p = r.parameter
        val rep = Report(ctx)
        rep.heading(p.a12?.let { a12 -> "A12 = ${ValueFormat.number(a12, 2)} cal/mol, A21 = ${ValueFormat.number(p.a21 ?: 0.0, 2)} cal/mol" + (p.alpha12?.let { ", α = ${ValueFormat.number(it, 2)}" } ?: "") }
            ?: "kij = ${ValueFormat.number(p.kij, 5)}")
        val v = if (kind == BinaryDiagramKinds.TXY) "T" else "P"
        rep.note("Average deviation in $v: ${ValueFormat.number(r.deviationBefore, 3)} % before, ${ValueFormat.number(r.deviationAfter, 3)} % after" +
            (r.vapourDeviationAfter?.let { "; in y: ${ValueFormat.number(r.vapourDeviationBefore ?: 0.0, 4)} → ${ValueFormat.number(it, 4)}" } ?: "") + " (${r.evaluations} evaluations).")
        rep.table(listOf("x", "Measured $v (${vu.symbol})", "Fitted $v (${vu.symbol})", "y fitted"), r.after.map { pt ->
            listOf(ValueFormat.number(pt.x, 4), ValueFormat.number(vu.fromSi(pt.measured, ctx), 2), ValueFormat.number(vu.fromSi(pt.calculated, ctx), 2), pt.yCalculated?.let { ValueFormat.number(it, 4) } ?: "—")
        })
        r.warnings.forEach { rep.note(it, "warn") }
        return buildJsonObject {
            put("report", FlowsheetJson.parseToJsonElement(rep.json()))
            put("parameter", FlowsheetJson.encodeToJsonElement(BinaryParameter.serializer(), p))
        }.toString()
    }

    /** Puts the fitted parameters in the fluid (the app's saveBinaryParameter). */
    fun saveParameter(flowsheetJson: String, parameterJson: String): String = try {
        out(FlowsheetEditing.upsertBinaryParameter(fs(flowsheetJson), FlowsheetJson.decodeFromString(BinaryParameter.serializer(), parameterJson)))
    } catch (e: IllegalArgumentException) { err(e.message ?: "Not saved") }

    // ---------- reaction finder ----------

    fun reactionsRequest(flowsheetJson: String): String {
        val f = fs(flowsheetJson)
        if (f.compounds.size < 2) return err("Add at least two components first")
        return buildJsonObject { put("method", "findReactions"); put("params", FlowsheetJson.encodeToJsonElement(ReactionFinderRequest.serializer(), ReactionFinderRequest(f))) }.toString()
    }

    /** What DWSIM's finder suggests: [{category, type, name, equation, note, added, reaction}], with its warnings. */
    fun reactionsList(flowsheetJson: String, resultJson: String): String {
        val f = fs(flowsheetJson)
        val r = FlowsheetJson.decodeFromString(ReactionFinderResult.serializer(), resultJson)
        return buildJsonObject {
            putJsonArray("reactions") {
                r.reactions.forEach { s ->
                    addJsonObject {
                        put("category", s.category.replaceFirstChar { it.uppercase() }); put("type", s.reaction.type.label)
                        put("name", s.name); put("equation", s.reaction.equation()); s.note?.let { put("note", it) }
                        put("added", f.reactions.any { it.type == s.reaction.type && it.stoichiometry == s.reaction.stoichiometry }) // added under a fresh id (addSuggestedReaction)
                        put("reaction", FlowsheetJson.encodeToJsonElement(Reaction.serializer(), s.reaction))
                    }
                }
            }
            putJsonArray("warnings") { r.warnings.forEach { add(JsonPrimitive(it)) } }
        }.toString()
    }

    /** Adds a suggested reaction to the flowsheet (the app's addReaction): reactors can then choose it. */
    fun addReaction(flowsheetJson: String, reactionJson: String): String = try {
        out(FlowsheetEditing.addSuggestedReaction(fs(flowsheetJson), FlowsheetJson.decodeFromString(Reaction.serializer(), reactionJson)))
    } catch (e: IllegalArgumentException) { err(e.message ?: "Not added") }
}
