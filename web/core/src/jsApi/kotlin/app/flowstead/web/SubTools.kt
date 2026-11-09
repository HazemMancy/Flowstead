/*
 * Flowstead web (D132): sub-flowsheets for the page. Editing (add, group, match boundary streams, own fluid package)
 * and the solve plan the page's engine client follows: one flat solve when every sub-flowsheet uses the main package,
 * else one solve a region with the boundary streams carried across (Subflowsheets).
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:OptIn(ExperimentalJsExport::class)

package app.flowstead.web

import app.flowstead.core.api.ObjectResult
import app.flowstead.core.api.SolveResult
import app.flowstead.core.model.EditRejected
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.FlowsheetJson
import app.flowstead.core.model.Spec
import app.flowstead.core.model.SubflowsheetSolve
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import app.flowstead.core.model.obj
import app.flowstead.core.model.Region
import app.flowstead.core.model.SubFluid
import app.flowstead.core.model.Subflowsheets
import app.flowstead.core.model.Transfer
import app.flowstead.core.model.TransferBasis
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

@JsExport
object SubTools {
    private fun fs(json: String): Flowsheet = FlowsheetJson.decodeFromString(Flowsheet.serializer(), json)
    private fun out(f: Flowsheet) = FlowsheetJson.encodeToString(Flowsheet.serializer(), f)
    private fun ok(f: Flowsheet, extra: (kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = {}) =
        buildJsonObject { put("flowsheet", FlowsheetJson.parseToJsonElement(out(Subflowsheets.clean(f)))); extra() }.toString()
    private fun err(e: Throwable) = buildJsonObject { put("error", e.message ?: "Not allowed") }.toString()
    private inline fun edit(block: () -> String): String = try { block() } catch (e: IllegalArgumentException) { err(e) } catch (e: IllegalStateException) { err(e) }
    private fun lvl(level: String?) = level?.takeIf { it.isNotEmpty() }

    fun has(flowsheetJson: String): Boolean = Subflowsheets.has(fs(flowsheetJson))
    fun multiPackage(flowsheetJson: String): Boolean = Subflowsheets.multiPackage(fs(flowsheetJson))

    /** The blocks on [level] ("" for the main flowsheet): [{id, tag, x, y, own, package}]. */
    fun blocks(flowsheetJson: String, level: String): String {
        val f = fs(flowsheetJson)
        return kotlinx.serialization.json.buildJsonArray {
            Subflowsheets.blocksAt(f, lvl(level)).forEach { b ->
                addJsonObject {
                    put("id", b.id); put("tag", b.tag); put("x", b.x); put("y", b.y); put("own", b.fluid != null)
                    put("package", Subflowsheets.fluidAt(f, b.id).propertyPackage ?: "")
                    putJsonArray("ports") {
                        Subflowsheets.boundary(f, b.id).forEach { bd -> addJsonObject { put("inner", bd.stream.id); put("feed", bd.feed); bd.port?.outer?.let { put("outer", it) } } }
                    }
                }
            }
        }.toString()
    }

    /** Breadcrumb from the main flowsheet to [level]: [{id, tag}]. */
    fun path(flowsheetJson: String, level: String): String = kotlinx.serialization.json.buildJsonArray {
        Subflowsheets.path(fs(flowsheetJson), lvl(level)).forEach { addJsonObject { put("id", it.id); put("tag", it.tag) } }
    }.toString()

    /** The level an object or block is on ("" for the main flowsheet). */
    fun levelOf(flowsheetJson: String, id: String): String {
        val f = fs(flowsheetJson)
        return f.obj(id)?.parent ?: Subflowsheets.of(f, id)?.parent ?: ""
    }

    /** A block for its inspector: {id, tag, own, package, compounds, parentPackage, ports: [{inner, innerTag, feed, outer?, basis, candidates: [{id, tag}]}]}. */
    fun info(flowsheetJson: String, blockId: String): String {
        val f = fs(flowsheetJson)
        val b = Subflowsheets.of(f, blockId) ?: return "null"
        val fluid = Subflowsheets.fluidAt(f, b.id)
        val parentFluid = Subflowsheets.fluidAt(f, b.parent)
        return buildJsonObject {
            put("id", b.id); put("tag", b.tag); put("own", b.fluid != null)
            put("package", fluid.propertyPackage ?: ""); put("parentPackage", parentFluid.propertyPackage ?: "")
            putJsonArray("compounds") { fluid.compounds.forEach { add(JsonPrimitive(it)) } }
            put("differs", Subflowsheets.regionOf(f, b.id) != Subflowsheets.regionOf(f, b.parent))
            putJsonArray("canIn") { Subflowsheets.connectable(f, b.id, true).forEach { c -> addJsonObject { put("id", c.id); put("tag", c.tag) } } }
            putJsonArray("canOut") { Subflowsheets.connectable(f, b.id, false).forEach { c -> addJsonObject { put("id", c.id); put("tag", c.tag) } } }
            putJsonArray("ports") {
                Subflowsheets.boundary(f, b.id).forEach { bd ->
                    addJsonObject {
                        put("inner", bd.stream.id); put("innerTag", bd.stream.tag); put("feed", bd.feed); put("energy", bd.stream.type.name == "EnergyStream")
                        bd.port?.outer?.let { o -> put("outer", o); put("outerTag", f.obj(o)?.tag ?: "?") }
                        put("basis", bd.port?.basis ?: TransferBasis.TP)
                        putJsonArray("candidates") { Subflowsheets.outerCandidates(f, b.id, bd.stream.id).forEach { c -> addJsonObject { put("id", c.id); put("tag", c.tag) } } }
                    }
                }
            }
        }.toString()
    }

    fun add(flowsheetJson: String, level: String, x: Double, y: Double): String = edit {
        val (f, b) = Subflowsheets.add(fs(flowsheetJson), lvl(level), x, y)
        ok(f) { put("id", b.id) }
    }
    fun rename(flowsheetJson: String, id: String, tag: String): String = edit { ok(Subflowsheets.rename(fs(flowsheetJson), id, tag)) }
    fun move(flowsheetJson: String, id: String, x: Double, y: Double): String = edit { ok(Subflowsheets.move(fs(flowsheetJson), id, x, y)) }
    /** What an edit made while [level] was shown belongs to it: [afterJson] with new objects on no flowsheet put there. */
    fun adopt(beforeJson: String, afterJson: String, level: String): String =
        if (level.isEmpty()) afterJson else out(Subflowsheets.adopt(fs(beforeJson), fs(afterJson), level))

    fun place(flowsheetJson: String, objectId: String, level: String): String = edit { ok(Subflowsheets.place(fs(flowsheetJson), objectId, lvl(level))) }
    fun link(flowsheetJson: String, blockId: String, innerId: String, outerId: String): String =
        edit { ok(Subflowsheets.link(fs(flowsheetJson), blockId, innerId, outerId.takeIf { it.isNotEmpty() })) }
    /** A stream of the parent into ([feed]) or out of the block: the boundary stream inside is made and matched. */
    fun connect(flowsheetJson: String, blockId: String, outerId: String, feed: Boolean): String = edit {
        val (f, id) = Subflowsheets.connect(fs(flowsheetJson), blockId, outerId, feed)
        ok(f) { put("id", id) }
    }
    fun disconnect(flowsheetJson: String, blockId: String, innerId: String): String = edit { ok(Subflowsheets.disconnect(fs(flowsheetJson), blockId, innerId)) }

    fun setBasis(flowsheetJson: String, blockId: String, innerId: String, basis: String): String =
        edit { ok(Subflowsheets.setBasis(fs(flowsheetJson), blockId, innerId, basis)) }
    fun delete(flowsheetJson: String, id: String): String = edit { ok(Subflowsheets.delete(fs(flowsheetJson), id)) }
    fun ungroup(flowsheetJson: String, id: String): String = edit { ok(Subflowsheets.ungroup(fs(flowsheetJson), id)) }
    fun group(flowsheetJson: String, idsJson: String, x: Double, y: Double): String = edit {
        val ids = FlowsheetJson.parseToJsonElement(idsJson).jsonArray.map { it.jsonPrimitive.content }
        val (f, b) = Subflowsheets.group(fs(flowsheetJson), ids, x, y)
        ok(f) { put("id", b.id) }
    }

    /** Own package on (a copy of the parent's, to edit) or off (the parent's again). */
    fun setOwnFluid(flowsheetJson: String, blockId: String, own: Boolean): String = edit {
        val f = fs(flowsheetJson)
        val b = Subflowsheets.of(f, blockId) ?: throw EditRejected("That sub-flowsheet no longer exists")
        ok(Subflowsheets.setFluid(f, blockId, if (own) Subflowsheets.fluidAt(f, b.parent) else null))
    }

    /** The flowsheet a basis editor works on for [blockId]: the case with the block's fluid in the case's own fields. */
    fun fluidView(flowsheetJson: String, blockId: String): String {
        val f = fs(flowsheetJson)
        return out(Subflowsheets.withFluid(f, Subflowsheets.fluidAt(f, blockId)))
    }

    /** Writes the fluid fields of [editedJson] (from [fluidView], after editing) back into [blockId]'s own package. */
    fun setFluidFrom(flowsheetJson: String, blockId: String, editedJson: String): String = edit {
        ok(Subflowsheets.setFluid(fs(flowsheetJson), blockId, Subflowsheets.fluidOf(fs(editedJson))))
    }

    // ---------- the solve (D135): the app's own SubflowsheetSolve, the engine called through [solve] ----------

    /**
     * Solves a case with sub-flowsheets as the app does (SubflowsheetSolve): flat on one package, part by part on
     * several. [solve] is one engine solve (a flowsheet's JSON in, the engine's result JSON out). The promise gives the
     * result of the whole case, or fails with the reason.
     */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    fun solveCase(flowsheetJson: String, solve: (String) -> kotlin.js.Promise<String>): kotlin.js.Promise<String> =
        kotlinx.coroutines.GlobalScope.promise {
            val r = SubflowsheetSolve.solve(fs(flowsheetJson)) { f ->
                FlowsheetJson.decodeFromString(SolveResult.serializer(), solve(out(f)).await())
            }
            FlowsheetJson.encodeToString(SolveResult.serializer(), r)
        }

    /** The flowsheet a tool on one object sends: the part on that object's package (the case flattened on one package). */
    fun forObject(flowsheetJson: String, objectId: String): String = out(SubflowsheetSolve.forObject(fs(flowsheetJson), objectId.ifEmpty { null }))

    // ---------- the solve plan ----------

    /** The case for the engine when every sub-flowsheet uses the main package: one flat flowsheet. */
    fun flatten(flowsheetJson: String): String = out(Subflowsheets.flatten(fs(flowsheetJson)))

    /** A flat solve's result with each joined inner stream given its outer stream's result. */
    fun expand(flowsheetJson: String, resultJson: String): String {
        val r = FlowsheetJson.decodeFromString(SolveResult.serializer(), resultJson)
        return FlowsheetJson.encodeToString(SolveResult.serializer(), Subflowsheets.expand(fs(flowsheetJson), r))
    }
}
