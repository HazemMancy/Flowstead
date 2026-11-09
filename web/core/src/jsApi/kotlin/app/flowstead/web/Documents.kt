/*
 * Flowstead web (D114): the app's project documents. The heat and material balance and the equipment data sheets are
 * built by the app's core (HeatMaterialBalance, DataSheets) and written as the app's workbook (DataSheetWorkbook); the
 * same sheets go to the page as JSON for its print layout (the app's DataSheetPdf, as a printable page the browser
 * saves as PDF). The report is the app's Markdown report. Every value is the engine's or the user's, as in the app.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:OptIn(ExperimentalJsExport::class)

package app.flowstead.web

import app.flowstead.core.api.SolveResult
import app.flowstead.core.export.DataSheet
import app.flowstead.core.export.DataSheetWorkbook
import app.flowstead.core.export.DataSheets
import app.flowstead.core.export.HeatMaterialBalance
import app.flowstead.core.export.Report
import app.flowstead.core.model.DocumentInfo
import app.flowstead.core.model.EditRejected
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.FlowsheetEditing
import app.flowstead.core.model.FlowsheetJson
import app.flowstead.core.model.ResultView
import app.flowstead.core.model.StoredSolve
import app.flowstead.core.model.displayName
import app.flowstead.core.model.modelHash
import app.flowstead.core.model.obj
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** What the web's documents say made them (the app writes its own version here). */
const val WEB_VERSION = "Web 1.13"

@JsExport
object Documents {
    private fun fs(json: String): Flowsheet = FlowsheetJson.decodeFromString(Flowsheet.serializer(), json)
    private fun solve(json: String?, hash: String, at: Double): StoredSolve? =
        json?.takeIf { it.isNotBlank() && it != "null" }?.let { StoredSolve(majorOnly(FlowsheetJson.decodeFromString(SolveResult.serializer(), it)), hash, at.toLong()) }

    /** "Flowstead Web 1.10.2 · DWSIM 10": the engine by its major version only (owner's choice, D129). */
    private fun footer(s: StoredSolve?) = "Flowstead $WEB_VERSION" + (s?.result?.let { " · DWSIM ${it.dwsimVersion.substringBefore('.')}" } ?: "")

    private fun sheets(kind: String, projectName: String, f: Flowsheet, s: StoredSolve?, today: String, ids: List<String>, forPrint: Boolean): List<DataSheet> = when (kind) {
        "hmb" -> HeatMaterialBalance.build(projectName, f, s, displayUnits, today, WEB_VERSION, if (forPrint) HeatMaterialBalance.STREAMS_PER_PDF_SHEET else Int.MAX_VALUE)
        "datasheets" -> {
            val targets = if (ids.isEmpty()) f.objects.filter { DataSheets.isEquipment(it.type) } else ids.mapNotNull { f.obj(it) }
            if (targets.isEmpty()) throw EditRejected("There is no equipment to make a data sheet for")
            targets.map { DataSheets.build(projectName, f, s, displayUnits, it, today, WEB_VERSION) }
        }
        else -> throw EditRejected("Unknown document")
    }

