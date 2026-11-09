/*
 * Flowstead web (D110): the flowsheet canvas. It draws what the app's FlowsheetCanvas draws (grid, connections, symbols,
 * ports, status badges, signal lines, tags, property tables), with the app's own Icons.kt and CanvasGeometry.kt
 * (copied at build time, unchanged), and applies the app's rules for moving and wiring. The page handles the pointer
 * and keyboard, and keeps the flowsheet: every edit comes back as a new flowsheet JSON in the app's format.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:OptIn(ExperimentalJsExport::class)

package app.flowstead.web

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import app.flowstead.core.api.ObjectStatus
import app.flowstead.core.api.SolveResult
import app.flowstead.core.model.AdjustKeys
import app.flowstead.core.model.DisplayValue
import app.flowstead.core.model.EditRejected
import app.flowstead.web.model.Subflowsheets
import kotlin.math.roundToInt
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.FlowsheetEditing
import app.flowstead.core.model.FlowsheetJson
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.Port
import app.flowstead.core.model.PortAlignment
import app.flowstead.core.model.PortKind
import app.flowstead.core.model.PortSide
import app.flowstead.core.model.Ports
import app.flowstead.core.model.PropertyTables
import app.flowstead.core.model.Readiness
import app.flowstead.core.model.ResultView
import app.flowstead.core.model.SetKeys
import app.flowstead.core.model.SimObject
import app.flowstead.core.model.SpecKey
import app.flowstead.core.model.SpecStatus
import app.flowstead.core.model.Specification
import app.flowstead.core.model.StoredSolve
import app.flowstead.core.model.StreamCopy
import app.flowstead.core.format.ValueFormat
import app.flowstead.core.model.displayName
import app.flowstead.core.model.isFeed
import app.flowstead.core.model.isLoop
import app.flowstead.core.model.modelHash
import app.flowstead.core.model.obj
import app.flowstead.core.model.streamAt
import app.flowstead.core.units.Quantity
import app.flowstead.core.units.UnitSet
import app.flowstead.core.units.Units
import app.flowstead.web.model.Clip
import app.flowstead.web.model.FlowsheetClipboard
import app.flowstead.ui.flowsheet.CanvasGeometry
import app.flowstead.ui.flowsheet.Hit
import app.flowstead.ui.flowsheet.IconPalette
import app.flowstead.ui.flowsheet.Viewport
import app.flowstead.ui.flowsheet.drawNozzles
import app.flowstead.ui.flowsheet.drawUnitIcon
import app.flowstead.ui.flowsheet.hitTest
import app.flowstead.ui.flowsheet.phaseColor
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** The app's colours (ui/theme: Theme.kt, ValueColors.kt, Brand), light and dark. */
internal class CanvasColors(dark: Boolean) {
    val surface = if (dark) Color(0xFF11233F) else Color(0xFFFFFFFF)
    val onSurface = if (dark) Color(0xFFF3F6FA) else Color(0xFF0B1930)
    val onSurfaceVariant = if (dark) Color(0xFF8A98A8) else Color(0xFF5B6878)
    val secondary = if (dark) Color(0xFF19BFAE) else Color(0xFF0E7C72)
    val outline = Color(0xFF8A98A8)
    val outlineVariant = if (dark) Color(0xFF263B5E) else Color(0xFFD3DBE5)
    val error = if (dark) Color(0xFFFF8A95) else Color(0xFFC23A4B)
    val converged = if (dark) Color(0xFF6FE0A1) else Color(0xFF1F8A4C)
    val underSpecified = if (dark) Color(0xFFF2B84B) else Color(0xFFB97E10)
    val vapour = if (dark) Color(0xFFF5A524) else Color(0xFFC77700)
    val liquid = if (dark) Color(0xFF22B8D6) else Color(0xFF0E7490)
    val energy = if (dark) Color(0xFFFF7A45) else Color(0xFFE8461A)
    val twoPhase = if (dark) Color(0xFFA78BFA) else Color(0xFF7C5CBF)
    val palette = IconPalette(fill = surface, stroke = onSurface, accent = secondary, vapour = vapour, liquid = liquid, energy = energy)
}

/** °C and bara, the rest SI: the units the web app shows, as the results workbook it exports. */
internal val WEB_DEFAULT_UNITS: UnitSet = UnitSet.SI.with(Quantity.Temperature, Units.C).with(Quantity.Pressure, Units.bara).copy(name = "SI (°C, bara)")

/** The units shown: the web default until the user picks a set (D121); every screen and document reads it when it draws. */
internal var displayUnits: UnitSet = WEB_DEFAULT_UNITS

@JsExport
class FlowsheetView {
    private var saved: Flowsheet = Flowsheet()
    /** Drawn lined up: arrows and units a few units off their nozzles are shown level (saved positions unchanged). */
    private var fs: Flowsheet = saved
    private var results: ResultView = ResultView.NONE
    private var status: Map<String, SpecStatus> = emptyMap()
    private var notReady: Map<String, String> = emptyMap()
    private var solve: StoredSolve? = null
    private var colors = CanvasColors(dark = true)
    private var vp = Viewport()

