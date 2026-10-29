/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Widest a list or form gets: on a tablet, lines this long still read as one column. */
val ReadableMaxWidth = 840.dp

/**
 * Full width on a phone; on a wider screen (a tablet, a phone in landscape), at most [ReadableMaxWidth] wide and
 * centred, so lists and forms do not stretch across the whole screen. The flowsheet canvas keeps the full width.
 */
fun Modifier.readableWidth(): Modifier =
    fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = ReadableMaxWidth).fillMaxWidth()
