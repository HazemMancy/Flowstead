/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.flowsheet

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A sheet along the bottom that does not block the flowsheet (owner's report, D146): no scrim, so an object above it
 * is tapped straight away (its editor replaces this one) and the flowsheet pans and zooms as usual. Its title bar is
 * dragged up or down to set its height, down to the title bar alone; the arrow minimises it or opens it again; ✕ or
 * Back closes it. Where it was left (its height, minimised or not) is kept while the app runs.
 */
@Composable
internal fun FloatingSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "floating_sheet",
    content: @Composable () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val density = LocalDensity.current
    // dp; -1: the default (55 % of the space), kept across objects as the user left it.
    var heightDp by rememberSaveable(key = "floating_sheet_height") { mutableFloatStateOf(-1f) }
    var minimised by rememberSaveable(key = "floating_sheet_min") { mutableStateOf(false) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val full = maxHeight.value
        val titleBar = 56f
        val target = when {
            minimised -> titleBar
            heightDp < 0 -> full * 0.55f
            else -> heightDp.coerceIn(titleBar + 64f, full * 0.95f)
        }
        val drag = rememberDraggableState { delta ->
            val current = when { minimised -> titleBar; heightDp < 0 -> full * 0.55f; else -> heightDp }
            val now = current - with(density) { delta.toDp().value }
            minimised = false
            heightDp = now.coerceIn(titleBar, full * 0.95f)
        }
        Surface(
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            tonalElevation = 3.dp,
            shadowElevation = 12.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .height(target.dp)
                .imePadding()
                .testTag(testTag),
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = titleBar.dp)
                        .draggable(
                            drag, Orientation.Vertical,
                            // Let go near the bottom: minimised to the title bar.
                            onDragStopped = { if (heightDp in 0f..(titleBar + 64f)) { minimised = true; heightDp = -1f } },
                        )
                        .semantics { contentDescription = "$title: drag up or down to resize" }
                        .padding(start = 16.dp, end = 4.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Box(
                            Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(bottom = 4.dp)
                                .size(width = 32.dp, height = 4.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)),
                        )
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { minimised = !minimised }, modifier = Modifier.testTag("${testTag}_minimise")) {
                        Icon(if (minimised) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = if (minimised) "Open the sheet again" else "Minimise the sheet")
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("${testTag}_close")) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }
                if (!minimised) Box(Modifier.weight(1f)) { content() }
            }
        }
    }
}