    private fun idList(json: String): List<String> = FlowsheetJson.parseToJsonElement(json.ifBlank { "[]" }).let { e ->
        (e as? kotlinx.serialization.json.JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
    }

    /** The equipment that gets a data sheet: [{id, tag, type}]. */
    fun equipment(flowsheetJson: String): String = buildJsonArray {
        fs(flowsheetJson).objects.filter { DataSheets.isEquipment(it.type) }.forEach { o -> addJsonObject { put("id", o.id); put("tag", o.tag); put("type", o.type.displayName()) } }
    }.toString()

    /**
     * [kind] "hmb" or "datasheets" ([idsJson]: equipment ids, [] for all) as the sheets the page prints:
     * {sheets: [{title, tag, heading, service, kind, paper, info, documentNumber, revision, revisionDescription, date,
     * unitSystem, footer, sections: [{title, columns, lines: [{description, unit, values, bold}]}], notes}], stale,
     * solved} or {error}.
     */
    fun sheetsJson(kind: String, projectName: String, flowsheetJson: String, solveJson: String?, solvedHash: String, solvedAt: Double, today: String, idsJson: String): String = try {
        val f = fs(flowsheetJson)
        val s = solve(solveJson, solvedHash, solvedAt)
        val list = sheets(kind, projectName, f, s, today, idList(idsJson), forPrint = true)
        buildJsonObject {
            put("solved", s != null); put("stale", ResultView(s, f.modelHash()).stale)
            putJsonArray("sheets") {
                list.forEach { d ->
                    addJsonObject {
                        put("title", d.title); put("tag", d.tag); put("heading", d.heading); put("service", d.service); put("kind", d.kind)
                        put("paper", d.paper.name); put("documentNumber", d.documentNumber); put("revision", d.revision)
                        put("revisionDescription", d.revisionDescription); put("date", d.date); put("unitSystem", d.unitSystem)
                        put("footer", footer(s).takeIf { s != null } ?: d.footer)
                        put("info", FlowsheetJson.encodeToJsonElement(DocumentInfo.serializer(), d.info))
                        putJsonArray("sections") {
                            d.sections.forEach { sec ->
                                addJsonObject {
                                    put("title", sec.title)
                                    putJsonArray("columns") { sec.columns.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                                    putJsonArray("lines") {
                                        sec.lines.forEach { l -> addJsonObject { put("description", l.description); put("unit", l.unit); put("bold", l.bold); putJsonArray("values") { l.values.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } } } }
                                    }
                                }
                            }
                        }
                        putJsonArray("notes") { d.notes.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                    }
                }
            }
        }.toString()
    } catch (e: IllegalArgumentException) { buildJsonObject { put("error", e.message ?: "Not possible") }.toString() }

    /** The same sheets as the app's Excel workbook (one sheet per document, the balance on one sheet). */
    fun sheetsXlsx(kind: String, projectName: String, flowsheetJson: String, solveJson: String?, solvedHash: String, solvedAt: Double, today: String, idsJson: String): ByteArray {
        val f = fs(flowsheetJson)
        return DataSheetWorkbook.write(sheets(kind, projectName, f, solve(solveJson, solvedHash, solvedAt), today, idList(idsJson), forPrint = false), null)
    }

    /** The app's project report, in Markdown: the flowsheet, its specifications and every result. */
    fun reportMarkdown(projectName: String, flowsheetJson: String, solveJson: String?, solvedHash: String, solvedAt: Double, generated: String): String {
        val f = fs(flowsheetJson)
        return Report.markdown(projectName, f, ResultView(solve(solveJson, solvedHash, solvedAt), f.modelHash()), displayUnits, generated, WEB_VERSION)
    }

    /** The project's title-block data (company, project, numbers, people), as the app's Document info. */
    fun documentInfo(flowsheetJson: String): String = FlowsheetJson.encodeToString(DocumentInfo.serializer(), fs(flowsheetJson).documentInfo ?: DocumentInfo())

    fun setDocumentInfo(flowsheetJson: String, infoJson: String): String = try {
        val info = FlowsheetJson.decodeFromString(DocumentInfo.serializer(), infoJson)
        buildJsonObject { put("flowsheet", FlowsheetJson.encodeToJsonElement(Flowsheet.serializer(), FlowsheetEditing.setDocumentInfo(fs(flowsheetJson), info))) }.toString()
    } catch (e: Exception) { buildJsonObject { put("error", e.message ?: "Not saved") }.toString() }

    /** What the PFD's title block shows: the app's PfdPdf.Header. */
    fun pfdHeader(projectName: String, flowsheetJson: String, solveJson: String?, solvedHash: String, solvedAt: Double, today: String): String {
        val f = fs(flowsheetJson)
        val s = solve(solveJson, solvedHash, solvedAt)
        return buildJsonObject {
            put("projectName", projectName); put("date", today); put("units", displayUnits.name)
            f.propertyPackage?.let { put("propertyPackage", it) }
            put("footer", footer(s)); put("stale", ResultView(s, f.modelHash()).stale); put("solved", s != null)
            put("info", FlowsteadJsonInfo.of(f))
        }.toString()
    }
}

private object FlowsteadJsonInfo {
    fun of(f: Flowsheet): JsonObject = FlowsheetJson.encodeToJsonElement(DocumentInfo.serializer(), f.documentInfo ?: DocumentInfo()).jsonObject
}

/** The engine in documents by its major version, "DWSIM 10" (owner's choice, D129); the stored solve keeps the full one. */
internal fun majorOnly(r: SolveResult): SolveResult = r.copy(dwsimVersion = r.dwsimVersion.substringBefore('.'))
