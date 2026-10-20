/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.flowsheet

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flowstead.core.api.SolveStatus
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.Port
import app.flowstead.core.model.Spec
import app.flowstead.core.model.obj
import app.flowstead.ui.components.BackButton
import app.flowstead.ui.theme.Brand
import app.flowstead.ui.theme.NumberStyles
import app.flowstead.ui.theme.valueColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowsheetScreen(
    onBack: () -> Unit,
    onOpenFluid: () -> Unit,
    onOpenResults: () -> Unit,
    onOpenLog: () -> Unit,
    viewModel: FlowsheetViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    val ready = (state as? FlowsheetUiState.Ready)?.ui
    var menu by remember { mutableStateOf(false) }
    val dwsimFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let(viewModel::exportDwsim)
    }
    val csvFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ResultsFormat.Csv.mimeType)) { uri ->
        uri?.let { viewModel.exportResults(it, ResultsFormat.Csv) }
    }
    val xlsxFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ResultsFormat.Xlsx.mimeType)) { uri ->
        uri?.let { viewModel.exportResults(it, ResultsFormat.Xlsx) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(ready?.projectName ?: "Flowsheet", style = MaterialTheme.typography.titleLarge, maxLines = 1)
                        Text(
                            ready?.flowsheet?.propertyPackage ?: "No property package",
                            style = NumberStyles.tag,
                            color = if (ready?.flowsheet?.propertyPackage == null) MaterialTheme.valueColors.underSpecifiedText
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = viewModel::undo, enabled = ready?.canUndo == true) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                    }
                    IconButton(onClick = onOpenFluid) { Icon(Icons.Filled.Science, contentDescription = "Fluid package") }
                    Box {
                        IconButton(onClick = { menu = true }, enabled = ready != null && !ready.exporting, modifier = Modifier.testTag("flowsheet_menu")) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Export DWSIM file (.dwxmz)") },
                                enabled = ready != null && ready.engineConfigured && ready.problems.isEmpty(),
                                onClick = { menu = false; dwsimFile.launch(viewModel.exportFileName("dwxmz")) },
                            )
                            val hasResults = ready?.results?.status != null
                            DropdownMenuItem(
                                text = { Text("Export results (CSV)") },
                                enabled = hasResults,
                                onClick = { menu = false; csvFile.launch(viewModel.exportFileName("csv", " results")) },
                            )
                            DropdownMenuItem(
                                text = { Text("Export results (Excel)") },
                                enabled = hasResults,
                                onClick = { menu = false; xlsxFile.launch(viewModel.exportFileName("xlsx", " results")) },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when (val s = state) {
            FlowsheetUiState.Loading -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            FlowsheetUiState.NotFound -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("This project no longer exists.")
            }
            is FlowsheetUiState.Ready -> Editor(s.ui, viewModel, onOpenResults, onOpenLog, Modifier.padding(padding))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Editor(ui: FlowsheetUi, vm: FlowsheetViewModel, onOpenResults: () -> Unit, onOpenLog: () -> Unit, modifier: Modifier) {
    val density = LocalDensity.current.density
    var viewport by remember { mutableStateOf(Viewport()) }
    var canvasRect by remember { mutableStateOf(Rect.Zero) }
    var centred by remember { mutableStateOf(false) }
    var ghost by remember { mutableStateOf<Pair<ObjectType, Offset>?>(null) }
    var quickActionsFor by remember { mutableStateOf<String?>(null) }
    var showIssues by remember { mutableStateOf(false) }

    // First layout: centre the drawing (or the world origin for an empty flowsheet).
    LaunchedEffect(canvasRect.size, centred) {
        if (centred || canvasRect.width == 0f) return@LaunchedEffect
        val objs = ui.flowsheet.objects
        val worldCentre = if (objs.isEmpty()) Offset.Zero
        else Offset(objs.map { it.x }.average().toFloat(), objs.map { it.y }.average().toFloat())
        viewport = Viewport(offset = Offset(canvasRect.width / 2, canvasRect.height / 2) - worldCentre * density, zoom = 1f)
        centred = true
    }

    val canvasActions = remember(vm) {
        object : CanvasActions {
            override fun tap(hit: Hit) = when (hit) {
                is Hit.OnObject -> vm.openEditor(hit.objectId)
                is Hit.OnPort -> vm.openEditor(hit.objectId)
                Hit.Empty -> vm.select(null)
            }
            override fun longPress(objectId: String) {
                vm.select(objectId)
                quickActionsFor = objectId
            }
            override fun move(objectId: String, x: Double, y: Double) = vm.move(objectId, x, y)
            override fun wire(unitId: String, port: Port, target: Hit, world: Offset) =
                vm.wire(unitId, port, target, world.x.toDouble(), world.y.toDouble())
            override fun delete(objectId: String) = vm.delete(objectId)
        }
    }
    val editorActions = remember(vm) {
        object : EditorActions {
            override fun rename(id: String, tag: String) = vm.rename(id, tag)
            override fun setSpec(id: String, key: String, spec: Spec?) = vm.setSpec(id, key, spec)
            override fun setExclusiveSpec(id: String, key: String, spec: Spec, group: Collection<String>) =
                vm.setExclusiveSpec(id, key, spec, group)
            override fun setComposition(id: String, fractions: Map<String, Double>) = vm.setComposition(id, fractions)
            override fun normalise(id: String, fractions: Map<String, Double>) = vm.normaliseComposition(id, fractions)
            override fun delete(id: String) = vm.delete(id)
            override fun setRef(id: String, key: String, value: String?) = vm.setRef(id, key, value)
        }
    }

    fun worldAtRoot(root: Offset) = viewport.toWorld(root - canvasRect.topLeft, density)

    Column(modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onGloballyPositioned { canvasRect = Rect(it.positionInRoot(), it.size.toSize()) },
        ) {
            FlowsheetCanvas(
                flowsheet = ui.flowsheet,
                status = ui.status,
                selectedId = ui.selectedId,
                results = ui.results,
                viewport = viewport,
                onViewport = { viewport = it },
                actions = canvasActions,
                modifier = Modifier.fillMaxSize(),
            )
            if (ui.flowsheet.objects.isEmpty()) {
                Text(
                    "Tap or long-press-drag an item from the palette below.\nStart with a material stream for your feed.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            }
            ghost?.let { (type, root) -> PaletteGhost(type, root, canvasRect.topLeft, viewport.zoom) }
        }

        SolveBar(ui, onSolve = vm::solve, onCancelQueued = vm::cancelQueuedSolve, onIssues = { showIssues = true }, onResults = onOpenResults, onLog = onOpenLog)
        Palette(
            onAdd = { type ->
                val c = viewport.toWorld(Offset(canvasRect.width / 2, canvasRect.height / 2), density)
                // Nudge each new item so repeated taps don't stack exactly.
                val n = ui.flowsheet.objects.size % 5
                vm.add(type, (c.x + n * 24).toDouble(), (c.y + n * 24).toDouble())
            },
            onDrag = { type, root -> ghost = root?.let { type to it } },
            onDrop = { type, root ->
                if (canvasRect.contains(root)) {
                    val w = worldAtRoot(root)
                    vm.add(type, w.x.toDouble(), w.y.toDouble())
                }
            },
            modifier = Modifier.navigationBarsPadding(),
        )
    }

    ui.editingId?.let { id ->
        ui.flowsheet.obj(id)?.let { obj ->
            ObjectEditorSheet(
                fs = ui.flowsheet,
                obj = obj,
                status = ui.status[id],
                results = ui.results,
                unitSet = ui.unitSet,
                ctx = ui.unitContext,
                actions = editorActions,
                onDismiss = vm::closeEditor,
            )
        }
    }

    quickActionsFor?.let { id ->
        ui.flowsheet.obj(id)?.let { obj ->
            ObjectQuickActions(
                obj = obj,
                onEdit = { vm.openEditor(id) },
                onDisconnect = { vm.disconnectAll(id) },
                onDelete = { vm.delete(id) },
                onDismiss = { quickActionsFor = null },
            )
        }
    }

    if (showIssues) {
        ModalBottomSheet(onDismissRequest = { showIssues = false }) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 24.dp),
            ) {
                Text("Before solving", style = MaterialTheme.typography.titleLarge)
                ui.problems.forEach { (id, text) ->
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.valueColors.underSpecifiedText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(enabled = id != null, role = Role.Button) {
                                showIssues = false
                                id?.let(vm::openEditor)
                            }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        }
    }
}

/**
 * Status and actions under the canvas: what blocks a solve, the last result's state, and the
 * Solve button (teal, as on the brand board).
 */
@Composable
private fun SolveBar(ui: FlowsheetUi, onSolve: () -> Unit, onCancelQueued: () -> Unit, onIssues: () -> Unit, onResults: () -> Unit, onLog: () -> Unit) {
    val v = MaterialTheme.valueColors
    val solving = ui.solveState == SolveState.Solving
    val (text, color) = when {
        ui.problems.isNotEmpty() ->
            (if (ui.problems.size == 1) "1 thing to fix before solving" else "${ui.problems.size} things to fix before solving") to v.underSpecifiedText
        solving -> "Solving…" to MaterialTheme.colorScheme.onSurfaceVariant
        ui.solveState == SolveState.Queued -> "Queued — solves when the engine is reachable" to MaterialTheme.colorScheme.onSurfaceVariant
        ui.results.status == null -> (if (ui.engineConfigured) "Ready to solve" else "Set the engine in Settings to solve") to MaterialTheme.colorScheme.onSurfaceVariant
        ui.results.stale -> "Results out of date — solve again" to v.underSpecifiedText
        ui.results.status == SolveStatus.Converged -> "✓ Converged" to v.convergedText
        else -> "Last solve ${ui.results.status?.name?.lowercase()} — see log" to v.error
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().testTag("solve_bar")) {
        Row(Modifier.heightIn(min = 56.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text,
                color = color,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = ui.problems.isNotEmpty(), role = Role.Button, onClickLabel = "Show issues", onClick = onIssues)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (ui.solveState == SolveState.Queued) {
                TextButton(onClick = onCancelQueued, modifier = Modifier.heightIn(min = 48.dp).testTag("cancel_queued")) { Text("Cancel") }
            } else if (ui.results.status != null) {
                TextButton(onClick = onLog, modifier = Modifier.heightIn(min = 48.dp)) { Text("Log") }
                TextButton(onClick = onResults, modifier = Modifier.heightIn(min = 48.dp)) { Text("Results") }
            }
            Button(
                onClick = onSolve,
                enabled = !solving,
                colors = ButtonDefaults.buttonColors(containerColor = Brand.Teal, contentColor = Brand.Navy),
                modifier = Modifier.heightIn(min = 48.dp).testTag("solve_button"),
            ) {
                if (solving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Brand.Navy)
                else Text("Solve", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
