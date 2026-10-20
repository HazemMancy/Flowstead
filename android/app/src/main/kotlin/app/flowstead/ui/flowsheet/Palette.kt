/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.flowsheet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.displayName
import app.flowstead.ui.theme.valueColors

/** Unit operations the engine can solve today. Grows as milestone 7 adds the rest of the MVP set. */
val PALETTE: List<ObjectType> = listOf(
    ObjectType.MaterialStream,
    ObjectType.EnergyStream,
    ObjectType.Valve,
    ObjectType.Heater,
    ObjectType.Cooler,
    ObjectType.Separator,
    ObjectType.ThreePhaseSeparator,
)

/**
 * Bottom palette. Tap an item to drop it in the middle of the view (also the accessible path),
 * or long-press and drag it onto the canvas (a plain swipe scrolls the palette). [onDrag] reports the finger in root coordinates while dragging and
 * null when it lifts; [onDrop] gets the root position of the release.
 */
@Composable
fun Palette(
    onAdd: (ObjectType) -> Unit,
    onDrag: (ObjectType, Offset?) -> Unit,
    onDrop: (ObjectType, Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp, modifier = modifier.fillMaxWidth()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.testTag("palette"),
        ) {
            items(PALETTE) { type -> PaletteItem(type, onAdd, onDrag, onDrop) }
        }
    }
}

@Composable
private fun PaletteItem(
    type: ObjectType,
    onAdd: (ObjectType) -> Unit,
    onDrag: (ObjectType, Offset?) -> Unit,
    onDrop: (ObjectType, Offset) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val values = MaterialTheme.valueColors
    val palette = IconPalette(colors.surface, colors.onSurface, colors.secondary, values.vapour, values.liquid, values.energy)
    val coords = remember { CoordinatesHolder() }
    val drag by rememberUpdatedState(onDrag)
    val drop by rememberUpdatedState(onDrop)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .onGloballyPositioned { coords.value = it }
            .clickable(role = Role.Button, onClickLabel = "Add to flowsheet") { onAdd(type) }
            .pointerInput(type) {
                var root = Offset.Zero
                detectDragGesturesAfterLongPress(
                    onDragStart = { local ->
                        root = (coords.value?.positionInRoot() ?: Offset.Zero) + local
                        drag(type, root)
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        root += amount
                        drag(type, root)
                    },
                    onDragEnd = {
                        drag(type, null)
                        drop(type, root)
                    },
                    onDragCancel = { drag(type, null) },
                )
            }
            .semantics(mergeDescendants = true) { contentDescription = "Add ${type.displayName()}" }
            .testTag("palette_${type.name}"),
    ) {
        Surface(shape = RoundedCornerShape(12.dp), color = colors.surface, tonalElevation = 1.dp) {
            Canvas(Modifier.size(48.dp)) {
                val s = CanvasGeometry.size(type)
                val fit = minOf(size.width / (s.width + 8f), size.height / (s.height + 8f))
                scale(fit, fit, pivot = Offset.Zero) { drawUnitIcon(type, center / fit, s, palette, strokeWidth = 2f) }
            }
        }
        Text(
            type.displayName(),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            color = colors.onSurfaceVariant,
        )
    }
}

private class CoordinatesHolder {
    var value: LayoutCoordinates? = null
}

/** Ghost of the item being dragged from the palette, drawn above the canvas. */
@Composable
fun PaletteGhost(type: ObjectType, rootPosition: Offset, canvasRoot: Offset, zoom: Float) {
    val colors = MaterialTheme.colorScheme
    val values = MaterialTheme.valueColors
    val palette = IconPalette(colors.surface, colors.secondary, colors.secondary, values.vapour, values.liquid, values.energy)
    val s: Size = CanvasGeometry.size(type)
    Canvas(Modifier.fillMaxSize()) {
        val k = zoom * density
        val local = rootPosition - canvasRoot
        scale(k, k, pivot = Offset.Zero) {
            drawUnitIcon(type, local / k, s, palette, strokeWidth = 2f)
        }
    }
}