    /** The selected objects (D124), in the order chosen. The inspector shows one; several make a group to move, copy or delete. */
    private val selection = linkedSetOf<String>()
    /** The one selected object, or null when none or several are; setting it selects just that object. */
    var selectedId: String?
        get() = selection.singleOrNull()
        set(value) { selection.clear(); value?.let { selection.add(it) } }
    /** A group move in progress: how far the grabbed object has gone; every selected object follows. */
    private var groupDelta: Offset? = null
    /** A selection box in progress (screen units): its first corner and the pointer. */
    private var box: Pair<Offset, Offset>? = null
    /** Drawing for print (the PFD, D114): no grid, no selection, no status badges, as the app's PfdPdf. */
    var print: Boolean = false
    private var dragging: Pair<String, Offset>? = null
    private var dragStart: Offset = Offset.Zero
    private var wireFrom: Pair<String, Port>? = null
    private var draggingTable: Pair<String, Offset>? = null
    private var tableStart: Offset = Offset.Zero
    private var wire: Pair<Offset, Offset>? = null
    /** The flowsheet shown (D132): null the main one, else a sub-flowsheet's id. Only its objects and blocks are drawn. */
    private var level: String? = null
    /** A sub-flowsheet block being moved: its id and where its centre is. */
    private var blockDrag: Pair<String, Offset>? = null
    /** Where each property table was last drawn (screen), for hit-testing. */
    private val tableBoxes = mutableMapOf<String, Rect>()

    val zoom: Double get() = vp.zoom.toDouble()
    val offsetX: Double get() = vp.offset.x.toDouble()
    val offsetY: Double get() = vp.offset.y.toDouble()

    fun setDark(dark: Boolean) { colors = CanvasColors(dark) }

    /** The flowsheet to show and edit, in the app's JSON. Keeps the stored solve: its results go out of date when the model changes. */
    fun setFlowsheet(json: String) {
        saved = FlowsheetJson.decodeFromString(Flowsheet.serializer(), json)
        if (level != null && Subflowsheets.of(saved, level) == null) level = null
        fs = PortAlignment.align(levelView(saved))
        // A matched boundary stream is defined by its partner (D132): checked on the case flattened.
        val pairs = saved.subflowsheets.flatMap { b -> b.ports.mapNotNull { p -> p.outer?.let { p.inner to it } } }.toMap()
        val flat = Subflowsheets.flatten(saved)
        status = saved.objects.associate { o -> o.id to Specification.status(flat, flat.obj(pairs[o.id] ?: o.id) ?: o) }
        notReady = Readiness.of(flat).notReady.let { nr -> nr + pairs.mapNotNull { (i, o) -> nr[o]?.let { i to it } } }
        results = ResultView(solve, saved.modelHash())
        selection.retainAll(fs.objects.map { it.id }.toSet())
    }

    /** [f] as drawn on the current level: its objects, the connections between them and their tables. */
    private fun levelView(f: Flowsheet): Flowsheet {
        if (!Subflowsheets.has(f) && level == null) return f
        val objs = f.objects.filter { it.parent == level }
        val ids = objs.map { it.id }.toSet()
        return f.copy(objects = objs, connections = f.connections.filter { it.fromObject in ids && it.toObject in ids }, tables = f.tables.filter { it.objectId in ids })
    }

    /** Opens a sub-flowsheet ([id]) or, with "", the main flowsheet. */
    fun setLevel(id: String) {
        level = id.takeIf { it.isNotEmpty() && Subflowsheets.of(saved, it) != null }
        selection.clear()
        fs = PortAlignment.align(levelView(saved))
    }
    val currentLevel: String get() = level ?: ""

    private val blockSize = Size(128f, 80f)
    private fun blockRect(x: Double, y: Double) = Rect(Offset(x.toFloat() - blockSize.width / 2, y.toFloat() - blockSize.height / 2), blockSize)
    private fun blockCenter(b: app.flowstead.web.model.Subflowsheet) = blockDrag?.takeIf { it.first == b.id }?.second ?: Offset(b.x.toFloat(), b.y.toFloat())
    private fun blockAt(world: Offset) = Subflowsheets.blocksAt(saved, level).lastOrNull { b -> blockCenter(b).let { c -> blockRect(c.x.toDouble(), c.y.toDouble()) }.contains(world) }

    /** A solve's result (the engine's JSON) and the model hash taken when it ran; null clears it. */
    fun setSolve(resultJson: String?, solvedModelHash: String, solvedAtMillis: Double) {
        solve = resultJson?.let { StoredSolve(FlowsheetJson.decodeFromString(SolveResult.serializer(), it), solvedModelHash, solvedAtMillis.toLong()) }
        results = ResultView(solve, saved.modelHash())
    }

    val stale: Boolean get() = results.stale

    // ---------- viewport (screen units are CSS pixels; world units are the app's dp at zoom 1)

    fun setViewport(offsetX: Double, offsetY: Double, zoom: Double) {
        vp = Viewport(Offset(offsetX.toFloat(), offsetY.toFloat()), zoom.toFloat().coerceIn(CanvasGeometry.MIN_ZOOM, CanvasGeometry.MAX_ZOOM))
    }

    fun pan(dx: Double, dy: Double) { vp = vp.copy(offset = vp.offset + Offset(dx.toFloat(), dy.toFloat())) }

    fun zoomAround(screenX: Double, screenY: Double, factor: Double) { vp = vp.zoomAround(Offset(screenX.toFloat(), screenY.toFloat()), factor.toFloat()) }

    fun worldX(screenX: Double): Double = vp.toWorld(Offset(screenX.toFloat(), 0f), 1f).x.toDouble()
    fun worldY(screenY: Double): Double = vp.toWorld(Offset(0f, screenY.toFloat()), 1f).y.toDouble()

