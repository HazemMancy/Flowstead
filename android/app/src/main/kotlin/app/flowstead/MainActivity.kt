/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flowstead.data.settings.AppSettings
import app.flowstead.data.settings.SettingsRepository
import app.flowstead.ui.nav.FlowsteadNavHost
import app.flowstead.ui.theme.FlowsteadTheme
import app.flowstead.ui.theme.isDark
import app.flowstead.ui.update.UpdatePromptHost
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            // The status and navigation bar icons follow the app's theme, not the phone's: dark icons on the light app
            // (the default) even when the phone is in dark mode, where they would otherwise be white on white.
            val dark = settings.themeMode.isDark()
            SideEffect {
                val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            FlowsteadTheme(mode = settings.themeMode) {
                FlowsteadNavHost()
                UpdatePromptHost()
            }
        }
    }
}
