/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.flowstead.data.ProjectRepository
import app.flowstead.data.ProjectSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProjectsUiState(
    val loading: Boolean = true,
    val projects: List<ProjectSummary> = emptyList(),
)

sealed interface ProjectsEffect {
    data class OpenProject(val id: String) : ProjectsEffect
    data class Message(val text: String) : ProjectsEffect
}

@HiltViewModel
class ProjectsViewModel @Inject constructor(
    private val repository: ProjectRepository,
) : ViewModel() {

    val state: StateFlow<ProjectsUiState> = repository.observeProjects()
        .map { ProjectsUiState(loading = false, projects = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectsUiState())

    private val _effects = Channel<ProjectsEffect>(Channel.BUFFERED)
    val effects: Flow<ProjectsEffect> = _effects.receiveAsFlow()

    fun create(name: String) = viewModelScope.launch {
        val id = repository.create(name)
        _effects.send(ProjectsEffect.OpenProject(id))
    }

    fun duplicate(id: String) = viewModelScope.launch {
        if (repository.duplicate(id) == null) _effects.send(ProjectsEffect.Message("That project no longer exists"))
    }

    fun rename(id: String, name: String) = viewModelScope.launch { repository.rename(id, name) }

    fun delete(id: String) = viewModelScope.launch { repository.delete(id) }
}
