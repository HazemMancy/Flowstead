/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.flowsheet

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.flowstead.BuildConfig
import app.flowstead.core.export.DataSheet
import app.flowstead.core.export.DataSheetWorkbook
import app.flowstead.core.export.HeatMaterialBalance
import app.flowstead.core.export.DataSheets
import app.flowstead.core.model.DocumentInfo
import app.flowstead.core.model.EditRejected
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.FlowsheetEditing
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.Port
import app.flowstead.core.model.Reaction
import app.flowstead.core.model.SizingCriteria
import app.flowstead.core.model.SimObject
import app.flowstead.core.model.obj
import app.flowstead.core.model.Spec
import app.flowstead.core.model.SpecStatus
import app.flowstead.core.model.Specification
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.UnitSet
import app.flowstead.core.api.SolveResult
import app.flowstead.core.api.SolveStatus
import app.flowstead.core.model.ResultView
import app.flowstead.core.model.StoredSolve
import app.flowstead.core.model.modelHash
import app.flowstead.data.Clock
import app.flowstead.data.ProjectRepository
import app.flowstead.data.engine.EngineClient
import app.flowstead.data.engine.EngineResult
import app.flowstead.data.engine.ON_DEVICE_ENGINE
import app.flowstead.data.files.DocumentIo
import app.flowstead.data.queue.SolveQueue
import app.flowstead.core.export.Csv
import app.flowstead.core.export.ResultsWorkbook
import app.flowstead.core.export.Xlsx
import app.flowstead.data.settings.SecretStore
import app.flowstead.data.settings.AppSettings
import app.flowstead.data.settings.SettingsRepository
import app.flowstead.ui.datasheet.DataSheetPdf
import app.flowstead.ui.datasheet.PfdPdf
import app.flowstead.ui.datasheet.FlowsteadLogo
import app.flowstead.ui.nav.FlowsheetRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface SolveState {
    data object Idle : SolveState
    data object Solving : SolveState
    /** The engine was unreachable; the solve runs by itself once the phone is back online. */
    data object Queued : SolveState
}

enum class ResultsFormat(val mimeType: String, val extension: String) {
    Csv("text/csv", "csv"),
    Xlsx("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
}

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
    val results: ResultView,
    val solveState: SolveState,
    val engineConfigured: Boolean,
    val exporting: Boolean = false,
    val editingSizingCriteria: Boolean = false,
    /** Data sheet being prepared: an equipment id, or [ALL_EQUIPMENT]; null when closed. */
    val dataSheetFor: String? = null,
)

/** [FlowsheetUi.dataSheetFor] for one document with every equipment's data sheet. */
const val ALL_EQUIPMENT = "__all"