    /** Fits every object (with its tag) in a [width] × [height] view, no closer than zoom 1.5. */
    fun fit(width: Double, height: Double) {
        val boxes = fs.objects.map { CanvasGeometry.bounds(it) } + Subflowsheets.blocksAt(saved, level).map { blockRect(it.x, it.y) }
        if (boxes.isEmpty()) { vp = Viewport(Offset((width / 2).toFloat(), (height / 2).toFloat()), 1f); return }
        val l = boxes.minOf { it.left } - 32f
        val t = boxes.minOf { it.top } - 32f
        val r = boxes.maxOf { it.right } + 32f
        val b = boxes.maxOf { it.bottom } + 48f
        val z = minOf(width.toFloat() / (r - l), height.toFloat() / (b - t), 1.5f).coerceIn(CanvasGeometry.MIN_ZOOM, CanvasGeometry.MAX_ZOOM)
        vp = Viewport(Offset(width.toFloat() / 2 - (l + r) / 2 * z, height.toFloat() / 2 - (t + b) / 2 * z), z)
    }

    // ---------- hit-testing and gestures

    private fun hitAt(screenX: Double, screenY: Double): Hit = hitTest(fs, vp.toWorld(Offset(screenX.toFloat(), screenY.toFloat()), 1f), vp.zoom)

    /** What is under a screen point: {kind: "port"|"object"|"table"|"empty", id, port, free}. */
    fun hit(screenX: Double, screenY: Double): String {
        val table = tableBoxes.entries.lastOrNull { it.value.contains(Offset(screenX.toFloat(), screenY.toFloat())) }?.key
        if (table != null) return buildJsonObject { put("kind", "table"); put("id", table) }.toString()
        return when (val h = hitAt(screenX, screenY)) {
            is Hit.OnPort -> buildJsonObject {
                put("kind", "port"); put("id", h.objectId)
                put("free", fs.streamAt(h.objectId, h.port) == null)
                putJsonObject("port") { put("side", h.port.side.name); put("kind", h.port.kind.name); put("index", h.port.index); put("label", h.port.label) }
            }
            is Hit.OnObject -> buildJsonObject { put("kind", "object"); put("id", h.objectId) }
            Hit.Empty -> blockAt(vp.toWorld(Offset(screenX.toFloat(), screenY.toFloat()), 1f))?.let { b -> buildJsonObject { put("kind", "block"); put("id", b.id) } }
                ?: buildJsonObject { put("kind", "empty") }
        }.toString()
    }

    /**
     * A press that moved: on a free port it starts a wire; on an object (or a port already in use, which can't take
     * another line, so small symbols stay movable) it starts moving it. Returns "wire", "move" or "pan".
     */
    fun beginDrag(screenX: Double, screenY: Double): String {
        val world = vp.toWorld(Offset(screenX.toFloat(), screenY.toFloat()), 1f)
        dragStart = world
        tableBoxes.entries.lastOrNull { it.value.contains(Offset(screenX.toFloat(), screenY.toFloat())) }?.key?.let { id ->
            val t = fs.tables.firstOrNull { it.objectId == id } ?: return "pan"
            tableStart = Offset(t.dx.toFloat(), t.dy.toFloat())
            draggingTable = id to tableStart
            return "table"
        }
        when (val h = hitTest(fs, world, vp.zoom)) {
            is Hit.OnPort -> if (fs.streamAt(h.objectId, h.port) == null) {
                val o = fs.obj(h.objectId) ?: return "pan"
                wireFrom = h.objectId to h.port
                val at = CanvasGeometry.portPosition(o, h.port)
                wire = at to at
                return "wire"
            } else {
                dragging = h.objectId to CanvasGeometry.center(fs.obj(h.objectId) ?: return "pan")
                if (selection.size > 1 && h.objectId in selection) groupDelta = Offset.Zero
                return "move"
            }
            is Hit.OnObject -> {
                dragging = h.objectId to CanvasGeometry.center(fs.obj(h.objectId) ?: return "pan")
                if (selection.size > 1 && h.objectId in selection) groupDelta = Offset.Zero
                return "move"
            }
            Hit.Empty -> {
                val b = blockAt(world) ?: return "pan"
                blockDrag = b.id to Offset(b.x.toFloat(), b.y.toFloat())
                return "move"
            }
        }
    }

    fun dragTo(screenX: Double, screenY: Double) {
        val world = vp.toWorld(Offset(screenX.toFloat(), screenY.toFloat()), 1f)
        dragging?.let { (id, _) -> fs.obj(id)?.let { dragging = id to (CanvasGeometry.center(it) + (world - dragStart)) } }
        if (groupDelta != null) groupDelta = world - dragStart
        blockDrag?.let { (id, _) -> Subflowsheets.of(saved, id)?.let { b -> blockDrag = id to (Offset(b.x.toFloat(), b.y.toFloat()) + (world - dragStart)) } }
        wire?.let { (a, _) -> wire = a to world }
        draggingTable?.let { (id, _) -> draggingTable = id to (tableStart + (world - dragStart)) }
    }

