/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/** Light scheme built from the brand palette. No dynamic colour: the brand is the point. */
val FlowsteadLightColors: ColorScheme = lightColorScheme(
    primary = Brand.Navy,
    onPrimary = Color.White,
    primaryContainer = Brand.Mist,
    onPrimaryContainer = Brand.Navy,
    secondary = Brand.TealText,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3F1EE),
    onSecondaryContainer = Color(0xFF053A36),
    tertiary = Brand.Amber,
    onTertiary = Brand.Navy,
    tertiaryContainer = Color(0xFFFDEBD1),
    onTertiaryContainer = Color(0xFF4A2E05),
    background = Brand.Background,
    onBackground = Brand.Navy,
    surface = Color.White,
    onSurface = Brand.Navy,
    surfaceVariant = Brand.Mist,
    onSurfaceVariant = Brand.SecondaryText,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBFCFD),
    surfaceContainer = Brand.Background,
    surfaceContainerHigh = Color(0xFFEFF3F7),
    surfaceContainerHighest = Brand.Mist,
    outline = Color(0xFF7C8FA2),
    outlineVariant = Brand.Border,
    error = Color(0xFFC8372D),
    onError = Color.White,
    errorContainer = Color(0xFFFBE3E1),
    onErrorContainer = Color(0xFF5E120D),
    inverseSurface = Brand.Navy,
    inverseOnSurface = Brand.Mist,
    inversePrimary = Brand.TealLight,
    scrim = Color.Black,
)

/** Dark scheme: navy surfaces from the brand board, teal as the accent. */
val FlowsteadDarkColors: ColorScheme = darkColorScheme(
    primary = Brand.Teal,
    onPrimary = Brand.Navy,
    primaryContainer = Brand.NavyHigh,
    onPrimaryContainer = Brand.TealLight,
    secondary = Brand.TealLight,
    onSecondary = Brand.Navy,
    secondaryContainer = Color(0xFF0E4A47),
    onSecondaryContainer = Color(0xFFC6F1EC),
    tertiary = Brand.Amber,
    onTertiary = Brand.Navy,
    tertiaryContainer = Color(0xFF5A3B0E),
    onTertiaryContainer = Color(0xFFFDEBD1),
    background = Brand.Navy,
    onBackground = Color.White,
    surface = Brand.Navy,
    onSurface = Brand.Mist,
    surfaceVariant = Brand.NavyHigh,
    onSurfaceVariant = Brand.OnDarkText,
    surfaceContainerLowest = Color(0xFF081726),
    surfaceContainerLow = Color(0xFF0D2338),
    surfaceContainer = Brand.NavyRaised,
    surfaceContainerHigh = Color(0xFF122C46),
    surfaceContainerHighest = Brand.NavyHigh,
    outline = Color(0xFF6F87A0),
    outlineVariant = Brand.NavyBorder,
    error = Color(0xFFFF8A80),
    onError = Color(0xFF5E120D),
    errorContainer = Color(0xFF7A1F18),
    onErrorContainer = Color(0xFFFBE3E1),
    inverseSurface = Brand.Mist,
    inverseOnSurface = Brand.Navy,
    inversePrimary = Brand.TealText,
    scrim = Color.Black,
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
            content = content,
        )
    }
}

/** Shorthand for the value-semantics palette. */
val MaterialTheme.valueColors: ValueColors
    @Composable get() = LocalValueColors.current
