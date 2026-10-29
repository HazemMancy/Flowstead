/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.flowsheet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.flowstead.core.api.ObjectStatus
import app.flowstead.core.model.AdjustKeys
import app.flowstead.core.model.SetKeys
import app.flowstead.core.model.DisplayValue
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.ResultView
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.PortAlignment
import app.flowstead.core.model.streamAt
import app.flowstead.core.model.Port
import app.flowstead.core.model.PortKind
import app.flowstead.core.model.PortSide
import app.flowstead.core.model.Ports
import app.flowstead.core.model.PropertyTables
import app.flowstead.core.model.SimObject
import app.flowstead.core.model.SpecKey
import app.flowstead.core.model.SpecStatus
import app.flowstead.core.model.displayName
import app.flowstead.core.model.isLoop
import app.flowstead.core.units.UnitSet
import app.flowstead.ui.theme.Inter
import app.flowstead.ui.theme.valueColors
import kotlin.math.roundToInt

private enum class Outcome { Up, Drag, Multi }

/** What the canvas reports back; the screen turns these into ViewModel edits. */
interface CanvasActions {
    fun tap(hit: Hit)
    fun longPress(objectId: String)
    fun move(objectId: String, x: Double, y: Double)
    /** A wire dragged from [port] on [unitId] was released over [target] at [world]. */
    fun wire(unitId: String, port: Port, target: Hit, world: Offset)
    fun delete(objectId: String)
    /** A property table was moved to ([dx], [dy]) from its object, in world units. */
    fun moveTable(objectId: String, dx: Double, dy: Double) {}
    /** A property table was tapped or long-pressed. */
    fun editTable(objectId: String) {}
}