    /**
     * Ends a move or a wire at a screen point. Returns {flowsheet} after the edit, {error} when the app's rules refuse
     * it, or {} when nothing changed.
     */
    fun endDrag(screenX: Double, screenY: Double): String {
        val world = vp.toWorld(Offset(screenX.toFloat(), screenY.toFloat()), 1f)
        val moved = dragging
        val from = wireFrom
        val table = draggingTable
        val group = groupDelta != null
        val blockMoved = blockDrag
        dragging = null; wire = null; wireFrom = null; draggingTable = null; groupDelta = null; blockDrag = null
        return edit {
            when {
                blockMoved != null -> {
                    val g = FlowsheetEditing.GRID.toFloat()
                    val p = Offset((blockMoved.second.x / g).roundToInt() * g, (blockMoved.second.y / g).roundToInt() * g)
                    val b = Subflowsheets.of(saved, blockMoved.first) ?: return@edit null
                    if (p.x.toDouble() == b.x && p.y.toDouble() == b.y) null else Subflowsheets.move(saved, b.id, p.x.toDouble(), p.y.toDouble())
                }
                table != null -> FlowsheetEditing.moveTable(saved, table.first, table.second.x.toDouble(), table.second.y.toDouble())
                moved != null && group -> {
                    // The grabbed object goes on the grid and the rest of the group moves by as much, keeping its layout.
                    val o = fs.obj(moved.first) ?: return@edit null
                    val p = CanvasGeometry.snap(fs, o, moved.second)
                    val c = CanvasGeometry.center(o)
                    if (p == c) null else FlowsheetClipboard.moveAll(saved, selection.toList(), (p.x - c.x).toDouble(), (p.y - c.y).toDouble())
                }
                moved != null -> {
                    val o = fs.obj(moved.first) ?: return@edit null
                    // Dropped on the grid and lined up with what it connects to, so its lines run straight.
                    val p = CanvasGeometry.snap(fs, o, moved.second)
                    if (p == CanvasGeometry.center(o)) null else FlowsheetEditing.move(saved, o.id, p.x.toDouble(), p.y.toDouble())
                }
                from != null -> wireTo(from.first, from.second, hitTest(fs, world, vp.zoom), world.x.toDouble(), world.y.toDouble())
                else -> null
            }
        }
    }

    fun cancelDrag() { dragging = null; wire = null; wireFrom = null; draggingTable = null; groupDelta = null; box = null; blockDrag = null }

    // ---------- selection of several objects, copy and paste (D124)

    /** The selected objects' ids, as a JSON array. */
    fun selectedIds(): String = buildJsonArray { selection.forEach { add(it) } }.toString()
    val selectionCount: Int get() = selection.size
    fun isSelected(id: String): Boolean = id in selection
    /** Adds [id] to the selection, or takes it out (Shift or Ctrl and a tap). */
    fun toggleSelected(id: String) { if (saved.obj(id) != null && !selection.remove(id)) selection.add(id) }
    fun selectAll() { selection.clear(); selection.addAll(fs.objects.map { it.id }) }
    fun setSelectedIds(idsJson: String) {
        selection.clear()
        val known = fs.objects.map { it.id }.toSet()
        FlowsheetJson.parseToJsonElement(idsJson).jsonArray.map { it.jsonPrimitive.content }.filter { it in known }.forEach { selection.add(it) }
    }

    /** Starts a selection box at a screen point (Shift and a drag on empty canvas, or the Select tool). */
    fun beginBox(screenX: Double, screenY: Double) { val p = Offset(screenX.toFloat(), screenY.toFloat()); box = p to p }
    fun boxTo(screenX: Double, screenY: Double) { box = box?.let { it.first to Offset(screenX.toFloat(), screenY.toFloat()) } }
    /**
     * Ends the box: every object whose symbol lies wholly inside it is selected, added to the selection when [add].
     * Returns how many are selected.
     */
    fun endBox(add: Boolean): Int {
        val b = box ?: return selection.size
        box = null
        val a = vp.toWorld(b.first, 1f)
        val z = vp.toWorld(b.second, 1f)
        val r = Rect(minOf(a.x, z.x), minOf(a.y, z.y), maxOf(a.x, z.x), maxOf(a.y, z.y))
        val inside = fs.objects.filter { o -> CanvasGeometry.bounds(o).let { r.contains(it.topLeft) && r.contains(it.bottomRight) } }.map { it.id }
        if (!add) selection.clear()
        selection.addAll(inside)
        return selection.size
    }

    /** The selection as a clip (JSON) for the clipboard, or {error}. */
    fun copySelection(): String = try {
        FlowsheetJson.encodeToString(Clip.serializer(), FlowsheetClipboard.copy(saved, selection, results, displayUnits))
    } catch (e: IllegalArgumentException) { buildJsonObject { put("error", e.message ?: "Not allowed") }.toString() }

    /**
     * Pastes a clip: [step] grid squares down and right of the originals when they are on this flowsheet (each paste
     * one step further), else centred on the screen point ([screenX], [screenY]). Selects the copies. Returns
     * {flowsheet, ids, notes} or {error}.
     */
    fun paste(clipJson: String, screenX: Double, screenY: Double, step: Int): String = try {
        val clip = try { FlowsheetJson.decodeFromString(Clip.serializer(), clipJson) } catch (e: Exception) { throw EditRejected("Nothing to paste") }
        val (dx, dy) = if (clip.objects.all { saved.obj(it.id) != null }) {
            val d = FlowsheetEditing.GRID * 2 * step.coerceAtLeast(1)
            d to d
        } else {
            val boxes = clip.objects.map { CanvasGeometry.bounds(it) }
            val cx = (boxes.minOf { it.left } + boxes.maxOf { it.right }) / 2
            val cy = (boxes.minOf { it.top } + boxes.maxOf { it.bottom }) / 2
            val w = vp.toWorld(Offset(screenX.toFloat(), screenY.toFloat()), 1f)
            (w.x - cx).toDouble() to (w.y - cy).toDouble()
        }
        val r = FlowsheetClipboard.paste(saved, clip, dx, dy)
        // The copies go on the flowsheet shown (D132).
        val placed = r.flowsheet.copy(objects = r.flowsheet.objects.map { if (it.id in r.ids) it.copy(parent = level) else it })
        buildJsonObject {
            put("flowsheet", FlowsheetJson.encodeToJsonElement(Flowsheet.serializer(), placed))
            putJsonArray("ids") { r.ids.forEach { add(it) } }
            putJsonArray("notes") { r.notes.forEach { add(it) } }
        }.toString()
    } catch (e: IllegalArgumentException) { buildJsonObject { put("error", e.message ?: "Not allowed") }.toString() }

