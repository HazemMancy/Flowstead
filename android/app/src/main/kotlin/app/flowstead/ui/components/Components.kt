/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.flowstead.R
import app.flowstead.core.format.MISSING
import app.flowstead.core.format.ValueFormat
import app.flowstead.core.model.DisplayValue
import app.flowstead.core.units.EngUnit
import app.flowstead.core.units.UnitContext
import app.flowstead.ui.theme.NumberStyles
import app.flowstead.ui.theme.valueColors

@Composable
fun FlowsteadMark(size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_flowstead_mark),
        contentDescription = "Flowstead logo",
        modifier = modifier.size(size),
    )
}

/** "Flow" + teal "stead", as on the brand board. */
@Composable
fun Wordmark(style: TextStyle, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.secondary
    Text(
        text = buildAnnotatedString {
            append("Flow")
            withStyle(SpanStyle(color = accent)) { append("stead") }
        },
        style = style,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier,
    )
}

@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
    }
}

/**
 * Renders a value with its provenance colour. This is the only way numbers reach the
 * screen: there is no overload that takes a bare Double, so an unsourced value can't be shown.
 */
@Composable
fun ValueText(
    value: DisplayValue,
    unit: EngUnit,
    modifier: Modifier = Modifier,
    ctx: UnitContext = UnitContext(),
) {
    val colors = MaterialTheme.valueColors
    val (text, color, spoken) = when (value) {
        is DisplayValue.Specified -> ValueFormat.si(value.si, unit, ctx).let { Triple(it, colors.specified, "$it, specified") }
        is DisplayValue.Calculated -> ValueFormat.si(value.si, unit, ctx).let {
            Triple(it, colors.calculated, if (value.stale) "$it, calculated, out of date" else "$it, calculated")
        }
        is DisplayValue.Missing -> Triple(MISSING, colors.underSpecifiedText, "not available: ${value.reason}")
        is DisplayValue.Error -> Triple(MISSING, colors.error, "error: ${value.reason}")
    }
    Text(
        text = text,
        style = if (value is DisplayValue.Specified) NumberStyles.valueSpecified else NumberStyles.value,
        color = if (value is DisplayValue.Calculated && value.stale) color.copy(alpha = 0.6f) else color,
        modifier = modifier.semantics { contentDescription = spoken },
    )
}

/** Small rounded status chip, e.g. "Converged". */
@Composable
fun StatusChip(text: String, container: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier
            .background(container, RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = content)
    }
}
