/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.flowstead.ui.about.AboutScreen
import app.flowstead.ui.about.LicenseTextScreen
import app.flowstead.ui.flowsheet.FlowsheetScreen
import app.flowstead.ui.help.HelpScreen
import app.flowstead.ui.fluid.FluidPackageScreen
import app.flowstead.ui.projects.ProjectsScreen
import app.flowstead.ui.results.ResultsScreen
import app.flowstead.ui.results.SolverLogScreen
import app.flowstead.ui.settings.SettingsScreen
import kotlinx.serialization.Serializable

@Serializable data object ProjectsRoute
@Serializable data class FlowsheetRoute(val projectId: String)
@Serializable data class FluidRoute(val projectId: String)
@Serializable data class ResultsRoute(val projectId: String, val log: Boolean = false)
@Serializable data object SettingsRoute
@Serializable data object AboutRoute
@Serializable data class LicenseRoute(val title: String, val asset: String)
@Serializable data class HelpRoute(val topicId: String? = null)

@Composable
fun FlowsteadNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = ProjectsRoute) {
        composable<ProjectsRoute> {
            ProjectsScreen(
                onOpenProject = { nav.navigate(FlowsheetRoute(it)) },
                onOpenSettings = { nav.navigate(SettingsRoute) },
                onOpenAbout = { nav.navigate(AboutRoute) },
                onOpenHelp = { nav.navigate(HelpRoute()) },
            )
        }
        composable<FlowsheetRoute> { entry ->
            val route = entry.toRoute<FlowsheetRoute>()
            FlowsheetScreen(
                onBack = { nav.popBackStack() },
                onOpenFluid = { nav.navigate(FluidRoute(route.projectId)) },
                onOpenResults = { nav.navigate(ResultsRoute(route.projectId)) },
                onOpenLog = { nav.navigate(ResultsRoute(route.projectId, log = true)) },
                onOpenHelp = { nav.navigate(HelpRoute(it)) },
            )
        }
        composable<ResultsRoute> { entry ->
            if (entry.toRoute<ResultsRoute>().log) SolverLogScreen(onBack = { nav.popBackStack() })
            else ResultsScreen(onBack = { nav.popBackStack() })
        }
        composable<FluidRoute> {
            FluidPackageScreen(onBack = { nav.popBackStack() })
        }
        composable<SettingsRoute> {
            SettingsScreen(onBack = { nav.popBackStack() }, onOpenAbout = { nav.navigate(AboutRoute) })
        }
        composable<AboutRoute> {
            AboutScreen(
                onBack = { nav.popBackStack() },
                onOpenLicense = { title, asset -> nav.navigate(LicenseRoute(title, asset)) },
            )
        }
        composable<HelpRoute> { entry ->
            HelpScreen(
                topicId = entry.toRoute<HelpRoute>().topicId,
                onBack = { nav.popBackStack() },
                onOpenProject = { id -> nav.navigate(FlowsheetRoute(id)) { popUpTo(ProjectsRoute) } },
            )
        }
        composable<LicenseRoute> { entry ->
            val route = entry.toRoute<LicenseRoute>()
            LicenseTextScreen(title = route.title, asset = route.asset, onBack = { nav.popBackStack() })
        }
    }
}
