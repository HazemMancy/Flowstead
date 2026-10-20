/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.flowsheet

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.flowstead.core.model.EditRejected
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.FlowsheetEditing
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.Port
import app.flowstead.core.model.SimObject
import app.flowstead.core.model.obj
import app.flowstead.core.model.Spec
import app.flowstead.core.model.SpecStatus
import app.flowstead.core.model.Specification
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.UnitSet
import app.flowstead.data.ProjectRepository
import app.flowstead.data.settings.AppSettings
import app.flowstead.data.settings.SettingsRepository
import app.flowstead.ui.nav.FlowsheetRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FlowsheetUi(
    val projectName: String,
    val flowsheet: Flowsheet,
    val status: Map<String, SpecStatus>,
    val problems: List<Pair<String?, String>>,
    val selectedId: String?,
    val editingId: String?,
    val canUndo: Boolean,
    val unitSet: UnitSet,
    val unitContext: UnitContext,
)

sealed interface FlowsheetUiState {
    data object Loading : FlowsheetUiState
    data object NotFound : FlowsheetUiState
    data class Ready(val ui: FlowsheetUi) : FlowsheetUiState
}

/**
 * Owns one project's flowsheet while it is open. Every edit goes through [FlowsheetEditing]
 * (pure, tested in :core), is saved straight to Room and can be undone.
 */
@HiltViewModel
class FlowsheetViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: ProjectRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val projectId = savedState.toRoute<FlowsheetRoute>().projectId

    private data class Local(
        val selectedId: String? = null,
        val editingId: String? = null,
        val undo: List<Flowsheet> = emptyList(),
    )

    private val local = MutableStateFlow(Local())
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** Latest flowsheet, kept so edits apply on top of the newest version. */
    private var current: Flowsheet? = null

    val state: StateFlow<FlowsheetUiState> = combine(
        repository.observeProject(projectId),
        settingsRepository.settings,
        local,
    ) { project, settings: AppSettings, l ->
        if (project == null) return@combine FlowsheetUiState.NotFound
        val fs = project.flowsheet
        current = fs
        FlowsheetUiState.Ready(
            FlowsheetUi(
                projectName = project.name,
                flowsheet = fs,
                status = fs.objects.associate { it.id to Specification.status(fs, it) },
                problems = Specification.problems(fs),
                selectedId = l.selectedId?.takeIf { id -> fs.objects.any { it.id == id } },
                editingId = l.editingId?.takeIf { id -> fs.objects.any { it.id == id } },
                canUndo = l.undo.isNotEmpty(),
                unitSet = settings.unitSet,
                unitContext = UnitContext(fs.atmosphericPressurePa),
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FlowsheetUiState.Loading)

    // ---- selection and editor

    fun select(id: String?) = local.update { it.copy(selectedId = id) }
    fun openEditor(id: String) = local.update { it.copy(selectedId = id, editingId = id) }
    fun closeEditor() = local.update { it.copy(editingId = null) }

    // ---- edits

    fun add(type: ObjectType, x: Double, y: Double) = edit { fs ->
        val (next, obj) = FlowsheetEditing.add(fs, type, x, y)
        local.update { it.copy(selectedId = obj.id) }
        next
    }

    fun move(id: String, x: Double, y: Double) = edit(undoable = true) { FlowsheetEditing.move(it, id, x, y) }

    fun delete(id: String) = edit { fs ->
        local.update { it.copy(selectedId = null, editingId = null) }
        FlowsheetEditing.delete(fs, id)
    }

    fun rename(id: String, tag: String) = edit { FlowsheetEditing.rename(it, id, tag) }

    fun connect(streamId: String, unitId: String, port: Port) = edit { FlowsheetEditing.connect(it, streamId, unitId, port) }

    fun connectNewStream(unitId: String, port: Port, x: Double, y: Double) = edit { fs ->
        FlowsheetEditing.connectNewStream(fs, unitId, port, x, y).first
    }

    /** A wire dragged from [port] on [unitId] was dropped on [target] at world ([x], [y]). */
    fun wire(unitId: String, port: Port, target: Hit, x: Double, y: Double) = edit { fs ->
        val unit = fs.obj(unitId) ?: throw EditRejected("That unit operation no longer exists")
        fun midpoint(other: SimObject) = (unit.x + other.x) / 2 to (unit.y + other.y) / 2
        when (target) {
            Hit.Empty -> FlowsheetEditing.connectNewStream(fs, unitId, port, x, y).first
            is Hit.OnPort -> {
                if (target.objectId == unitId) return@edit fs
                val other = fs.obj(target.objectId) ?: return@edit fs
                val (mx, my) = midpoint(other)
                FlowsheetEditing.connectUnits(fs, unitId, port, other.id, target.port, mx, my)
            }
            is Hit.OnObject -> {
                val other = fs.obj(target.objectId) ?: return@edit fs
                when {
                    other.id == unitId -> fs
                    other.type.isStream -> FlowsheetEditing.connect(fs, other.id, unitId, port)
                    else -> {
                        val free = FlowsheetEditing.firstFreePort(fs, other.id, port)
                            ?: throw EditRejected("${other.tag} has no free port for this connection")
                        val (mx, my) = midpoint(other)
                        FlowsheetEditing.connectUnits(fs, unitId, port, other.id, free, mx, my)
                    }
                }
            }
        }
    }

    fun disconnectAll(id: String) = edit { fs ->
        fs.copy(connections = fs.connections.filterNot { it.fromObject == id || it.toObject == id })
    }

    fun setSpec(id: String, key: String, spec: Spec?) = edit { FlowsheetEditing.setSpec(it, id, key, spec) }

    fun setExclusiveSpec(id: String, key: String, spec: Spec, group: Collection<String>) =
        edit { FlowsheetEditing.setExclusiveSpec(it, id, key, spec, group) }

    fun setComposition(id: String, fractions: Map<String, Double>) =
        edit { FlowsheetEditing.setComposition(it, id, fractions) }

    fun normaliseComposition(id: String, fractions: Map<String, Double>) =
        edit { FlowsheetEditing.setComposition(it, id, FlowsheetEditing.normalise(fractions)) }

    fun setAtmosphericPressure(pa: Double) = edit { fs ->
        if (!(pa.isFinite() && pa > 0)) throw EditRejected("Atmospheric pressure must be positive")
        fs.copy(atmosphericPressurePa = pa)
    }

    fun undo() {
        val previous = local.value.undo.lastOrNull() ?: return
        local.update { it.copy(undo = it.undo.dropLast(1)) }
        viewModelScope.launch { repository.saveFlowsheet(projectId, previous) }
    }

    private fun edit(undoable: Boolean = true, block: (Flowsheet) -> Flowsheet) {
        val before = current ?: return
        val after = try {
            block(before)
        } catch (e: EditRejected) {
            _messages.trySend(e.message ?: "That change isn't allowed")
            return
        } catch (e: IllegalArgumentException) {
            _messages.trySend(e.message ?: "That change isn't allowed")
            return
        }
        if (after == before) return
        current = after
        if (undoable) local.update { it.copy(undo = (it.undo + before).takeLast(UNDO_DEPTH)) }
        viewModelScope.launch { repository.saveFlowsheet(projectId, after) }
    }

    companion object {
        const val UNDO_DEPTH = 50
    }
}