    /** Deletes every selected object; {flowsheet}, {error} or {}. */
    fun deleteSelected(): String = edit { if (selection.isEmpty()) null else FlowsheetClipboard.deleteAll(saved, selection.toList()) }

    /** Moves every selected object by ([dx], [dy]) world units (arrow keys); {flowsheet}, {error} or {}. */
    fun moveSelected(dx: Double, dy: Double): String = edit { if (selection.isEmpty()) null else FlowsheetClipboard.moveAll(saved, selection.toList(), dx, dy) }

    /** Shows or hides the object's property table on the canvas (the app's defaults: conditions and flows, or its first results). */
    fun toggleTable(id: String): String = edit {
        val o = saved.obj(id) ?: return@edit null
        FlowsheetEditing.setTable(saved, id, if (saved.tables.any { it.objectId == id }) emptyList() else PropertyTables.defaults(saved, o))
    }

    fun hasTable(id: String): Boolean = saved.tables.any { it.objectId == id }

    /** The table editor (the app's PropertyTableSheet): {tag, shown, keys: [{key, label, on}]}; a new table starts with the app's defaults. */
    fun tableOptions(id: String): String {
        val o = saved.obj(id) ?: return "{}"
        val existing = saved.tables.firstOrNull { it.objectId == id }
        val chosen = existing?.keys ?: PropertyTables.defaults(saved, o)
        return buildJsonObject {
            put("tag", o.tag); put("shown", existing != null)
            putJsonArray("keys") { PropertyTables.available(saved, o).forEach { k -> add(buildJsonObject { put("key", k); put("label", app.flowstead.core.model.ResultKeys.label(k)); put("on", k in chosen) }) } }
        }.toString()
    }

    /** Shows the object's table with exactly these properties ([keysJson], a JSON array of keys); an empty list removes it. */
    fun setTable(id: String, keysJson: String): String = edit {
        val keys = FlowsheetJson.parseToJsonElement(keysJson).jsonArray.map { it.jsonPrimitive.content }
        FlowsheetEditing.setTable(saved, id, keys)
    }

    /** True for a material stream nothing calculates (its conditions are typed), as the app's feed editor. */
    fun isFeed(id: String): Boolean = saved.obj(id)?.let { it.type == ObjectType.MaterialStream && saved.isFeed(it) } ?: false

    // ---------- copying a stream's data (the app's StreamCopy, 1.8.8) ----------

    /** The streams [targetId] can copy from: [{id, tag, feed}]. Empty unless the target is a feed (a calculated stream cannot take values). */
    fun copySources(targetId: String): String = buildJsonArray {
        val t = saved.obj(targetId)
        if (t != null && t.type == ObjectType.MaterialStream && saved.isFeed(t)) {
            saved.objects.filter { it.type == ObjectType.MaterialStream && it.id != targetId }.forEach { s ->
                add(buildJsonObject { put("id", s.id); put("tag", s.tag); put("feed", saved.isFeed(s)) })
            }
        }
    }.toString()

    /** What [sourceId] can give: [{key, label, value}] in the display units (a feed's specifications, or a calculated stream's current results). */
    fun copyItems(sourceId: String): String = buildJsonArray {
        val s = saved.obj(sourceId) ?: return@buildJsonArray
        val ctx = app.flowstead.core.units.UnitContext(saved.atmosphericPressurePa)
        StreamCopy.available(saved, s, results, displayUnits).forEach { item ->
            val text = item.spec?.let { sp ->
                val u = app.flowstead.core.units.Units.byId(sp.unit)
                ValueFormat.number(u.fromSi(sp.si, ctx), u.decimals) + " " + u.symbol
            } ?: item.composition?.let { c -> "${c.size} components" }.orEmpty()
            add(buildJsonObject { put("key", item.key); put("label", item.label); put("value", text) })
        }
    }.toString()

    /** Puts the ticked items of [sourceId] on feed [targetId]; they replace its own conditions, flow and composition. */
    fun copyFrom(targetId: String, sourceId: String, keysJson: String): String = edit {
        val s = saved.obj(sourceId) ?: throw EditRejected("That stream no longer exists")
        val keys = FlowsheetJson.parseToJsonElement(keysJson).jsonArray.map { it.jsonPrimitive.content }.toSet()
        StreamCopy.apply(saved, targetId, StreamCopy.available(saved, s, results, displayUnits).filter { it.key in keys })
    }

    /** A new feed beside [id] with all it can give; {flowsheet, id}. */
    fun duplicate(id: String): String = try {
        val (next, copy) = StreamCopy.duplicate(saved, id, results, displayUnits)
        buildJsonObject { put("flowsheet", FlowsheetJson.encodeToJsonElement(Flowsheet.serializer(), next)); put("id", copy.id) }.toString()
    } catch (e: IllegalArgumentException) { buildJsonObject { put("error", e.message ?: "Not allowed") }.toString() }

    /**
     * Every result the app can show for the object, as rows {label, value, unit}: the engine's numbers in °C, bara and
     * SI, formatted as on the app's screens, "—" where the engine returned nothing.
     */
    fun summary(id: String): String {
        val o = saved.obj(id) ?: return "[]"
        val rows = PropertyTables.rows(saved, app.flowstead.core.model.PropertyTable(id, PropertyTables.available(saved, o)), results, displayUnits)
        return kotlinx.serialization.json.buildJsonArray {
            rows.forEach { r -> add(buildJsonObject { put("label", r.label); put("value", r.value); put("unit", r.unit) }) }
        }.toString()
    }

