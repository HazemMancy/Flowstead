/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Light scheme: paper and ink, black primary buttons, signal orange for accents. No dynamic colour: the brand is the point. */
val FlowsteadLightColors: ColorScheme = lightColorScheme(
    primary = Brand.Ink,
    onPrimary = Color.White,
    primaryContainer = Brand.PaperHigh,
    onPrimaryContainer = Brand.Ink,
    secondary = Brand.SignalText,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE4D9),
    onSecondaryContainer = Color(0xFF4A1500),
    tertiary = Brand.Signal,
    onTertiary = Brand.Ink,
    tertiaryContainer = Color(0xFFFFE4D9),
    onTertiaryContainer = Color(0xFF4A1500),
    background = Brand.Paper,
    onBackground = Brand.Ink,
    surface = Color.White,
    onSurface = Brand.Ink,
    surfaceVariant = Brand.PaperRaised,
    onSurfaceVariant = Brand.Graphite,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBFBF9),
    surfaceContainer = Brand.Paper,
    surfaceContainerHigh = Brand.PaperRaised,
    surfaceContainerHighest = Brand.PaperHigh,
    outline = Color(0xFF8A8A84),
    outlineVariant = Brand.Hairline,
    error = Color(0xFFC8372D),
    onError = Color.White,
    errorContainer = Color(0xFFFBE3E1),
    onErrorContainer = Color(0xFF5E120D),
    inverseSurface = Brand.Ink,
    inverseOnSurface = Brand.Chalk,
    inversePrimary = Brand.SignalLight,
    scrim = Color.Black,
)

/** Dark scheme: ink surfaces, chalk text and primary buttons, lighter signal orange for accents. */
val FlowsteadDarkColors: ColorScheme = darkColorScheme(
    primary = Brand.Chalk,
    onPrimary = Brand.Ink,
    primaryContainer = Brand.InkHigh,
    onPrimaryContainer = Brand.Chalk,
    secondary = Brand.SignalLight,
    onSecondary = Brand.Ink,
    secondaryContainer = Color(0xFF4A1D0A),
    onSecondaryContainer = Color(0xFFFFD9C9),
    tertiary = Brand.Signal,
    onTertiary = Brand.Ink,
    tertiaryContainer = Color(0xFF4A1D0A),
    onTertiaryContainer = Color(0xFFFFD9C9),
    background = Brand.Ink,
    onBackground = Brand.Chalk,
    surface = Brand.InkRaised,
    onSurface = Brand.Chalk,
    surfaceVariant = Brand.InkHigh,
    onSurfaceVariant = Brand.Fog,
    surfaceContainerLowest = Color(0xFF0B0B0D),
    surfaceContainerLow = Color(0xFF151518),
    surfaceContainer = Brand.InkRaised,
    surfaceContainerHigh = Color(0xFF1E1E22),
    surfaceContainerHighest = Brand.InkHigh,
    outline = Color(0xFF75757D),
    outlineVariant = Brand.InkHairline,
    error = Color(0xFFFF8A80),
    onError = Color(0xFF5E120D),
    errorContainer = Color(0xFF7A1F18),
    onErrorContainer = Color(0xFFFBE3E1),
    inverseSurface = Brand.Chalk,
    inverseOnSurface = Brand.Ink,
    inversePrimary = Brand.SignalText,
    scrim = Color.Black,
)

/** Soft, modern corners: cards and sheets rounder than Material's defaults. */
val FlowsteadShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

enum class ThemeMode { System, Light, Dark }

@Composable
fun FlowsteadTheme(mode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    CompositionLocalProvider(LocalValueColors provides if (dark) DarkValueColors else LightValueColors) {
        MaterialTheme(
            colorScheme = if (dark) FlowsteadDarkColors else FlowsteadLightColors,
            typography = FlowsteadTypography,
            shapes = FlowsteadShapes,
            content = content,
        )
    }
}

/** Shorthand for the value-semantics palette. */
val MaterialTheme.valueColors: ValueColors
    @Composable get() = LocalValueColors.current
