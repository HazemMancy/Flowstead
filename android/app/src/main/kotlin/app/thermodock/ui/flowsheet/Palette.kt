/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.ui.flowsheet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import app.thermodock.core.model.Category
import app.thermodock.core.model.ObjectType
import app.thermodock.core.model.displayName
import app.thermodock.ui.theme.valueColors

/** Objects the engine can solve, in the order engineers reach for them (the "All" tab). */
val PALETTE: List<ObjectType> = ObjectType.entries.filter { it.available }

/**
 * Object palette, grouped like HYSYS (streams, heat transfer, rotating, piping, mixing, separators,
 * columns, reactors, logical). Tap an item to drop it in the middle of the view (also the
 * accessible path), or long-press and drag it onto the canvas. Types the engine can't solve yet
 * appear in their group marked "Coming" and can't be placed. [onDrag] reports the finger in root
 * coordinates while dragging and null when it lifts; [onDrop] gets the root position of the release.
 */
@Composable
fun Palette(
    onAdd: (ObjectType) -> Unit,
    onDrag: (ObjectType, Offset?) -> Unit,
    onDrop: (ObjectType, Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    var group by rememberSaveable { mutableStateOf<Category?>(null) }
    val items = group?.let { g -> ObjectType.entries.filter { it.category == g } } ?: PALETTE
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp, modifier = modifier.fillMaxWidth()) {
        Column {
            LazyRow(
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.testTag("palette_groups"),
            ) {
                item { GroupChip("All", group == null) { group = null } }
                items(Category.entries) { g -> GroupChip(g.label, group == g) { group = g } }
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.testTag("palette"),
            ) {
                items(items, key = { it.name }) { type -> PaletteItem(type, onAdd, onDrag, onDrop) }
            }
        }
    }
}

@Composable
private fun GroupChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        modifier = Modifier.heightIn(min = 48.dp).testTag("group_$label"),
    )
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
    val enabled = type.available
    val coords = remember { CoordinatesHolder() }
    val drag by rememberUpdatedState(onDrag)
    val drop by rememberUpdatedState(onDrop)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(76.dp)
            .onGloballyPositioned { coords.value = it }
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Add to flowsheet") { onAdd(type) }
            .alpha(if (enabled) 1f else 0.45f)
            .pointerInput(type, enabled) {
                if (!enabled) return@pointerInput
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
            .semantics(mergeDescendants = true) {
                contentDescription = if (enabled) "Add ${type.displayName()}" else "${type.displayName()}, coming in a later version"
            }
            .testTag("palette_${type.name}"),
    ) {
        Surface(shape = RoundedCornerShape(12.dp), color = colors.surface, tonalElevation = 1.dp) {
            Canvas(Modifier.size(52.dp)) {
                val s = CanvasGeometry.size(type)
                val fit = minOf(size.width / (s.width + 8f), size.height / (s.height + 8f))
                scale(fit, fit, pivot = Offset.Zero) { drawUnitIcon(type, center / fit, s, palette, strokeWidth = 1.5f * density / fit) }
            }
        }
        Text(
            if (enabled) type.displayName() else "${type.displayName()} · Coming",
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