enum class DataSheetFormat(val extension: String, val mimeType: String) {
    Pdf("pdf", "application/pdf"),
    Xlsx("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
}

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
    private val settingsRepository: SettingsRepository,
    private val secrets: SecretStore,
    private val engine: EngineClient,
    private val queue: SolveQueue,
    private val documents: DocumentIo,
    private val clock: Clock,
) : ViewModel() {

    private val projectId = savedState.toRoute<FlowsheetRoute>().projectId

    private data class Local(
        val selectedId: String? = null,
        val editingId: String? = null,
        val undo: List<Flowsheet> = emptyList(),
        val solving: Boolean = false,
        val exporting: Boolean = false,
        val editingSizingCriteria: Boolean = false,
        val dataSheetFor: String? = null,
    )

    private val local = MutableStateFlow(Local())
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** Latest flowsheet, kept so edits apply on top of the newest version. */
    private var current: Flowsheet? = null
    private var currentSolve: StoredSolve? = null
    private var currentName: String = ""

    val state: StateFlow<FlowsheetUiState> = combine(
        repository.observeProject(projectId),
        settingsRepository.settings,
        local,
        queue.observeQueued(projectId),
    ) { project, settings: AppSettings, l, queued ->
        if (project == null) return@combine FlowsheetUiState.NotFound
        val fs = project.flowsheet
        current = fs
        currentSolve = project.solve
        currentName = project.name
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
                results = ResultView(project.solve, fs.modelHash()),
                solveState = when {
                    l.solving -> SolveState.Solving
                    queued -> SolveState.Queued
                    else -> SolveState.Idle
                },
                engineConfigured = settings.engineTarget.isNotBlank(),
                exporting = l.exporting,
                editingSizingCriteria = l.editingSizingCriteria,
                dataSheetFor = l.dataSheetFor?.takeIf { id -> id == ALL_EQUIPMENT || fs.objects.any { it.id == id } },
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FlowsheetUiState.Loading)

    init {
        // On-device engine: start loading DWSIM while the user edits, so the first solve is quick.
        viewModelScope.launch {
            if (settingsRepository.settings.first().engineTarget == ON_DEVICE_ENGINE) engine.health(ON_DEVICE_ENGINE, null)
        }
    }

    // ---- selection and editor

    fun select(id: String?) = local.update { it.copy(selectedId = id) }
    fun openEditor(id: String) = local.update { it.copy(selectedId = id, editingId = id) }
    fun closeEditor() = local.update { it.copy(editingId = null) }
    fun openSizingCriteria() = local.update { it.copy(editingSizingCriteria = true) }
    fun closeSizingCriteria() = local.update { it.copy(editingSizingCriteria = false) }
    fun openDataSheet(id: String?) = local.update { it.copy(dataSheetFor = id ?: ALL_EQUIPMENT, editingId = null) }
    fun closeDataSheet() = local.update { it.copy(dataSheetFor = null) }

    // ---- edits

    fun add(type: ObjectType, x: Double, y: Double) = edit { fs ->
        val (next, obj) = FlowsheetEditing.add(fs, type, x, y)
        local.update { it.copy(selectedId = obj.id) }
        next
    }

    fun move(id: String, x: Double, y: Double) = edit(undoable = true) { FlowsheetEditing.move(it, id, x, y) }
    fun setTable(id: String, keys: List<String>) = edit { FlowsheetEditing.setTable(it, id, keys) }
    fun moveTable(id: String, dx: Double, dy: Double) = edit { FlowsheetEditing.moveTable(it, id, dx, dy) }

    fun delete(id: String) = edit { fs ->
        local.update { it.copy(selectedId = null, editingId = null) }
        FlowsheetEditing.delete(fs, id)
    }

    fun rename(id: String, tag: String) = edit { FlowsheetEditing.rename(it, id, tag) }

    fun connect(streamId: String, unitId: String, port: Port) = edit { FlowsheetEditing.connect(it, streamId, unitId, port) }

    fun connectNewStream(unitId: String, port: Port, x: Double, y: Double) = edit { fs ->
        FlowsheetEditing.connectNewStream(fs, unitId, port, x, y).first
    }

    /** Wiring from the editor's Connections list (no drag): an existing stream, or a new one placed beside the port. */
    fun connectPort(unitId: String, port: Port, streamId: String?) = edit { fs ->
        if (streamId != null) return@edit FlowsheetEditing.connect(fs, streamId, unitId, port)
        val unit = fs.obj(unitId) ?: throw EditRejected("That unit operation no longer exists")
        val (x, y) = FlowsheetEditing.newStreamPosition(unit, port)
        FlowsheetEditing.connectNewStream(fs, unitId, port, x, y).first
    }

    fun disconnectPort(unitId: String, port: Port) = edit { FlowsheetEditing.disconnectPort(it, unitId, port) }

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

    fun setRef(id: String, key: String, value: String?) = edit { FlowsheetEditing.setRef(it, id, key, value) }

    fun setPipeSize(id: String, nps: String?, schedule: String?) = edit { FlowsheetEditing.setPipeSize(it, id, nps, schedule) }

    fun setSizingCriteria(c: SizingCriteria) = edit { FlowsheetEditing.setSizingCriteria(it, c) }

    fun setDocumentInfo(info: DocumentInfo) = edit { FlowsheetEditing.setDocumentInfo(it, info) }

    fun setSheetField(id: String, key: String, value: String) = edit { FlowsheetEditing.setSheetField(it, id, key, value) }

    fun upsertReaction(reaction: Reaction) = edit { FlowsheetEditing.upsertReaction(it, reaction) }

    fun deleteReaction(id: String) = edit { FlowsheetEditing.deleteReaction(it, id) }

    fun setComposition(id: String, fractions: Map<String, Double>) =
        edit { FlowsheetEditing.setComposition(it, id, fractions) }

    fun normaliseComposition(id: String, fractions: Map<String, Double>) =
        edit { FlowsheetEditing.setComposition(it, id, FlowsheetEditing.normalise(fractions)) }

    fun setAtmosphericPressure(pa: Double) = edit { fs ->
        if (!(pa.isFinite() && pa > 0)) throw EditRejected("Atmospheric pressure must be positive")
        fs.copy(atmosphericPressurePa = pa)
    }

    /**
     * Sends the flowsheet as it is now to the engine and keeps the result with the project.
     * Local degrees-of-freedom problems are reported first, so the engine only sees complete input.
     */
    fun solve() {
        val fs = current ?: return
        if (local.value.solving) return
        val problems = Specification.problems(fs)
        if (problems.isNotEmpty()) {
            _messages.trySend("Fix ${problems.size} issue(s) first: ${problems.first().second}")
            return
        }
        local.update { it.copy(solving = true) }
        viewModelScope.launch {
            try {
                val url = settingsRepository.settings.first().engineTarget
                if (url.isBlank()) {
                    _messages.send("Set the engine address in Settings to solve")
                    return@launch
                }
                when (val r = engine.solve(url, secrets.apiKey(), fs)) {
                    is EngineResult.Failed -> if (r.unreachable) {
                        queue.enqueue(projectId)
                        _messages.send("Engine unreachable. Queued: it will solve when the phone is back online")
                    } else {
                        _messages.send("Not solved: ${r.reason}")
                    }
                    is EngineResult.Ok -> {
                        queue.cancel(projectId)
                        repository.saveSolve(projectId, StoredSolve(r.value, fs.modelHash(), clock.now()))
                        _messages.send(summary(r.value))
                    }
                }
            } finally {
                local.update { it.copy(solving = false) }
            }
        }
    }

    fun cancelQueuedSolve() = queue.cancel(projectId)

    /** Suggested file names for the system "save as" picker. */
    fun exportFileName(extension: String, suffix: String = ""): String {
        val base = currentName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "flowsheet" }
        return "$base$suffix.$extension"
    }

    /** Writes the flowsheet as a DWSIM .dwxmz (solved by the engine, so it opens with results in desktop DWSIM). */
    fun exportDwsim(uri: Uri) = export {
        val fs = current ?: return@export
        val url = settingsRepository.settings.first().engineTarget
        if (url.isBlank()) {
            _messages.send("Set the engine address in Settings to export DWSIM files")
            return@export
        }
        when (val r = engine.exportDwsim(url, secrets.apiKey(), fs)) {
            is EngineResult.Failed -> _messages.send("Not exported: ${r.reason}")
            is EngineResult.Ok -> {
                documents.write(uri, java.util.Base64.getDecoder().decode(r.value.data))
                val notes = r.value.warnings
                _messages.send(if (notes.isEmpty()) "Exported DWSIM file" else "Exported DWSIM file. Note: ${notes.joinToString(" ")}")
            }
        }
    }

    /** Writes the stored results (engine values only) as CSV or Excel. Works offline. */
    fun exportResults(uri: Uri, format: ResultsFormat) = export {
        val fs = current ?: return@export
        val solve = currentSolve ?: run {
            _messages.send("Solve first: there are no results to export")
            return@export
        }
        val unitSet = settingsRepository.settings.first().unitSet
        val sheets = ResultsWorkbook.build(currentName, fs, solve, unitSet, BuildConfig.VERSION_NAME)
        val bytes = when (format) {
            ResultsFormat.Csv -> Csv.write(sheets).toByteArray(Charsets.UTF_8)
            ResultsFormat.Xlsx -> Xlsx.write(sheets)
        }
        documents.write(uri, bytes)
        _messages.send(if (fs.modelHash() == solve.modelHash) "Results exported" else "Results exported (out of date: the flowsheet changed after the solve)")
    }

    /** "<tag> data sheet.pdf|xlsx", or "<project> data sheets…" for every equipment. */
    fun dataSheetFileName(id: String, format: DataSheetFormat): String {
        if (id == ALL_EQUIPMENT) return exportFileName(format.extension, " data sheets")
        val tag = current?.obj(id)?.tag?.replace(Regex("[\\\\/:*?\"<>|]"), "_")?.trim().orEmpty().ifEmpty { "equipment" }
        return "$tag data sheet.${format.extension}"
    }

    /** Equipment data sheets as a PDF or Excel workbook, from the stored solve (blank process data if there is none). */
    fun exportDataSheets(uri: Uri, id: String, format: DataSheetFormat) = export {
        val fs = current ?: return@export
        val unitSet = settingsRepository.settings.first().unitSet
        val targets = if (id == ALL_EQUIPMENT) fs.objects.filter { DataSheets.isEquipment(it.type) } else listOfNotNull(fs.obj(id))
        if (targets.isEmpty()) {
            _messages.send("There is no equipment to make a data sheet for")
            return@export
        }
        val sheets = targets.map { DataSheets.build(currentName, fs, currentSolve, unitSet, it, today(), BuildConfig.VERSION_NAME) }
        documents.write(uri, render(sheets, format))
        val stale = currentSolve?.let { fs.modelHash() != it.modelHash } ?: false
        _messages.send(
            (if (sheets.size == 1) "Data sheet created" else "${sheets.size} data sheets created") +
                when {
                    currentSolve == null -> " (not solved: process data are blank)"
                    stale -> " (results out of date)"
                    else -> ""
                },
        )
    }

    /** The heat and material balance (all streams, energy streams, duties) as an A3 PDF or a workbook. */
    fun exportHeatMaterialBalance(uri: Uri, format: DataSheetFormat) = export {
        val fs = current ?: return@export
        val unitSet = settingsRepository.settings.first().unitSet
        val perSheet = if (format == DataSheetFormat.Pdf) HeatMaterialBalance.STREAMS_PER_PDF_SHEET else Int.MAX_VALUE
        val sheets = HeatMaterialBalance.build(currentName, fs, currentSolve, unitSet, today(), BuildConfig.VERSION_NAME, perSheet)
        documents.write(uri, render(sheets, format))
        val stale = currentSolve?.let { fs.modelHash() != it.modelHash } ?: false
        _messages.send(
            "Heat and material balance created" + when {
                currentSolve == null -> " (not solved: the balance is blank)"
                stale -> " (results out of date)"
                else -> ""
            },
        )
    }

    /** The flowsheet as an A3 landscape PFD (with its property tables) and the project title block. */
    fun exportPfd(uri: Uri) = export {
        val fs = current ?: return@export
        val unitSet = settingsRepository.settings.first().unitSet
        val solve = currentSolve
        val view = ResultView(solve, fs.modelHash())
        val header = PfdPdf.Header(
            projectName = currentName,
            info = fs.documentInfo ?: DocumentInfo(),
            date = today(),
            units = unitSet.name,
            propertyPackage = fs.propertyPackage,
            footer = "Flowstead ${BuildConfig.VERSION_NAME}" + (solve?.result?.let { " · DWSIM ${it.dwsimVersion}" } ?: ""),
            stale = view.stale,
            solved = solve != null,
        )
        val bytes = withContext(Dispatchers.Default) {
            java.io.ByteArrayOutputStream().also { PfdPdf.write(fs, view, unitSet, header, it) }.toByteArray()
        }
        documents.write(uri, bytes)
        _messages.send("PFD created")
    }

    private fun today(): String =
        java.time.Instant.ofEpochMilli(clock.now()).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()

    private suspend fun render(sheets: List<DataSheet>, format: DataSheetFormat): ByteArray = withContext(Dispatchers.Default) {
        when (format) {
            DataSheetFormat.Pdf -> java.io.ByteArrayOutputStream().also { DataSheetPdf.write(sheets, it) }.toByteArray()
            DataSheetFormat.Xlsx -> {
                val (png, size) = FlowsteadLogo.png(heightPx = 96)
                DataSheetWorkbook.write(sheets, DataSheetWorkbook.Logo(png, size[0], size[1]))
            }
        }
    }

    private fun export(block: suspend () -> Unit) {
        if (local.value.exporting) return
        local.update { it.copy(exporting = true) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: java.io.IOException) {
                _messages.send("Couldn't write the file: ${e.message ?: "unknown error"}")
            } finally {
                local.update { it.copy(exporting = false) }
            }
        }
    }

    private fun summary(r: SolveResult): String = when (r.status) {
        SolveStatus.Converged -> "Converged in %.1f s".format(java.util.Locale.ROOT, r.elapsedMs / 1000.0)
        SolveStatus.Failed -> "Solve failed: ${r.messages.firstOrNull()?.text ?: "see the solver log"}"
        SolveStatus.Invalid -> "Engine refused the flowsheet: ${r.messages.firstOrNull()?.text ?: "see the solver log"}"
        SolveStatus.Timeout -> "Solve timed out: ${r.messages.firstOrNull()?.text ?: ""}".trimEnd(' ', ':')
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
