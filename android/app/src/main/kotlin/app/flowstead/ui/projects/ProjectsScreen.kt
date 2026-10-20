/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.projects

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flowstead.data.ProjectSummary
import app.flowstead.ui.components.FlowsteadMark
import app.flowstead.ui.components.Wordmark
import app.flowstead.ui.theme.NumberStyles

@Composable
fun ProjectsScreen(
    onOpenProject: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    viewModel: ProjectsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var report by remember { mutableStateOf<ProjectsEffect.ImportReport?>(null) }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect {
            when (it) {
                is ProjectsEffect.OpenProject -> onOpenProject(it.id)
                is ProjectsEffect.Message -> snackbar.showSnackbar(it.text)
                is ProjectsEffect.ImportReport -> report = it
            }
        }
    }
    // DWSIM files have no registered MIME type and SAF can't filter by extension, so any file is offered.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::import) }
    ProjectsContent(
        state = state,
        snackbar = snackbar,
        onOpenProject = onOpenProject,
        onCreate = viewModel::create,
        onRename = viewModel::rename,
        onDuplicate = viewModel::duplicate,
        onDelete = viewModel::delete,
        onImport = { picker.launch(arrayOf("*/*")) },
        importReport = report,
        onImportReportDone = { open ->
            val r = report
            report = null
            if (open && r != null) onOpenProject(r.id)
        },
        onOpenSettings = onOpenSettings,
        onOpenAbout = onOpenAbout,
    )
}

private sealed interface ProjectDialog {
    data object Create : ProjectDialog
    data class Rename(val project: ProjectSummary) : ProjectDialog
    data class Delete(val project: ProjectSummary) : ProjectDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsContent(
    state: ProjectsUiState,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    onOpenProject: (String) -> Unit = {},
    onCreate: (String) -> Unit = {},
    onRename: (String, String) -> Unit = { _, _ -> },
    onDuplicate: (String) -> Unit = {},
    onDelete: (String) -> Unit = {},
    onImport: () -> Unit = {},
    importReport: ProjectsEffect.ImportReport? = null,
    onImportReportDone: (open: Boolean) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
) {
    var dialog by remember { mutableStateOf<ProjectDialog?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Wordmark(MaterialTheme.typography.titleLarge) },
                actions = {
                    if (state.importing) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp).testTag("importing"), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = onImport, modifier = Modifier.testTag("import")) {
                            Icon(Icons.Filled.FileOpen, contentDescription = "Import DWSIM file")
                        }
                    }
                    IconButton(onClick = onOpenAbout) { Icon(Icons.Filled.Info, contentDescription = "About Flowstead") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { dialog = ProjectDialog.Create },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New project") },
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
                modifier = Modifier.testTag("new_project"),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.projects.isEmpty() -> EmptyProjects(Modifier.padding(padding)) { dialog = ProjectDialog.Create }
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(padding).testTag("project_list"),
            ) {
                items(state.projects, key = { it.id }) { p ->
                    ProjectCard(
                        project = p,
                        onOpen = { onOpenProject(p.id) },
                        onRename = { dialog = ProjectDialog.Rename(p) },
                        onDuplicate = { onDuplicate(p.id) },
                        onDelete = { dialog = ProjectDialog.Delete(p) },
                    )
                }
            }
        }
    }

    when (val d = dialog) {
        ProjectDialog.Create -> NameDialog(
            title = "New project",
            confirm = "Create",
            initial = "",
            onConfirm = { onCreate(it); dialog = null },
            onDismiss = { dialog = null },
        )
        is ProjectDialog.Rename -> NameDialog(
            title = "Rename project",
            confirm = "Rename",
            initial = d.project.name,
            onConfirm = { onRename(d.project.id, it); dialog = null },
            onDismiss = { dialog = null },
        )
        is ProjectDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Delete “${d.project.name}”?") },
            text = { Text("The flowsheet and its results are removed from this phone. This can’t be undone.") },
            confirmButton = {
                TextButton(onClick = { onDelete(d.project.id); dialog = null }, modifier = Modifier.testTag("confirm_delete")) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        null -> Unit
    }

    importReport?.let { r ->
        AlertDialog(
            onDismissRequest = { onImportReportDone(false) },
            title = { Text("Imported “${r.name}” with ${r.warnings.size} note${if (r.warnings.size == 1) "" else "s"}") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("import_warnings")) {
                    items(r.warnings) { w -> Text("• $w", style = MaterialTheme.typography.bodyMedium) }
                }
            },
            confirmButton = {
                TextButton(onClick = { onImportReportDone(true) }, modifier = Modifier.testTag("open_imported")) { Text("Open project") }
            },
            dismissButton = { TextButton(onClick = { onImportReportDone(false) }) { Text("Close") } },
        )
    }
}

@Composable
private fun EmptyProjects(modifier: Modifier = Modifier, onCreate: () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        FlowsteadMark(size = 96.dp)
        Spacer(Modifier.height(24.dp))
        Text("No projects yet", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Steady-state process simulation, in your pocket. Start a flowsheet for a quick flash, a separator check or a compressor train.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onCreate, modifier = Modifier.heightIn(min = 48.dp)) { Text("Create your first project") }
    }
}

@Composable
private fun ProjectCard(
    project: ProjectSummary,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val edited = DateUtils.getRelativeTimeSpanString(project.updatedAt).toString()
    val objects = when (project.objectCount) {
        0 -> "Empty flowsheet"
        1 -> "1 object"
        else -> "${project.objectCount} objects"
    }
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Open project", role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) {
                contentDescription = "${project.name}. $objects. Edited $edited."
            },
    ) {
        Row(Modifier.padding(start = 20.dp, top = 16.dp, bottom = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(project.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(
                    "$objects · $edited",
                    style = NumberStyles.tag,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More actions for ${project.name}")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDelete() },
                        modifier = Modifier.testTag("menu_delete"),
                    )
                }
            }
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    confirm: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // The dialog's window attaches a frame later; auto-focus is a convenience, so never fail on it.
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(80) },
                label = { Text("Project name") },
                placeholder = { Text("e.g. HP separator check") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm(name) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("project_name_field"),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