@Composable
fun FlowsheetCanvas(
    flowsheet: Flowsheet,
    status: Map<String, SpecStatus>,
    selectedId: String?,
    results: ResultView,
    viewport: Viewport,
    onViewport: (Viewport) -> Unit,
    actions: CanvasActions,
    modifier: Modifier = Modifier,
    unitSet: UnitSet = UnitSet.SI,
    /** Objects the solver leaves out, with why (D83): their badge is red. */
    notReady: Map<String, String> = emptyMap(),
) {
    val density = LocalDensity.current.density
    val colors = MaterialTheme.colorScheme
    val values = MaterialTheme.valueColors
    val palette = IconPalette(
        fill = colors.surface,
        stroke = colors.onSurface,
        accent = colors.secondary,
        vapour = values.vapour,
        liquid = values.liquid,
        energy = values.energy,
    )
    val textMeasurer = rememberTextMeasurer()
    val labelColor = colors.onSurfaceVariant

    // Drawn lined up: arrows and units a few units off their nozzles are shown level (saved positions unchanged).
    val shown = remember(flowsheet) { PortAlignment.align(flowsheet) }
    val fs by rememberUpdatedState(shown)
    val twoPhase = values.twoPhase
    val vp by rememberUpdatedState(viewport)
    val act by rememberUpdatedState(actions)
    val onVp by rememberUpdatedState(onViewport)

    // Transient gesture state: only committed to the flowsheet when the finger lifts.
    var dragging by remember { mutableStateOf<Pair<String, Offset>?>(null) }
    var wire by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    var draggingTable by remember { mutableStateOf<Pair<String, Offset>?>(null) }
    // Where each property table was last drawn (screen), for hit-testing; written while drawing.
    val tableBoxes = remember { mutableMapOf<String, Rect>() }

    fun positionOf(o: SimObject): Offset = dragging?.takeIf { it.first == o.id }?.second ?: CanvasGeometry.center(o)

    Box(modifier) {
        Canvas(
            Modifier
                .fillMaxSize()
                .testTag("flowsheet_canvas")
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startVp = vp
                        val startWorld = startVp.toWorld(down.position, density)
                        val hit = hitTest(fs, startWorld, startVp.zoom)
                        val tableHit = tableBoxes.entries.lastOrNull { it.value.contains(down.position) }?.key

                        val outcome: Outcome? = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            var travelled = Offset.Zero
                            var result: Outcome? = null
                            while (result == null) {
                                val ev = awaitPointerEvent()
                                val change = ev.changes.firstOrNull { it.id == down.id }
                                result = when {
                                    ev.changes.count { it.pressed } > 1 -> Outcome.Multi
                                    change == null || !change.pressed -> Outcome.Up
                                    else -> {
                                        travelled += change.positionChange()
                                        if (travelled.getDistance() > viewConfiguration.touchSlop) Outcome.Drag else null
                                    }
                                }
                            }
                            result
                        }

                        when (outcome) {
                            null -> { // long press
                                val id = if (tableHit != null) null else when (hit) {
                                    is Hit.OnObject -> hit.objectId
                                    is Hit.OnPort -> hit.objectId
                                    Hit.Empty -> null
                                }
                                if (tableHit != null) act.editTable(tableHit) else id?.let { act.longPress(it) }
                                do {
                                    val ev = awaitPointerEvent()
                                    ev.changes.forEach { it.consume() }
                                } while (ev.changes.any { it.pressed })
                            }
                            Outcome.Up -> if (tableHit != null) act.editTable(tableHit) else act.tap(hit)
                            Outcome.Drag -> if (tableHit != null) {
                                val table = fs.tables.firstOrNull { it.objectId == tableHit }
                                val from = Offset(table?.dx?.toFloat() ?: 0f, table?.dy?.toFloat() ?: 0f)
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val change = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    change.consume()
                                    draggingTable = tableHit to (from + (vp.toWorld(change.position, density) - startWorld))
                                }
                                draggingTable?.let { (id, at) -> act.moveTable(id, at.x.toDouble(), at.y.toDouble()) }
                                draggingTable = null
                            } else {
                                // A port already in use can't take another line, so dragging from it moves its unit:
                                // small symbols (a valve) are mostly ports at finger size.
                                val portHit = hit as? Hit.OnPort
                                val port = portHit?.takeIf { p -> fs.streamAt(p.objectId, p.port) == null }
                                val obj = ((hit as? Hit.OnObject)?.objectId ?: portHit?.takeIf { port == null }?.objectId)
                                    ?.let { id -> fs.objects.firstOrNull { it.id == id } }
                                val portStart = port?.let { p ->
                                    fs.objects.firstOrNull { it.id == p.objectId }?.let { CanvasGeometry.portPosition(it, p.port) }
                                }
                                var last = down.position
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val change = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    change.consume()
                                    val world = vp.toWorld(change.position, density)
                                    when {
                                        obj != null -> dragging = obj.id to (CanvasGeometry.center(obj) + (world - startWorld))
                                        portStart != null -> wire = portStart to world
                                        else -> onVp(vp.copy(offset = vp.offset + (change.position - last)))
                                    }
                                    last = change.position
                                }
                                val releaseWorld = vp.toWorld(last, density)
                                when {
                                    obj != null -> dragging?.let { (id, at) ->
                                        // Dropped on the grid and lined up with what it connects to, so its lines run straight.
                                        val snapped = CanvasGeometry.snap(fs, obj, at)
                                        act.move(id, snapped.x.toDouble(), snapped.y.toDouble())
                                    }
                                    port != null -> act.wire(port.objectId, port.port, hitTest(fs, releaseWorld, vp.zoom), releaseWorld)
                                }
                                dragging = null
                                wire = null
                            }
                            Outcome.Multi -> {
                                do {
                                    val ev = awaitPointerEvent()
                                    val zoom = ev.calculateZoom()
                                    val pan = ev.calculatePan()
                                    val centroid = ev.calculateCentroid(useCurrent = true)
                                    if (centroid != Offset.Unspecified) {
                                        val zoomed = vp.zoomAround(centroid, zoom)
                                        onVp(zoomed.copy(offset = zoomed.offset + pan))
                                    }
                                    ev.changes.forEach { it.consume() }
                                } while (ev.changes.any { it.pressed })
                            }
                        }
                    }
                },
        ) {
            val k = viewport.zoom * density
            // Grid (skipped when zoomed far out, where it would only be noise).
            if (viewport.zoom >= 0.6f) {
                val step = 24f * k
                val startX = viewport.offset.x % step
                val startY = viewport.offset.y % step
                var x = startX
                while (x < size.width) {
                    var y = startY
                    while (y < size.height) {
                        drawCircle(colors.outlineVariant, 1.2f * density, Offset(x, y))
                        y += step
                    }
                    x += step
                }
            }

            translate(viewport.offset.x, viewport.offset.y) {
                scale(k, k, pivot = Offset.Zero) {
                    // Connections
                    for (c in fs.connections) {
                        val pts = CanvasGeometry.connectionRoute(fs, c, ::positionOf) ?: continue
                        val stream = fs.objects.firstOrNull { it.id == c.fromObject && it.type.isStream }
                            ?: fs.objects.firstOrNull { it.id == c.toObject }
                        val energy = stream?.type == ObjectType.EnergyStream
                        val color = if (energy) palette.energy else stream?.let { streamColor(it, palette, results, twoPhase) } ?: palette.stroke
                        val path = Path().apply {
                            moveTo(pts[0].x, pts[0].y)
                            pts.drop(1).forEach { lineTo(it.x, it.y) }
                        }
                        drawPath(
                            path, color,
                            style = Stroke(
                                width = 2f, cap = StrokeCap.Round,
                                pathEffect = if (energy) PathEffect.dashPathEffect(floatArrayOf(6f, 5f)) else null,
                            ),
                        )
                    }

                    // Objects
                    for (o in fs.objects) {
                        val at = positionOf(o)
                        val s = CanvasGeometry.size(o.type)
                        if (o.id == selectedId) {
                            val b = CanvasGeometry.bounds(o, at).inflate(6f)
                            drawRoundRect(colors.secondary, b.topLeft, b.size, CornerRadius(10f), style = Stroke(2.5f))
                        }
                        if (!o.type.isStream) drawNozzles(o.type, at, s, palette, strokeWidth = 2f)
                        drawUnitIcon(o.type, at, s, palette, strokeWidth = 2f, streamColor = streamColor(o, palette, results, twoPhase))

                        // Ports
                        for (p in Ports.of(o.type)) {
                            val pos = CanvasGeometry.portPosition(o, p, at)
                            val portColor = if (p.kind == PortKind.Energy) palette.energy else palette.stroke
                            if (p.side == PortSide.Outlet) drawCircle(portColor, CanvasGeometry.PORT_RADIUS * 0.7f, pos)
                            else {
                                drawCircle(palette.fill, CanvasGeometry.PORT_RADIUS * 0.7f, pos)
                                drawCircle(portColor, CanvasGeometry.PORT_RADIUS * 0.7f, pos, style = Stroke(1.5f))
                            }
                        }

                        // Status badge (D83): red = not ready (missing something, or waiting for something that is) or failed,
                        // green = solved and current, amber = solved with warnings.
                        val badge = when {
                            o.id in notReady -> values.error
                            status[o.id] is SpecStatus.OverSpecified || status[o.id] is SpecStatus.Invalid -> values.error
                            status[o.id] is SpecStatus.UnderSpecified -> values.error
                            else -> when (results.objectStatus(o.id)) {
                                ObjectStatus.Error -> values.error
                                ObjectStatus.Solved -> when {
                                    results.stale -> null
                                    results.warnings(o.id).isNotEmpty() -> values.underSpecified
                                    else -> values.converged
                                }
                                else -> null
                            }
                        }
                        if (badge != null) {
                            // Outside the top-left corner, clear of the ports, which sit on the symbol's edges.
                            val b = CanvasGeometry.bounds(o, at)
                            drawCircle(badge, 4.5f, Offset(b.left - 7f, b.top - 7f))
                        }
                    }

                    // Adjust, controller and Set references: dashed (signal) lines to the objects they read and write.
                    for (adj in fs.objects.filter { it.type.isLoop || it.type == ObjectType.Set }) {
                        val from = positionOf(adj)
                        val keys = if (adj.type.isLoop) listOf(AdjustKeys.MANIPULATED_OBJECT, AdjustKeys.CONTROLLED_OBJECT)
                        else listOf(SetKeys.SOURCE_OBJECT, SetKeys.TARGET_OBJECT)
                        for (key in keys) {
                            val target = adj.refs[key]?.let { id -> fs.objects.firstOrNull { it.id == id } } ?: continue
                            val pts = CanvasGeometry.signalRoute(adj, target, ::positionOf)
                            drawPath(
                                Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } },
                                colors.secondary,
                                style = Stroke(width = 1.5f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 4f))),
                            )
                        }
                    }

                    // A PSV on the flowsheet: a dashed line to the equipment its relief valve protects.
                    for (psv in fs.objects.filter { it.type == ObjectType.ReliefValve }) {
                        val target = fs.safety?.devices?.firstOrNull { it.id == psv.id }?.protectedId
                            ?.let { id -> fs.objects.firstOrNull { it.id == id } } ?: continue
                        drawLine(
                            colors.secondary, positionOf(psv), positionOf(target), 1.5f, StrokeCap.Round,
                            PathEffect.dashPathEffect(floatArrayOf(3f, 4f)),
                        )
                    }

                    wire?.let { (a, b) ->
                        drawLine(colors.secondary, a, b, 2f, StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                        drawCircle(colors.secondary, 5f, b)
                    }
                }
            }

            // Tags, drawn in screen space so text stays crisp and readable at any zoom.
            val fontSize = (11f * viewport.zoom).coerceIn(8f, 16f)
            val style = TextStyle(fontFamily = Inter, fontFeatureSettings = "tnum", fontSize = fontSize.sp, color = labelColor)
            for (o in fs.objects) {
                val at = positionOf(o)
                val b = CanvasGeometry.bounds(o, at)
                if (o.type == ObjectType.Controller) {
                    // Inside the bubble, as on a P&ID: function letters above the line, loop number below.
                    val (letters, number) = o.tag.split('-', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                    val inside = style.copy(fontSize = (b.height * viewport.zoom * 0.24f / density).coerceIn(6f, 16f).sp, color = colors.onSurface)
                    val c = viewport.toScreen(b.center, density)
                    val top = textMeasurer.measure(letters, inside)
                    val bottom = textMeasurer.measure(number, inside)
                    drawText(top, topLeft = Offset(c.x - top.size.width / 2f, c.y - top.size.height - 1f))
                    drawText(bottom, topLeft = Offset(c.x - bottom.size.width / 2f, c.y + 1f))
                    continue
                }
                val anchor = viewport.toScreen(Offset(b.center.x, b.bottom + 4f), density)
                val layout = textMeasurer.measure(o.tag, style)
                drawText(layout, topLeft = Offset(anchor.x - layout.size.width / 2f, anchor.y))
            }

            // Property tables, also in screen space; they scale with the zoom and stay put when their object moves.
            tableBoxes.clear()
            val tableSize = (9f * viewport.zoom).coerceIn(6f, 14f)
            val cell = TextStyle(fontFamily = Inter, fontFeatureSettings = "tnum", fontSize = tableSize.sp, color = colors.onSurface)
            val head = cell.copy(fontWeight = FontWeight.Bold)
            val unitStyle = cell.copy(color = labelColor)
            for (t in fs.tables) {
                val o = fs.objects.firstOrNull { it.id == t.objectId } ?: continue
                val rows = PropertyTables.rows(fs, t, results, unitSet)
                val offset = draggingTable?.takeIf { it.first == t.objectId }?.second ?: Offset(t.dx.toFloat(), t.dy.toFloat())
                // From where the object is saved, not where it is being dragged: a table stays put when its object moves.
                val topLeft = viewport.toScreen(CanvasGeometry.center(o) + offset, density)
                val title = textMeasurer.measure(o.tag, head)
                val labels = rows.map { textMeasurer.measure(it.label, cell) }
                val valueTexts = rows.map { textMeasurer.measure(it.value, cell) }
                val unitsText = rows.map { textMeasurer.measure(it.unit, unitStyle) }
                val rowH = title.size.height.toFloat()
                val pad = rowH * 0.35f
                val lw = labels.maxOfOrNull { it.size.width }?.toFloat() ?: 0f
                val vw = valueTexts.maxOfOrNull { it.size.width }?.toFloat() ?: 0f
                val uw = unitsText.maxOfOrNull { it.size.width }?.toFloat() ?: 0f
                val w = maxOf(title.size.width.toFloat(), lw + vw + uw + 3 * pad) + 2 * pad
                val h = rowH * (rows.size + 1) + 2 * pad
                val box = Rect(topLeft, Size(w, h))
                tableBoxes[t.objectId] = box
                drawRect(colors.surface, box.topLeft, box.size)
                drawRect(if (t.objectId == selectedId) colors.secondary else colors.outline, box.topLeft, box.size, style = Stroke(1.2f))
                drawText(title, topLeft = Offset(box.left + pad, box.top + pad))
                drawLine(colors.outlineVariant, Offset(box.left, box.top + pad + rowH), Offset(box.right, box.top + pad + rowH), 1f)
                rows.indices.forEach { i ->
                    val y = box.top + pad + rowH * (i + 1)
                    drawText(labels[i], topLeft = Offset(box.left + pad, y))
                    drawText(valueTexts[i], topLeft = Offset(box.left + 2 * pad + lw + vw - valueTexts[i].size.width, y))
                    drawText(unitsText[i], topLeft = Offset(box.left + 3 * pad + lw + vw, y))
                }
            }
        }

        // Accessibility: one invisible node per object, placed over its icon, so TalkBack can
        // reach, read and act on everything drawn on the canvas.
        for (o in flowsheet.objects) {
            val b = CanvasGeometry.bounds(o)
            val topLeft = viewport.toScreen(b.topLeft, density)
            val description = notReady[o.id]?.let { "${o.tag}: not ready, ${it.replaceFirstChar { c -> c.lowercase() }}" } ?: describe(o, status[o.id], results)
            Box(
                Modifier
                    .offset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
                    .size((b.width * viewport.zoom).coerceAtLeast(48f).dp, (b.height * viewport.zoom).coerceAtLeast(48f).dp)
                    .clearAndSetSemantics {
                        contentDescription = description
                        onClick(label = "Edit") { actions.tap(Hit.OnObject(o.id)); true }
                        onLongClick(label = "More actions") { actions.longPress(o.id); true }
                        customActions = listOf(CustomAccessibilityAction("Delete ${o.tag}") { actions.delete(o.id); true })
                    },
            )
        }
    }
}