    /** The solve's messages for one object, or for the whole flowsheet when [id] is null: [{level, text}]. */
    fun messages(id: String?): String = kotlinx.serialization.json.buildJsonArray {
        val list = if (id != null) results.messages(id) else solve?.result?.messages.orEmpty()
        list.forEach { m -> add(buildJsonObject { put("level", m.level.name.lowercase()); put("text", m.text) }) }
    }.toString()

    /** As the app's FlowsheetViewModel.wire: a wire released on empty canvas, a port, a stream or a unit. */
    private fun wireTo(unitId: String, port: Port, target: Hit, x: Double, y: Double): Flowsheet? {
        val f = saved
        val unit = f.obj(unitId) ?: throw EditRejected("That unit operation no longer exists")
        fun midpoint(other: SimObject) = (unit.x + other.x) / 2 to (unit.y + other.y) / 2
        return when (target) {
            Hit.Empty -> FlowsheetEditing.connectNewStream(f, unitId, port, x, y).first
            is Hit.OnPort -> {
                if (target.objectId == unitId) return null
                val other = f.obj(target.objectId) ?: return null
                val (mx, my) = midpoint(other)
                FlowsheetEditing.connectUnits(f, unitId, port, other.id, target.port, mx, my)
            }
            is Hit.OnObject -> {
                val other = f.obj(target.objectId) ?: return null
                when {
                    other.id == unitId -> null
                    other.type.isStream -> FlowsheetEditing.connect(f, other.id, unitId, port)
                    else -> {
                        val free = FlowsheetEditing.firstFreePort(f, other.id, port)
                            ?: throw EditRejected("${other.tag} has no free port for this connection")
                        val (mx, my) = midpoint(other)
                        FlowsheetEditing.connectUnits(f, unitId, port, other.id, free, mx, my)
                    }
                }
            }
        }
    }

    private fun edit(block: () -> Flowsheet?): String = try {
        val next = block()
        if (next == null || next == saved) "{}"
        else buildJsonObject { put("flowsheet", FlowsheetJson.encodeToJsonElement(Flowsheet.serializer(), next)) }.toString()
    } catch (e: IllegalArgumentException) {
        buildJsonObject { put("error", e.message ?: "Not allowed") }.toString()
    }

    /** What a screen reader says for an object: type, tag and state, as the app's canvas. */
    fun describe(id: String): String {
        val o = saved.obj(id) ?: return ""
        notReady[id]?.let { return "${o.tag}: not ready, ${it.replaceFirstChar { c -> c.lowercase() }}" }
        val st = status[id]
        val state = when (st) {
            is SpecStatus.UnderSpecified -> "needs ${st.needs.joinToString(", ")}"
            is SpecStatus.OverSpecified -> st.problem
            is SpecStatus.Invalid -> st.problem
            else -> when (results.objectStatus(id)) {
                ObjectStatus.Solved -> when {
                    results.stale -> "solved, results out of date"
                    results.warnings(id).isNotEmpty() -> "solved with ${results.warnings(id).size} warning(s)"
                    else -> "solved"
                }
                ObjectStatus.Error -> "calculation error"
                else -> "fully specified, not solved"
            }
        }
        return "${o.type.displayName()} ${o.tag}, $state"
    }

    // ---------- drawing

    private fun positionOf(o: SimObject): Offset {
        groupDelta?.let { d -> if (o.id in selection) return CanvasGeometry.center(o) + d }
        return dragging?.takeIf { it.first == o.id }?.second ?: CanvasGeometry.center(o)
    }

    private fun streamColor(o: SimObject): Color = when (o.type) {
        ObjectType.EnergyStream -> colors.energy
        ObjectType.MaterialStream -> phaseColor(
            (results.value(o.id, "vapourFraction") as? DisplayValue.Calculated)?.si ?: o.specs[SpecKey.VAPOUR_FRACTION]?.si, colors.palette, colors.twoPhase,
        )
        else -> colors.onSurface
    }

