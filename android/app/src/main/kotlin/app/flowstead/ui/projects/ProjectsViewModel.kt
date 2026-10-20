/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.projects

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.flowstead.core.api.DwsimFile
import app.flowstead.data.ProjectRepository
import app.flowstead.data.ProjectSummary
import app.flowstead.data.engine.EngineClient
import app.flowstead.data.engine.EngineResult
import app.flowstead.data.files.DocumentIo
import app.flowstead.data.settings.SecretStore
import app.flowstead.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Base64
import javax.inject.Inject

data class ProjectsUiState(
    val loading: Boolean = true,
    val projects: List<ProjectSummary> = emptyList(),
    val importing: Boolean = false,
)

sealed interface ProjectsEffect {
    data class OpenProject(val id: String) : ProjectsEffect
    data class Message(val text: String) : ProjectsEffect
    /** Imported, but some of the file could not be carried over: show every warning before opening. */
    data class ImportReport(val id: String, val name: String, val warnings: List<String>) : ProjectsEffect
}

@HiltViewModel
class ProjectsViewModel @Inject constructor(
    private val repository: ProjectRepository,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val engine: EngineClient,
    private val documents: DocumentIo,
) : ViewModel() {

    private val importing = MutableStateFlow(false)

    val state: StateFlow<ProjectsUiState> = combine(repository.observeProjects(), importing) { projects, busy ->
        ProjectsUiState(loading = false, projects = projects, importing = busy)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectsUiState())

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

    /** Imports a DWSIM file as a new project. The engine reads the file; the phone keeps the result. */
    fun import(uri: Uri) {
        if (importing.value) return
        importing.value = true
        viewModelScope.launch {
            try {
                val url = settings.settings.first().engineUrl
                if (url.isBlank()) {
                    _effects.send(ProjectsEffect.Message("Set the engine address in Settings to import DWSIM files"))
                    return@launch
                }
                val doc = try {
                    documents.read(uri)
                } catch (e: IOException) {
                    _effects.send(ProjectsEffect.Message("Couldn't read the file: ${e.message ?: "unknown error"}"))
                    return@launch
                }
                val file = DwsimFile(Base64.getEncoder().encodeToString(doc.bytes))
                when (val r = engine.importDwsim(url, secrets.apiKey(), file)) {
                    is EngineResult.Failed -> _effects.send(ProjectsEffect.Message("Not imported: ${r.reason}"))
                    is EngineResult.Ok -> {
                        val name = projectName(doc.displayName)
                        val id = repository.create(name, r.value.flowsheet)
                        _effects.send(
                            if (r.value.warnings.isEmpty()) ProjectsEffect.OpenProject(id)
                            else ProjectsEffect.ImportReport(id, name, r.value.warnings),
                        )
                    }
                }
            } finally {
                importing.value = false
            }
        }
    }

    companion object {
        /** "Gas plant.dwxmz" → "Gas plant". */
        fun projectName(fileName: String): String =
            fileName.substringBeforeLast('.').takeIf { it.isNotBlank() && fileName.contains('.') } ?: fileName
    }
}
