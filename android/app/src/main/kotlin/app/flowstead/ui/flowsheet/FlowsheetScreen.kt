/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.flowsheet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.flowstead.data.Project
import app.flowstead.data.ProjectRepository
import app.flowstead.ui.components.BackButton
import app.flowstead.ui.nav.FlowsheetRoute
import app.flowstead.ui.theme.NumberStyles
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

sealed interface FlowsheetUiState {
    data object Loading : FlowsheetUiState
    data object NotFound : FlowsheetUiState
    data class Ready(val project: Project) : FlowsheetUiState
}

@HiltViewModel
class FlowsheetViewModel @Inject constructor(
    savedState: SavedStateHandle,
    repository: ProjectRepository,
) : ViewModel() {
    private val projectId = savedState.toRoute<FlowsheetRoute>().projectId

    val state: StateFlow<FlowsheetUiState> = repository.observeProject(projectId)
        .map { if (it == null) FlowsheetUiState.NotFound else FlowsheetUiState.Ready(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FlowsheetUiState.Loading)
}

/** Milestone 4 shell: project title and the snap grid. The editor itself arrives in Milestone 5. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowsheetScreen(onBack: () -> Unit, viewModel: FlowsheetViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            (state as? FlowsheetUiState.Ready)?.project?.name ?: "Flowsheet",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                        )
                        (state as? FlowsheetUiState.Ready)?.project?.flowsheet?.propertyPackage?.let {
                            Text(it, style = NumberStyles.tag, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = { BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
            when (state) {
                FlowsheetUiState.Loading -> CircularProgressIndicator()
                FlowsheetUiState.NotFound -> Text("This project no longer exists.")
                is FlowsheetUiState.Ready -> {
                    GridBackground()
                    Text(
                        "Empty flowsheet.\nThe drag-and-drop editor is the next milestone.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun GridBackground() {
    val dot = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxSize()) {
        val step = 24.dp.toPx()
        var x = step / 2
        while (x < size.width) {
            var y = step / 2
            while (y < size.height) {
                drawCircle(dot, radius = 1.5.dp.toPx(), center = Offset(x, y))
                y += step
            }
            x += step
        }
    }
}