/** Vapour amber, liquid teal-blue, two-phase violet; from the solved vapour fraction, else from a VF spec. */
/** A stream's colour on the canvas and the printed PFD: energy, or its phase. */
internal fun streamColor(o: SimObject, p: IconPalette, results: ResultView, twoPhase: Color): Color = when (o.type) {
    ObjectType.EnergyStream -> p.energy
    ObjectType.MaterialStream -> phaseColor(
        (results.value(o.id, "vapourFraction") as? DisplayValue.Calculated)?.si ?: o.specs[SpecKey.VAPOUR_FRACTION]?.si, p, twoPhase,
    )
    else -> p.stroke
}

private fun describe(o: SimObject, status: SpecStatus?, results: ResultView): String {
    val state = when (status) {
        is SpecStatus.UnderSpecified -> "needs ${status.needs.joinToString(", ")}"
        is SpecStatus.OverSpecified -> status.problem
        is SpecStatus.Invalid -> status.problem
        else -> when (results.objectStatus(o.id)) {
            ObjectStatus.Solved -> when {
                results.stale -> "solved, results out of date"
                results.warnings(o.id).isNotEmpty() -> "solved with ${results.warnings(o.id).size} warning(s)"
                else -> "solved"
            }
            ObjectStatus.Error -> "calculation error"
            else -> "fully specified, not solved"
        }
    }
    return "${o.type.displayName()} ${o.tag}, $state"
}