    /**
     * Draws the canvas on [ctx] (a CanvasRenderingContext2D already scaled for the device pixel ratio), [width] ×
     * [height] CSS pixels, as the app's FlowsheetCanvas draws it.
     */
    fun draw(ctx: dynamic, width: Double, height: Double) {
        val d = CanvasDrawScope(ctx, Size(width.toFloat(), height.toFloat()))
        val palette = colors.palette
        ctx.clearRect(0, 0, width, height)
        val k = vp.zoom
        // Grid (skipped when zoomed far out, where it would only be noise).
        if (vp.zoom >= 0.6f && !print) {
            val step = 24f * k
            var x = vp.offset.x.mod(step)
            while (x < width) {
                var y = vp.offset.y.mod(step)
                while (y < height) { d.drawCircle(colors.outlineVariant, 1.2f, Offset(x, y)); y += step }
                x += step
            }
        }
        d.translate(vp.offset.x, vp.offset.y) {
            scale(k, k, pivot = Offset.Zero) { world(this, palette) }
        }
        labels(ctx)
        tables(ctx, d)
        box?.let { (a, b) ->
            val r = Rect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
            d.drawRect(colors.secondary, r.topLeft, r.size, alpha = 0.10f)
            d.drawRect(colors.secondary, r.topLeft, r.size, style = Stroke(1.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f))))
        }
    }

    private fun world(d: DrawScope, palette: IconPalette) = with(d) {
        for (c in fs.connections) {
            val pts = CanvasGeometry.connectionRoute(fs, c, ::positionOf) ?: continue
            val stream = fs.objects.firstOrNull { it.id == c.fromObject && it.type.isStream } ?: fs.objects.firstOrNull { it.id == c.toObject }
            val energy = stream?.type == ObjectType.EnergyStream
            val color = if (energy) palette.energy else stream?.let { streamColor(it) } ?: palette.stroke
            drawPath(polyline(pts), color, style = Stroke(width = 2f, cap = StrokeCap.Round, pathEffect = if (energy) PathEffect.dashPathEffect(floatArrayOf(6f, 5f)) else null))
        }
        // Sub-flowsheet blocks (D132), with a dashed line from each matched stream of this flowsheet to the block.
        for (b in Subflowsheets.blocksAt(saved, level)) {
            val c = blockCenter(b)
            val r = blockRect(c.x.toDouble(), c.y.toDouble())
            drawRoundRect(palette.fill, r.topLeft, r.size, CornerRadius(8f))
            drawRoundRect(if (b.fluid != null) colors.secondary else palette.stroke, r.topLeft, r.size, CornerRadius(8f), style = Stroke(2f))
            drawRoundRect(palette.stroke, Offset(r.left + 5f, r.top + 5f), Size(r.width - 10f, r.height - 10f), CornerRadius(5f), style = Stroke(0.8f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f))))
            for (p in b.ports) {
                val outer = p.outer?.let { fs.obj(it) } ?: continue
                val feed = saved.connections.none { it.toObject == p.inner }
                val at = positionOf(outer)
                val edge = Offset(if (feed) r.left else r.right, at.y.coerceIn(r.top + 8f, r.bottom - 8f))
                drawLine(streamColor(outer), at, edge, 2f, StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
                drawCircle(streamColor(outer), 3.5f, edge)
            }
        }
        for (o in fs.objects) {
            val at = positionOf(o)
            val s = CanvasGeometry.size(o.type)
            if (o.id in selection && !print) {
                val b = CanvasGeometry.bounds(o, at).inflate(6f)
                drawRoundRect(colors.secondary, b.topLeft, b.size, CornerRadius(10f), style = Stroke(2.5f))
            }
            if (!o.type.isStream) drawNozzles(o.type, at, s, palette, strokeWidth = 2f)
            drawUnitIcon(o.type, at, s, palette, strokeWidth = 2f, streamColor = streamColor(o))
            for (p in Ports.of(o.type)) {
                val pos = CanvasGeometry.portPosition(o, p, at)
                val portColor = if (p.kind == PortKind.Energy) palette.energy else palette.stroke
                if (p.side == PortSide.Outlet) drawCircle(portColor, CanvasGeometry.PORT_RADIUS * 0.7f, pos)
                else {
                    drawCircle(palette.fill, CanvasGeometry.PORT_RADIUS * 0.7f, pos)
                    drawCircle(portColor, CanvasGeometry.PORT_RADIUS * 0.7f, pos, style = Stroke(1.5f))
                }
            }
            // Status badge (D83): red = not ready or failed, green = solved and current, amber = solved with warnings.
            val badge = when {
                o.id in notReady -> colors.error
                status[o.id] is SpecStatus.OverSpecified || status[o.id] is SpecStatus.Invalid || status[o.id] is SpecStatus.UnderSpecified -> colors.error
                else -> when (results.objectStatus(o.id)) {
                    ObjectStatus.Error -> colors.error
                    ObjectStatus.Solved -> when {
                        results.stale -> null
                        results.warnings(o.id).isNotEmpty() -> colors.underSpecified
                        else -> colors.converged
                    }
                    else -> null
                }
            }
            if (badge != null && !print) {
                val b = CanvasGeometry.bounds(o, at)
                drawCircle(badge, 4.5f, Offset(b.left - 7f, b.top - 7f))
            }
        }
        // Adjust, controller and Set references: dashed signal lines to the objects they read and write.
        for (adj in fs.objects.filter { it.type.isLoop || it.type == ObjectType.Set }) {
            val keys = if (adj.type.isLoop) listOf(AdjustKeys.MANIPULATED_OBJECT, AdjustKeys.CONTROLLED_OBJECT) else listOf(SetKeys.SOURCE_OBJECT, SetKeys.TARGET_OBJECT)
            for (key in keys) {
                val target = adj.refs[key]?.let { id -> fs.obj(id) } ?: continue
                val pts = CanvasGeometry.signalRoute(adj, target, ::positionOf)
                drawPath(polyline(pts), colors.secondary, style = Stroke(width = 1.5f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 4f))))
            }
        }
        // A PSV on the flowsheet: a dashed line to the equipment its relief valve protects.
        for (psv in fs.objects.filter { it.type == ObjectType.ReliefValve }) {
            val target = fs.safety?.devices?.firstOrNull { it.id == psv.id }?.protectedId?.let { fs.obj(it) } ?: continue
            drawLine(colors.secondary, positionOf(psv), positionOf(target), 1.5f, StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(3f, 4f)))
        }
        wire?.let { (a, b) ->
            drawLine(colors.secondary, a, b, 2f, StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            drawCircle(colors.secondary, 5f, b)
        }
    }

    private fun polyline(pts: List<Offset>) = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }

    private fun font(px: Float, bold: Boolean = false) = "${if (bold) "700" else "400"} ${px}px Inter, system-ui, sans-serif"

    /** Tags, in screen space so text stays crisp at any zoom; a controller's inside its bubble, letters over number. */
    private fun labels(ctx: dynamic) {
        val fontSize = (11f * vp.zoom).coerceIn(8f, 16f)
        ctx.textBaseline = "top"
        for (o in fs.objects) {
            val b = CanvasGeometry.bounds(o, positionOf(o))
            if (o.type == ObjectType.Controller) {
                val parts = o.tag.split('-', limit = 2)
                ctx.font = font((b.height * vp.zoom * 0.24f).coerceIn(6f, 16f))
                ctx.fillStyle = colors.onSurface.css()
                ctx.textAlign = "center"
                val c = vp.toScreen(b.center, 1f)
                ctx.textBaseline = "bottom"; ctx.fillText(parts[0], c.x, c.y - 1)
                ctx.textBaseline = "top"; ctx.fillText(parts.getOrElse(1) { "" }, c.x, c.y + 1)
                continue
            }
            ctx.font = font(fontSize)
            ctx.fillStyle = colors.onSurfaceVariant.css()
            ctx.textAlign = "center"
            val anchor = vp.toScreen(Offset(b.center.x, b.bottom + 4f), 1f)
            ctx.fillText(o.tag, anchor.x, anchor.y)
        }
        // Blocks: tag, and the package when it has its own; inside one, each matched boundary stream names its partner.
        for (b in Subflowsheets.blocksAt(saved, level)) {
            val c = vp.toScreen(blockCenter(b), 1f)
            ctx.textAlign = "center"; ctx.textBaseline = "middle"
            ctx.fillStyle = colors.onSurface.css(); ctx.font = font((13f * vp.zoom).coerceIn(9f, 18f), bold = true)
            ctx.fillText(b.tag, c.x, c.y - (if (b.fluid != null) 7f * vp.zoom else 0f))
            if (b.fluid != null) {
                ctx.fillStyle = colors.secondary.css(); ctx.font = font((10f * vp.zoom).coerceIn(7f, 13f))
                ctx.fillText((b.fluid.propertyPackage ?: "").substringBefore(" ("), c.x, c.y + 9f * vp.zoom)
            }
        }
        Subflowsheets.of(saved, level)?.let { blk ->
            ctx.textBaseline = "top"; ctx.textAlign = "center"; ctx.fillStyle = colors.secondary.css(); ctx.font = font((10f * vp.zoom).coerceIn(7f, 13f))
            for (p in blk.ports) {
                val inner = fs.obj(p.inner) ?: continue
                val outerTag = p.outer?.let { saved.obj(it)?.tag } ?: continue
                val feed = saved.connections.none { it.toObject == p.inner }
                val bx = CanvasGeometry.bounds(inner, positionOf(inner))
                val a = vp.toScreen(Offset(bx.center.x, bx.bottom + 4f), 1f)
                ctx.fillText(if (feed) "from $outerTag" else "to $outerTag", a.x, a.y + fontSize + 2f)
            }
        }
        ctx.textAlign = "left"
        ctx.textBaseline = "top"
    }

    /** Property tables, in screen space; they scale with the zoom and stay put when their object moves. */
    private fun tables(ctx: dynamic, d: DrawScope) {
        tableBoxes.clear()
        val size = (9f * vp.zoom).coerceIn(6f, 14f)
        val units = displayUnits
        fun w(text: String, bold: Boolean = false): Float { ctx.font = font(size, bold); return (ctx.measureText(text).width as Double).toFloat() }
        ctx.textBaseline = "top"
        for (t in fs.tables) {
            val o = fs.obj(t.objectId) ?: continue
            val rows = PropertyTables.rows(fs, t, results, units)
            val offset = draggingTable?.takeIf { it.first == t.objectId }?.second ?: Offset(t.dx.toFloat(), t.dy.toFloat())
            // From where the object is saved, not where it is being dragged: a table stays put when its object moves.
            val topLeft = vp.toScreen(CanvasGeometry.center(o) + offset, 1f)
            val rowH = size * 1.35f
            val pad = rowH * 0.35f
            val lw = rows.maxOfOrNull { w(it.label) } ?: 0f
            val vw = rows.maxOfOrNull { w(it.value) } ?: 0f
            val uw = rows.maxOfOrNull { w(it.unit) } ?: 0f
            val bw = maxOf(w(o.tag, bold = true), lw + vw + uw + 3 * pad) + 2 * pad
            val bh = rowH * (rows.size + 1) + 2 * pad
            val box = Rect(topLeft, Size(bw, bh))
            tableBoxes[t.objectId] = box
            d.drawRect(colors.surface, box.topLeft, box.size)
            d.drawRect(if (t.objectId in selection) colors.secondary else colors.outline, box.topLeft, box.size, style = Stroke(1.2f))
            ctx.font = font(size, bold = true); ctx.fillStyle = colors.onSurface.css(); ctx.textAlign = "left"
            ctx.fillText(o.tag, box.left + pad, box.top + pad)
            d.drawLine(colors.outlineVariant, Offset(box.left, box.top + pad + rowH), Offset(box.right, box.top + pad + rowH), 1f)
            rows.forEachIndexed { i, r ->
                val y = box.top + pad + rowH * (i + 1) + 1
                ctx.font = font(size); ctx.fillStyle = colors.onSurface.css()
                ctx.textAlign = "left"; ctx.fillText(r.label, box.left + pad, y)
                ctx.textAlign = "right"; ctx.fillText(r.value, box.left + 2 * pad + lw + vw, y)
                ctx.textAlign = "left"; ctx.fillStyle = colors.onSurfaceVariant.css(); ctx.fillText(r.unit, box.left + 3 * pad + lw + vw, y)
            }
        }
        ctx.textAlign = "left"
    }
}
