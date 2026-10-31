/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.results

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextContains
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.flowstead.core.api.MessageLevel
import app.flowstead.core.api.ObjectResult
import app.flowstead.core.api.ObjectStatus
import app.flowstead.core.api.PhaseResult
import app.flowstead.core.api.SolveResult
import app.flowstead.core.api.SolveStatus
import app.flowstead.core.api.SolverMessage
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.ResultView
import app.flowstead.core.model.SimObject
import app.flowstead.core.model.StoredSolve
import app.flowstead.core.model.modelHash
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.UnitSet
import app.flowstead.ui.theme.FlowsteadTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ResultsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val fs = Flowsheet(
        propertyPackage = "Peng-Robinson (PR)",
        compounds = listOf("Methane"),
        objects = listOf(
            SimObject("a", "FEED", ObjectType.MaterialStream),
            SimObject("b", "LP", ObjectType.MaterialStream),
        ),
    )

    private val result = SolveResult(
        SolveStatus.Converged,
        elapsedMs = 650,
        engineVersion = "0.1.0",
        dwsimVersion = "10.2.12",
        messages = listOf(SolverMessage(MessageLevel.Warning, "Recycle tolerance relaxed", "b")),
        objects = mapOf(
            "a" to ObjectResult(
                ObjectStatus.Solved,
                properties = mapOf("temperature" to 318.15, "pressure" to 7_101_330.0),
                phases = mapOf("overall" to PhaseResult(composition = mapOf("Methane" to 1.0))),
            ),
            "b" to ObjectResult(ObjectStatus.Error, error = "Flash failed"),
        ),
    )

    private fun ui(hash: String = fs.modelHash()) = ResultsUi(
        name = "Test",
        flowsheet = fs,
        solve = StoredSolve(result, hash, 0L),
        results = ResultView(StoredSolve(result, hash, 0L), fs.modelHash()),
        unitSet = UnitSet.SI,
        ctx = UnitContext(),
    )

    @Test
    fun showsEngineValuesInTheUnitSetAndDashesForTheRest() {
        compose.setContent { FlowsteadTheme { ResultsContent(ui()) } }
        compose.onNodeWithTag("results_banner").assertTextContains("Converged", substring = true)
        compose.onNodeWithText("45.00 °C").assertIsDisplayed()
        compose.onNodeWithText("7,101.33 kPa").assertIsDisplayed()
        // Values the engine did not return are dashes with a spoken reason, never numbers.
        val missing = compose.onAllNodesWithContentDescription("not available: Not returned by the engine").fetchSemanticsNodes()
        assertTrue(missing.isNotEmpty())
        compose.onAllNodesWithContentDescription("error: Flash failed").fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
    }

    @Test
    fun staleResultsAreFlagged() {
        compose.setContent { FlowsteadTheme { ResultsContent(ui(hash = "something-else")) } }
        compose.onNodeWithTag("results_banner").assertTextContains("Out of date", substring = true)
        compose.onNodeWithContentDescription("45.00 °C, calculated, out of date").assertIsDisplayed()
    }

    @Test
    fun solverLogListsMessagesWithObjectTags() {
        compose.setContent { FlowsteadTheme { SolverLogContent(ui()) } }
        compose.onNodeWithText("Recycle tolerance relaxed").assertIsDisplayed()
        compose.onNodeWithText("Warning · LP").assertIsDisplayed()
        compose.onNodeWithText("Flowstead 0.1.0 · DWSIM 10.2.12").assertIsDisplayed()
    }
}
