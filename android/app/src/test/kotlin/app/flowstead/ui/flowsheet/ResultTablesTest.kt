/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.flowsheet

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.flowstead.core.api.ObjectResult
import app.flowstead.core.api.ObjectStatus
import app.flowstead.core.api.PhaseResult
import app.flowstead.core.api.Phases
import app.flowstead.core.api.SolveResult
import app.flowstead.core.api.SolveStatus
import app.flowstead.core.model.Connection
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.ResultView
import app.flowstead.core.model.SimObject
import app.flowstead.core.model.StoredSolve
import app.flowstead.core.model.UnitStreams
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.UnitSet
import app.flowstead.ui.theme.FlowsteadTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResultTablesTest {
    @get:Rule val compose = createComposeRule()

    private val fs = Flowsheet(
        compounds = listOf("Methane", "Propane"),
        objects = listOf(
            SimObject("a", "FEED", ObjectType.MaterialStream),
            SimObject("v", "VLV-100", ObjectType.Valve),
            SimObject("b", "LP", ObjectType.MaterialStream),
        ),
        connections = listOf(Connection("a", 0, "v", 0), Connection("v", 0, "b", 0)),
    )

    private fun stream(vf: Double) = ObjectResult(
        ObjectStatus.Solved,
        properties = mapOf("temperature" to 300.0, "pressure" to 2e6, "vapourFraction" to vf, "molarFlow" to 1.0, "massFlow" to 0.03),
        phases = mapOf(
            Phases.OVERALL to PhaseResult(mapOf("density" to 40.0), mapOf("Methane" to 0.6, "Propane" to 0.4)),
            Phases.VAPOUR to PhaseResult(mapOf("density" to 20.0, "viscosity" to 1.1e-5, "compressibilityFactor" to 0.91), mapOf("Methane" to 0.8, "Propane" to 0.2)),
            Phases.LIQUID1 to PhaseResult(mapOf("density" to 480.0, "surfaceTension" to 0.005), mapOf("Methane" to 0.1, "Propane" to 0.9)),
        ),
    )

    private val view = ResultView(
        StoredSolve(
            SolveResult(SolveStatus.Converged, objects = mapOf("a" to stream(0.7), "b" to stream(0.8), "v" to ObjectResult(ObjectStatus.Solved, properties = mapOf("pressureDrop" to 1e5)))),
            "h", 0,
        ),
        "h",
    )

    @Test
    fun streamShowsEveryPhaseBlockAndCompositionByPhase() {
        compose.setContent { FlowsteadTheme { StreamResults(fs, "a", view, UnitSet.SI, UnitContext()) } }
        compose.onNodeWithText("Vapour phase").assertExists()
        compose.onNodeWithText("Liquid phase").assertExists()
        compose.onNodeWithText("Vapour phase Z factor").assertExists()
        compose.onNodeWithText("Liquid phase surface tension").assertExists()
        compose.onNodeWithText("Composition (mole fraction)").assertExists()
        compose.onNodeWithText("Propane").assertExists()
    }

    @Test
    fun unitShowsAWorksheetOfItsStreams() {
        compose.setContent { FlowsteadTheme { UnitWorksheet(fs, UnitStreams.of(fs, "v"), view, UnitSet.SI, UnitContext()) } }
        compose.onNodeWithText("Worksheet").assertExists()
        compose.onNodeWithText("IN · FEED").assertExists()
        compose.onNodeWithText("OUT · LP").assertExists()
        compose.onNodeWithText("Vapour phase molar flow").assertExists()
    }
}
