/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.flowsheet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The owner's report (D146): the editor over the flowsheet blocked everything behind it. The sheet now leaves the
 * flowsheet above it free, minimises to its title bar, and closes from ✕.
 */
@RunWith(AndroidJUnit4::class)
class FloatingSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun theFlowsheetAboveTheSheetStillTakesTapsAndTheSheetMinimises() {
        var behind = 0
        var dismissed = false
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().testTag("behind").clickable { behind++ })
                FloatingSheet(title = "V-100 · Separator", onDismiss = { dismissed = true }) { Text("Sheet body") }
            }
        }
        compose.onNodeWithText("Sheet body").assertIsDisplayed()
        // Near the top, above the sheet: the flowsheet gets the tap (a modal sheet's scrim took it before).
        compose.onNodeWithTag("behind").performTouchInput { click(Offset(centerX, 20f)) }
        assertEquals(1, behind)
        // Minimise to the title bar, then open again.
        compose.onNodeWithTag("floating_sheet_minimise").performClick()
        assertTrue(compose.onAllNodesWithTextCount("Sheet body") == 0)
        compose.onNodeWithTag("floating_sheet_minimise").performClick()
        compose.onNodeWithText("Sheet body").assertIsDisplayed()
        // Dragged right down by its title bar, it minimises too.
        compose.onNodeWithText("V-100 · Separator").performTouchInput { swipeDown(startY = centerY, endY = centerY + 3000f) }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithTextCount("Sheet body") == 0)
        compose.onNodeWithTag("floating_sheet_close").performClick()
        assertTrue(dismissed)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
