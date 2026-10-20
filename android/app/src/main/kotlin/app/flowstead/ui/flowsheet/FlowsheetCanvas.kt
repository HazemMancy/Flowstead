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
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.Port
import app.flowstead.core.model.PortKind
import app.flowstead.core.model.PortSide
import app.flowstead.core.model.Ports
import app.flowstead.core.model.SimObject
import app.flowstead.core.model.SpecKey
import app.flowstead.core.model.SpecStatus
import app.flowstead.core.model.displayName
import app.flowstead.ui.theme.PlexMono
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
}

@Composable
fun FlowsheetCanvas(
    flowsheet: Flowsheet,
    status: Map<String, SpecStatus>,
    selectedId: String?,
    viewport: Viewport,
    onViewport: (Viewport) -> Unit,
    actions: CanvasActions,
    modifier: Modifier = Modifier,
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

    val fs by rememberUpdatedState(flowsheet)
    val vp by rememberUpdatedState(viewport)
    val act by rememberUpdatedState(actions)
    val onVp by rememberUpdatedState(onViewport)

    // Transient gesture state: only committed to the flowsheet when the finger lifts.
    var dragging by remember { mutableStateOf<Pair<String, Offset>?>(null) }
    var wire by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }

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
                                val id = when (hit) {
                                    is Hit.OnObject -> hit.objectId
                                    is Hit.OnPort -> hit.objectId
                                    Hit.Empty -> null
                                }
                                id?.let { act.longPress(it) }
                                do {
                                    val ev = awaitPointerEvent()
                                    ev.changes.forEach { it.consume() }
                                } while (ev.changes.any { it.pressed })
                            }
                            Outcome.Up -> act.tap(hit)
                            Outcome.Drag -> {
                                val obj = (hit as? Hit.OnObject)?.let { h -> fs.objects.firstOrNull { it.id == h.objectId } }
                                val port = hit as? Hit.OnPort
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
                                    obj != null -> dragging?.let { (id, at) -> act.move(id, at.x.toDouble(), at.y.toDouble()) }
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
                        val (a, b) = CanvasGeometry.endpoints(fs, c, ::positionOf) ?: continue
                        val stream = fs.objects.firstOrNull { it.id == c.fromObject && it.type.isStream }
                            ?: fs.objects.firstOrNull { it.id == c.toObject }
                        val energy = stream?.type == ObjectType.EnergyStream
                        val color = if (energy) palette.energy else stream?.let { streamColor(it, palette) } ?: palette.stroke
                        val pts = CanvasGeometry.elbow(a, b)
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
                        drawUnitIcon(o.type, at, s, palette, strokeWidth = 2f, streamColor = streamColor(o, palette))

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

                        // Status badge: amber = needs input, red = invalid / over-specified.
                        val badge = when (status[o.id]) {
                            is SpecStatus.UnderSpecified -> values.underSpecified
                            is SpecStatus.OverSpecified, is SpecStatus.Invalid -> values.error
                            else -> null
                        }
                        if (badge != null) {
                            val b = CanvasGeometry.bounds(o, at)
                            drawCircle(badge, 4.5f, Offset(b.right, b.top))
                        }
                    }

                    wire?.let { (a, b) ->
                        drawLine(colors.secondary, a, b, 2f, StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                        drawCircle(colors.secondary, 5f, b)
                    }
                }
            }

            // Tags, drawn in screen space so text stays crisp and readable at any zoom.
            val fontSize = (11f * viewport.zoom).coerceIn(8f, 16f)
            val style = TextStyle(fontFamily = PlexMono, fontSize = fontSize.sp, color = labelColor)
            for (o in fs.objects) {
                val at = positionOf(o)
                val b = CanvasGeometry.bounds(o, at)
                val anchor = viewport.toScreen(Offset(b.center.x, b.bottom + 4f), density)
                val layout = textMeasurer.measure(o.tag, style)
                drawText(layout, topLeft = Offset(anchor.x - layout.size.width / 2f, anchor.y))
            }
        }

        // Accessibility: one invisible node per object, placed over its icon, so TalkBack can
        // reach, read and act on everything drawn on the canvas.
        for (o in flowsheet.objects) {
            val b = CanvasGeometry.bounds(o)
            val topLeft = viewport.toScreen(b.topLeft, density)
            val description = describe(o, status[o.id])
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

private fun streamColor(o: SimObject, p: IconPalette): Color = when (o.type) {
    ObjectType.EnergyStream -> p.energy
    ObjectType.MaterialStream -> when (o.specs[SpecKey.VAPOUR_FRACTION]?.si) {
        1.0 -> p.vapour
        0.0 -> p.liquid
        else -> p.stroke
    }
    else -> p.stroke
}

private fun describe(o: SimObject, status: SpecStatus?): String {
    val state = when (status) {
        is SpecStatus.UnderSpecified -> "needs ${status.needs.joinToString(", ")}"
        is SpecStatus.OverSpecified -> status.problem
        is SpecStatus.Invalid -> status.problem
        else -> "fully specified"
    }
    return "${o.type.displayName()} ${o.tag}, $state"
}
