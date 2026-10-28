/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.projects

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.flowstead.data.ProjectSummary
import app.flowstead.ui.theme.FlowsteadTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ProjectsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun emptyStateInvitesFirstProject() {
        compose.setContent { FlowsteadTheme { ProjectsContent(ProjectsUiState(loading = false)) } }
        compose.onNodeWithText("No projects yet").assertIsDisplayed()
        compose.onNodeWithText("Create your first project").assertIsDisplayed()
    }

    @Test
    fun createDialogPassesTypedName() {
        var created: String? = null
        compose.setContent {
            FlowsteadTheme { ProjectsContent(ProjectsUiState(loading = false), onCreate = { created = it }) }
        }
        compose.onNodeWithTag("new_project").performClick()
        compose.onNodeWithTag("project_name_field").performTextInput("JT valve letdown")
        compose.onNodeWithText("Create").performClick()
        assertEquals("JT valve letdown", created)
    }

    @Test
    fun listShowsProjectsWithAccessibleSummary() {
        val p = ProjectSummary("a", "Compressor + aftercooler", System.currentTimeMillis(), 3, false)
        var opened: String? = null
        compose.setContent {
            FlowsteadTheme { ProjectsContent(ProjectsUiState(loading = false, projects = listOf(p)), onOpenProject = { opened = it }) }
        }
        compose.onNodeWithText("Compressor + aftercooler").assertIsDisplayed().performClick()
        assertEquals("a", opened)
        compose.onNodeWithContentDescription("More actions for Compressor + aftercooler").assertIsDisplayed()
    }

    @Test
    fun deleteAsksForConfirmation() {
        val p = ProjectSummary("a", "Recycle loop", 0L, 0, false)
        var deleted: String? = null
        compose.setContent {
            FlowsteadTheme { ProjectsContent(ProjectsUiState(loading = false, projects = listOf(p)), onDelete = { deleted = it }) }
        }
        compose.onNodeWithContentDescription("More actions for Recycle loop").performClick()
        compose.onNodeWithTag("menu_delete").performClick()
        compose.onNodeWithText("Delete “Recycle loop”?").assertIsDisplayed()
        assertEquals(null, deleted)
        compose.onNodeWithTag("confirm_delete").performClick()
        assertEquals("a", deleted)
    }

    @Test
    fun importNotesAreListedBeforeOpening() {
        var opened: Boolean? = null
        val report = ProjectsEffect.ImportReport("p9", "Gas plant", listOf("PIPE-1: Pipe is not supported", "END: it was fed by PIPE-1"))
        compose.setContent {
            FlowsteadTheme {
                ProjectsContent(ProjectsUiState(loading = false), importReport = report, onImportReportDone = { opened = it })
            }
        }
        compose.onNodeWithText("Imported “Gas plant” with 2 notes").assertIsDisplayed()
        compose.onNodeWithText("• PIPE-1: Pipe is not supported").assertIsDisplayed()
        compose.onNodeWithTag("open_imported").performClick()
        assertEquals(true, opened)
    }

    @Test
    fun importButtonShowsProgressWhileImporting() {
        compose.setContent { FlowsteadTheme { ProjectsContent(ProjectsUiState(loading = false, importing = true)) } }
        compose.onNodeWithTag("importing").assertIsDisplayed()
        compose.onNodeWithContentDescription("Import DWSIM file").assertDoesNotExist()
    }
}
