/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.ui.theme

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

/** Light scheme: canvas and white surfaces, navy text, teal primary actions. No dynamic colour: the brand is the point. */
val ThermoDockLightColors: ColorScheme = lightColorScheme(
    primary = Brand.Teal,
    onPrimary = Brand.Navy,
    primaryContainer = Brand.TealContainer,
    onPrimaryContainer = Brand.Navy,
    secondary = Brand.TealText,
    onSecondary = Color.White,
    secondaryContainer = Brand.TealContainer,
    onSecondaryContainer = Brand.Navy,
    tertiary = Brand.BlueText,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDCEAFD),
    onTertiaryContainer = Brand.Navy,
    background = Brand.Canvas,
    onBackground = Brand.Navy,
    surface = Color.White,
    onSurface = Brand.Navy,
    surfaceVariant = Brand.CanvasRaised,
    onSurfaceVariant = Brand.SlateText,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Brand.Canvas,
    surfaceContainerHigh = Brand.CanvasRaised,
    surfaceContainerHighest = Brand.CanvasHigh,
    outline = Brand.Slate,
    outlineVariant = Brand.Hairline,
    error = Brand.ErrorText,
    onError = Color.White,
    errorContainer = Color(0xFFFBE1E4),
    onErrorContainer = Color(0xFF5C0F1A),
    inverseSurface = Brand.Navy,
    inverseOnSurface = Brand.Canvas,
    inversePrimary = Brand.Teal,
    scrim = Color.Black,
)

/** Dark scheme: navy surfaces, canvas text, teal primary actions. */
val ThermoDockDarkColors: ColorScheme = darkColorScheme(
    primary = Brand.Teal,
    onPrimary = Brand.Navy,
    primaryContainer = Brand.TealContainerDark,
    onPrimaryContainer = Brand.TealContainer,
    secondary = Brand.Teal,
    onSecondary = Brand.Navy,
    secondaryContainer = Brand.TealContainerDark,
    onSecondaryContainer = Brand.TealContainer,
    tertiary = Brand.Blue,
    onTertiary = Brand.Navy,
    tertiaryContainer = Color(0xFF1B3A66),
    onTertiaryContainer = Color(0xFFDCEAFD),
    background = Brand.Navy,
    onBackground = Brand.Canvas,
    surface = Brand.NavyRaised,
    onSurface = Brand.Canvas,
    surfaceVariant = Brand.NavyHigh,
    onSurfaceVariant = Brand.Slate,
    surfaceContainerLowest = Color(0xFF07111F),
    surfaceContainerLow = Color(0xFF0E1E37),
    surfaceContainer = Brand.NavyRaised,
    surfaceContainerHigh = Color(0xFF152947),
    surfaceContainerHighest = Brand.NavyHigh,
    outline = Brand.Slate,
    outlineVariant = Brand.NavyHairline,
    error = Color(0xFFFF8A95),
    onError = Color(0xFF5C0F1A),
    errorContainer = Color(0xFF7A1F2B),
    onErrorContainer = Color(0xFFFBE1E4),
    inverseSurface = Brand.Canvas,
    inverseOnSurface = Brand.Navy,
    inversePrimary = Brand.TealText,
    scrim = Color.Black,
)

/** Soft, modern corners: cards and sheets rounder than Material's defaults. */
val ThermoDockShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

enum class ThemeMode { System, Light, Dark }

@Composable
fun ThermoDockTheme(mode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    CompositionLocalProvider(LocalValueColors provides if (dark) DarkValueColors else LightValueColors) {
        MaterialTheme(
            colorScheme = if (dark) ThermoDockDarkColors else ThermoDockLightColors,
            typography = ThermoDockTypography,
            shapes = ThermoDockShapes,
            content = content,
        )
    }
}

/** Shorthand for the value-semantics palette. */
val MaterialTheme.valueColors: ValueColors
    @Composable get() = LocalValueColors.current
