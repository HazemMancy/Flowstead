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
import app.flowstead.core.model.CaseResults
import app.flowstead.core.model.CaseStudies
import app.flowstead.core.model.CaseStudy
import app.flowstead.core.model.DepressuringCase
import app.flowstead.core.model.FlowsheetEditing
import app.flowstead.core.model.ReliefDeviceDef
import app.flowstead.core.model.ResultView
import app.flowstead.core.model.Safety
import app.flowstead.core.model.SubflowsheetSolve
import app.flowstead.core.model.Subflowsheets
import app.flowstead.core.model.Subflowsheet
import app.flowstead.core.model.PerformanceCurve
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.Port
import app.flowstead.core.model.Reaction
import app.flowstead.core.model.SizingCriteria
import app.flowstead.core.model.SimObject
import app.flowstead.core.model.obj
import app.flowstead.core.model.Spec
import app.flowstead.core.model.SpecStatus
import app.flowstead.core.model.Readiness
import app.flowstead.core.model.Specification
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.UnitSet
import app.flowstead.core.api.PhaseEnvelopeRequest
import app.flowstead.core.api.PvtRequest
import app.flowstead.core.model.PvtTuning
import app.flowstead.core.api.PvtRegressionRequest
import app.flowstead.core.api.PvtObservation
import app.flowstead.core.api.ReliefRequest
import app.flowstead.core.api.SeparatorStage
import app.flowstead.core.api.SolveResult
import app.flowstead.core.api.SolveStatus
import app.flowstead.core.model.StoredSolve
import app.flowstead.core.model.StreamCopy
import app.flowstead.core.model.CompositionBasis
import app.flowstead.core.model.isFeed
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
    /** Sub-flowsheets (D133): the flowsheet shown (null the main one), the path to it, its blocks and the selected block. */
    val level: String? = null,
    val path: List<Subflowsheet> = emptyList(),
    val blocks: List<BlockView> = emptyList(),
    val selectedBlock: String? = null,
    val streamNotes: Map<String, String> = emptyMap(),
    /** The Select tool (D139): taps build a selection of several objects, to move, group or drag into a block together. */
    val selecting: Boolean = false,
    val multiSelected: Set<String> = emptySet(),
    val status: Map<String, SpecStatus>,
    val problems: List<Pair<String?, String>>,
    /** Problems that stop any solve (no package, no components…); the rest only leave their objects out (D83). */
    val blocking: List<Pair<String?, String>> = emptyList(),
    /** Objects the solver leaves out, with why: shown in red (D83). */
    val notReady: Map<String, String> = emptyMap(),
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
    /** The last phase envelope asked for, while the project is open (D50). */
    val envelope: EnvelopeUi? = null,
    /** The last distillation curves asked for (D99). */
    val petroleum: PetroleumUi? = null,
    val caseStudiesOpen: Boolean = false,
    /** A case study running now (D51). */
    val caseRun: CaseRunUi? = null,
    /** The stream whose PVT sheet is open (D53), and the last experiment run. */
    val pvtFor: String? = null,
    val pvt: PvtUi? = null,
    val tuning: TuningUi? = null,
    val safetyOpen: Boolean = false,
    val safetyRunning: Set<String> = emptySet(),
    /** How compositions are entered and shown (D78), and the engine's molecular weights and liquid densities for it. */
    val compositionBasis: CompositionBasis = CompositionBasis.MoleFraction,
    val compoundBasis: CompoundBasisUi? = null,
    val dynamicsOpen: Boolean = false,
    val dynamics: DynamicsUi? = null,
    /** The flowsheet tools sheet (D105, D106) and its runs. */
    val toolsOpen: Boolean = false,
    val optimiser: ToolRunUi<app.flowstead.core.api.OptimiseResult>? = null,
    val pidTest: ToolRunUi<PidTestUi>? = null,
    val mcCabe: ToolRunUi<app.flowstead.core.api.BinaryDiagramResult>? = null,
)

/** A tool's run: working, its result, or why it failed. */
data class ToolRunUi<T>(val running: Boolean = false, val result: T? = null, val error: String? = null)

/** A PID step test (D105): the run, the measurement in % of span, the fitted model and the settings. */
data class PidTestUi(
    val controllerId: String,
    val times: List<Double>,
    val pvPercent: List<Double>,
    val stepTime: Double,
    val step: Double,
    val model: app.flowstead.core.model.Fopdt?,
    val settings: List<app.flowstead.core.model.PidSettings>,
)

/**
 * The last dynamic run (D86), kept in memory: [key] is the flowsheet and setup it ran for, so a change marks it out of
 * date.
 */
data class DynamicsUi(
    val running: Boolean = false,
    val result: app.flowstead.core.api.DynamicsResult? = null,
    val key: String? = null,
    val error: String? = null,
)

/** The engine's molecular weights and ideal liquid densities for the fluid [key] (package, compounds, hypotheticals). */
data class CompoundBasisUi(
    val key: String,
    val data: Map<String, app.flowstead.core.api.CompoundBasis>? = null,
    val loading: Boolean = false,
    val error: String? = null,
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
        val level: String? = null,
        val selecting: Boolean = false,
        val multi: Set<String> = emptySet(),
        val selectedBlock: String? = null,
        val selectedId: String? = null,
        val editingId: String? = null,
        val undo: List<Flowsheet> = emptyList(),
        val solving: Boolean = false,
        val exporting: Boolean = false,
        val editingSizingCriteria: Boolean = false,
        val dataSheetFor: String? = null,
        val envelope: EnvelopeUi? = null,
        /** Model hash the envelope was traced for. */
        val envelopeHash: String? = null,
        val petroleum: PetroleumUi? = null,
        val petroleumHash: String? = null,
        val caseStudiesOpen: Boolean = false,
        val caseRun: CaseRunUi? = null,
        val pvtFor: String? = null,
        val pvt: PvtUi? = null,
        val pvtHash: String? = null,
        val tuning: TuningUi? = null,
        val safetyOpen: Boolean = false,
        /** Ids of relief valves and depressuring cases the engine is working on. */
        val safetyRunning: Set<String> = emptySet(),
        val compositionBasis: CompositionBasis = CompositionBasis.MoleFraction,
        val compoundBasis: CompoundBasisUi? = null,
        val dynamicsOpen: Boolean = false,
        val dynamics: DynamicsUi? = null,
        val toolsOpen: Boolean = false,
        val optimiser: ToolRunUi<app.flowstead.core.api.OptimiseResult>? = null,
        val pidTest: ToolRunUi<PidTestUi>? = null,
        val mcCabe: ToolRunUi<app.flowstead.core.api.BinaryDiagramResult>? = null,
    )

    private val local = MutableStateFlow(Local())
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** Latest flowsheet, kept so edits apply on top of the newest version. */
    private var current: Flowsheet? = null
    private var currentSolve: StoredSolve? = null
    private var currentName: String = ""
    private var autoSolve: Job? = null
    private var caseJob: Job? = null
    private var solveAgain = false

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
                level = l.level?.takeIf { Subflowsheets.of(fs, it) != null },
                path = Subflowsheets.path(fs, l.level),
                blocks = blockViews(fs, l.level?.takeIf { Subflowsheets.of(fs, it) != null }),
                selectedBlock = l.selectedBlock?.takeIf { Subflowsheets.of(fs, it) != null },
                streamNotes = streamNotes(fs, l.level),
                selecting = l.selecting,
                multiSelected = l.multi.filter { id -> fs.objects.any { it.id == id && it.parent == l.level } }.toSet(),
                status = Readiness.statusOf(fs),
                problems = Specification.problems(fs),
                blocking = Specification.blocking(fs),
                notReady = Readiness.of(fs).notReady,
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
                envelope = l.envelope?.copy(stale = l.envelopeHash != null && l.envelopeHash != fs.modelHash()),
                petroleum = l.petroleum?.copy(stale = l.petroleumHash != null && l.petroleumHash != fs.modelHash()),
                caseStudiesOpen = l.caseStudiesOpen,
                dynamicsOpen = l.dynamicsOpen,
                dynamics = l.dynamics,
                toolsOpen = l.toolsOpen,
                optimiser = l.optimiser,
                pidTest = l.pidTest,
                mcCabe = l.mcCabe,
                caseRun = l.caseRun,
                pvtFor = l.pvtFor?.takeIf { id -> fs.objects.any { it.id == id } },
                pvt = l.pvt?.copy(stale = l.pvtHash != null && l.pvtHash != fs.modelHash()),
                tuning = l.tuning,
                safetyOpen = l.safetyOpen,
                safetyRunning = l.safetyRunning,
                compositionBasis = l.compositionBasis,
                compoundBasis = l.compoundBasis?.takeIf { it.key == fluidKey(fs) },
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FlowsheetUiState.Loading)

    init {
        // On-device engine: start loading DWSIM while the user edits, so the first solve is quick.
        viewModelScope.launch {
            if (settingsRepository.settings.first().engineTarget == ON_DEVICE_ENGINE) engine.health(ON_DEVICE_ENGINE, null)
        }
    }

    // ---- sub-flowsheets (D133): blocks that hold a flowsheet, as HYSYS's

    private fun blockViews(fs: Flowsheet, level: String?): List<BlockView> = Subflowsheets.blocksAt(fs, level).map { b ->
        val bd = Subflowsheets.boundary(fs, b.id)
        BlockView(b.id, b.tag, b.x, b.y, b.fluid?.propertyPackage?.substringBefore(" ("),
            b.ports.mapNotNull { p -> p.outer?.let { o -> BlockLink(o, bd.firstOrNull { it.stream.id == p.inner }?.feed ?: true) } })
    }

    private fun streamNotes(fs: Flowsheet, level: String?): Map<String, String> {
        val b = Subflowsheets.of(fs, level) ?: return emptyMap()
        val bd = Subflowsheets.boundary(fs, b.id)
        return b.ports.mapNotNull { p ->
            val outer = p.outer?.let { o -> fs.objects.firstOrNull { it.id == o }?.tag } ?: return@mapNotNull null
            p.inner to if (bd.firstOrNull { it.stream.id == p.inner }?.feed != false) "from $outer" else "to $outer"
        }.toMap()
    }

    /** Opens a sub-flowsheet, or (null) the main flowsheet. */
    fun openLevel(id: String?) = local.update { it.copy(level = id, selectedBlock = null, selectedId = null, editingId = null, multi = emptySet()) }

    // ---- several objects at once (D139): the Select tool, then move, group, delete or drop them into a block

    fun setSelecting(on: Boolean) = local.update { it.copy(selecting = on, multi = if (on) it.multi else emptySet(), selectedId = null, editingId = null) }
    /** A tap on an object: with the Select tool it joins or leaves the selection, else its editor opens. */
    fun tapObject(id: String?) {
        if (local.value.selecting) { if (id != null) toggleSelected(id) } else if (id != null) openEditor(id) else select(null)
    }
    fun toggleSelected(id: String) = local.update { it.copy(multi = if (id in it.multi) it.multi - id else it.multi + id, selectedBlock = null) }
    fun clearSelection() = local.update { it.copy(multi = emptySet()) }

    /** Moves every object in [ids] by ([dx], [dy]), keeping their layout. */
    fun moveMany(ids: Collection<String>, dx: Double, dy: Double) = edit { fs ->
        ids.fold(fs) { acc, id -> acc.objects.firstOrNull { it.id == id }?.let { o -> FlowsheetEditing.move(acc, id, o.x + dx, o.y + dy) } ?: acc }
    }

    /** Moves [ids] into sub-flowsheet [blockId] (drag and drop, or Move into): streams that joined them to the rest cross its edge. */
    fun moveIntoBlock(ids: Collection<String>, blockId: String) {
        val fs = current ?: return
        val tag = Subflowsheets.of(fs, blockId)?.tag ?: return
        val before = current
        edit { Subflowsheets.moveInto(it, ids, blockId) }
        if (current !== before) {
            local.update { it.copy(multi = emptySet(), selectedId = null, editingId = null) }
            _messages.trySend("Moved ${if (ids.size == 1) fs.objects.firstOrNull { it.id == ids.first() }?.tag ?: "it" else "${ids.size} objects"} into $tag")
        }
    }

    /** Puts the selected objects in a new sub-flowsheet where they are. */
    fun groupSelected() {
        val ids = local.value.multi.toList().ifEmpty { return }
        edit { fs ->
            val chosen = fs.objects.filter { it.id in ids }
            val (next, b) = Subflowsheets.group(fs, ids, chosen.map { it.x }.average(), chosen.map { it.y }.average())
            local.update { it.copy(selectedBlock = b.id, multi = emptySet(), selectedId = null, editingId = null) }
            next
        }
    }

    fun deleteSelected() {
        val ids = local.value.multi.toList().ifEmpty { return }
        edit { fs -> ids.fold(fs) { acc, id -> if (acc.objects.any { it.id == id }) FlowsheetEditing.delete(acc, id) else acc } }
        local.update { it.copy(multi = emptySet()) }
    }

    /** A copy of sub-flowsheet [id] with everything inside it, beside it; selected, with what the user must know. */
    fun duplicateBlock(id: String) = edit { fs ->
        val (next, b, notes) = Subflowsheets.duplicate(fs, id)
        local.update { it.copy(selectedBlock = b.id, selectedId = null, editingId = null) }
        _messages.trySend((listOf("Duplicated ${Subflowsheets.of(fs, id)?.tag} as ${b.tag}") + notes).joinToString(" · "))
        next
    }

    private var lastBlockTap: Pair<String, Long> = "" to 0L
    /** A tap selects a block; a second tap on it within a moment opens it. */
    fun tapBlock(id: String) {
        val now = System.currentTimeMillis()
        if (lastBlockTap.first == id && now - lastBlockTap.second < 450) { lastBlockTap = "" to 0L; openLevel(id); return }
        lastBlockTap = id to now
        local.update { it.copy(selectedBlock = id, selectedId = null, editingId = null) }
    }
    fun closeBlock() = local.update { it.copy(selectedBlock = null) }

    /** A new empty sub-flowsheet on the flowsheet shown, at ([x], [y]). */
    fun addBlock(x: Double, y: Double) = edit { fs ->
        val (next, b) = Subflowsheets.add(fs, local.value.level, x, y)
        local.update { it.copy(selectedBlock = b.id, selectedId = null, editingId = null) }
        next
    }
    /** Puts [id] (a unit or stream here) in a new sub-flowsheet; streams joining it to the rest stay outside, matched. */
    fun groupObject(id: String) = edit { fs ->
        val o = fs.objects.firstOrNull { it.id == id } ?: return@edit fs
        val (next, b) = Subflowsheets.group(fs, listOf(id), o.x, o.y)
        local.update { it.copy(selectedBlock = b.id, selectedId = null, editingId = null) }
        next
    }
    fun moveBlock(id: String, x: Double, y: Double) = edit { Subflowsheets.move(it, id, x, y) }
    fun renameBlock(id: String, tag: String) = edit { Subflowsheets.rename(it, id, tag) }
    fun deleteBlock(id: String) = edit { fs -> local.update { it.copy(selectedBlock = null) }; Subflowsheets.delete(fs, id) }
    fun ungroupBlock(id: String) = edit { fs -> local.update { it.copy(selectedBlock = null) }; Subflowsheets.ungroup(fs, id) }
    fun connectToBlock(blockId: String, streamId: String, feed: Boolean) = edit { Subflowsheets.connect(it, blockId, streamId, feed).first }
    fun disconnectFromBlock(blockId: String, innerId: String) = edit { Subflowsheets.disconnect(it, blockId, innerId) }
    fun setTransferBasis(blockId: String, innerId: String, basis: String) = edit { Subflowsheets.setBasis(it, blockId, innerId, basis) }
    fun rematch(blockId: String, innerId: String, outerId: String?) = edit { Subflowsheets.link(it, blockId, innerId, outerId) }
    /** Own fluid package (a copy of the parent's, to edit in the Fluid screen) or the parent's again. */
    fun setOwnFluid(blockId: String, own: Boolean) = edit { fs ->
        val b = Subflowsheets.of(fs, blockId) ?: return@edit fs
        Subflowsheets.setFluid(fs, blockId, if (own) Subflowsheets.fluidAt(fs, b.parent) else null)
    }
    /** A stream dropped on a block: into it from a free end, out of it from a free start (by where it lies when both). */
    fun dropOnBlock(streamId: String, blockId: String) = edit { fs ->
        val b = Subflowsheets.of(fs, blockId) ?: return@edit fs
        val canIn = Subflowsheets.connectable(fs, blockId, true).any { it.id == streamId }
        val canOut = Subflowsheets.connectable(fs, blockId, false).any { it.id == streamId }
        val tag = fs.objects.firstOrNull { it.id == streamId }?.tag ?: "That stream"
        val feed = when {
            canIn && canOut -> (fs.objects.first { it.id == streamId }.x < b.x)
            canIn -> true
            canOut -> false
            // A unit, or a stream joined at both ends: it goes into the block (D139).
            else -> return@edit Subflowsheets.moveInto(fs, listOf(streamId), blockId).also { _messages.trySend("Moved $tag into ${b.tag}") }
        }
        Subflowsheets.connect(fs, blockId, streamId, feed).first
    }

    // ---- selection and editor

    fun select(id: String?) = local.update { it.copy(selectedId = id, selectedBlock = if (id != null) null else it.selectedBlock) }
    fun openEditor(id: String) = local.update { it.copy(selectedId = id, editingId = id, selectedBlock = null) }
    fun closeEditor() = local.update { it.copy(editingId = null) }
    fun openSizingCriteria() = local.update { it.copy(editingSizingCriteria = true) }
    fun closeSizingCriteria() = local.update { it.copy(editingSizingCriteria = false) }
    fun openDataSheet(id: String?) = local.update { it.copy(dataSheetFor = id ?: ALL_EQUIPMENT, editingId = null) }
    fun closeDataSheet() = local.update { it.copy(dataSheetFor = null) }

    // ---- edits

    fun add(type: ObjectType, x: Double, y: Double) = edit { fs ->
        val (next, obj) = FlowsheetEditing.add(fs, type, x, y)
        local.update { it.copy(selectedId = obj.id) }
        // On the flowsheet shown: inside a sub-flowsheet the new object is one of its own (D133).
        Subflowsheets.place(next, obj.id, local.value.level)
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

    /** A new feed stream with everything [id] can give (its specs, or its current results), selected for editing. */
    fun duplicateStream(id: String) = viewModelScope.launch {
        val units = settingsRepository.settings.first().unitSet
        edit { fs ->
            val (next, copy) = StreamCopy.duplicate(fs, id, ResultView(currentSolve, fs.modelHash()), units)
            local.update { it.copy(selectedId = copy.id, editingId = copy.id) }
            next
        }
    }

    // ---- composition basis (D78)

    fun setCompositionBasis(basis: CompositionBasis) {
        local.update { it.copy(compositionBasis = basis) }
        if (basis.needsEngineData) loadCompoundBasis()
    }

    private fun fluidKey(fs: Flowsheet) = listOf(fs.propertyPackage, fs.compounds, fs.hypotheticals, fs.blackOils).toString()

    /** Asks the engine for the fluid's molecular weights and liquid densities, once per fluid. */
    fun loadCompoundBasis() {
        val fs = current ?: return
        val key = fluidKey(fs)
        local.value.compoundBasis?.let { if (it.key == key && (it.loading || it.data != null)) return }
        local.update { it.copy(compoundBasis = CompoundBasisUi(key, loading = true)) }
        viewModelScope.launch {
            val url = settingsRepository.settings.first().engineTarget
            val next = if (url.isBlank()) CompoundBasisUi(key, error = "Set the engine address in Settings")
            else when (val r = engine.compoundBasis(url, secrets.apiKey(), fs)) {
                is EngineResult.Ok -> CompoundBasisUi(key, data = r.value.associateBy { it.name })
                is EngineResult.Failed -> CompoundBasisUi(key, error = r.reason)
            }
            local.update { it.copy(compoundBasis = next) }
        }
    }

    /** Puts chosen values of another stream on feed stream [targetId]. */
    fun copyStreamData(targetId: String, items: List<StreamCopy.Item>) = edit { StreamCopy.apply(it, targetId, items) }

    fun setPipeSize(id: String, nps: String?, schedule: String?) = edit { FlowsheetEditing.setPipeSize(it, id, nps, schedule) }

    fun setCurve(id: String, curve: PerformanceCurve?) = edit { FlowsheetEditing.setCurve(it, id, curve) }
    fun setPipeSections(id: String, sections: List<app.flowstead.core.model.PipeSegmentSection>?) = edit { FlowsheetEditing.setPipeSections(it, id, sections) }
    fun setSizing(id: String, sizing: app.flowstead.core.model.SeparatorSizingInput?) = edit { FlowsheetEditing.setSizing(it, id, sizing) }
    fun setEquipmentSizing(id: String, sizing: app.flowstead.core.model.EquipmentSizingInput?) = edit { FlowsheetEditing.setEquipmentSizing(it, id, sizing) }

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
     * Sends what is ready of the flowsheet as it is now to the engine and keeps the result with the project (D83):
     * objects that are missing something, and what depends on them, are left out and shown in red.
     */
    fun solve() = solve(auto = false)

    /**
     * [auto]: started by the Active solver after a change. It stays quiet when the flowsheet is not complete yet
     * (the solve bar already says what is missing) and when it converges (the bar shows it), and never queues.
     */
    private fun solve(auto: Boolean) {
        val fs = current ?: return
        if (local.value.solving) {
            if (auto) solveAgain = true
            return
        }
        val blocking = Specification.blocking(fs)
        if (blocking.isNotEmpty()) {
            if (!auto) _messages.trySend("Fix ${blocking.size} issue(s) first: ${blocking.first().second}")
            return
        }
        val readiness = Readiness.of(fs)
        if (!readiness.anyReady) {
            if (!auto) _messages.trySend("Nothing is ready to solve yet: ${Specification.problems(fs).firstOrNull()?.second ?: "complete a feed stream"}")
            return
        }
        if (auto && currentSolve?.modelHash == fs.modelHash()) return
        local.update { it.copy(solving = true) }
        viewModelScope.launch {
            try {
                val url = settingsRepository.settings.first().engineTarget
                if (url.isBlank()) {
                    _messages.send("Set the engine address in Settings to solve")
                    return@launch
                }
                when (val r = engine.solve(url, secrets.apiKey(), readiness.solvable)) {
                    is EngineResult.Failed -> if (r.unreachable && !auto) {
                        queue.enqueue(projectId)
                        _messages.send("Engine unreachable. Queued: it will solve when the phone is back online")
                    } else {
                        _messages.send("Not solved: ${r.reason}")
                    }
                    is EngineResult.Ok -> {
                        queue.cancel(projectId)
                        repository.saveSolve(projectId, StoredSolve(r.value, fs.modelHash(), clock.now()))
                        if (!auto || r.value.status != SolveStatus.Converged) {
                            val left = readiness.notReady.size
                            _messages.send(summary(r.value) + if (left == 0) "" else " · $left not ready")
                        }
                    }
                }
            } finally {
                local.update { it.copy(solving = false) }
                // Changes made while this solve ran are solved next (Active only).
                if (solveAgain) {
                    solveAgain = false
                    if (current?.solverActive == true) solveSoon()
                }
            }
        }
    }

    /** Hold (false) or Active (true), as HYSYS's solver buttons; switching to Active solves straight away. */
    fun setSolverActive(active: Boolean) {
        edit(undoable = false) { it.copy(solverActive = active) }
        if (active) solveSoon(0)
        else {
            autoSolve?.cancel()
            solveAgain = false
        }
    }

    /** Active solver: waits for the user to stop typing or dragging, then solves the newest flowsheet. */
    private fun solveSoon(delayMs: Long = ACTIVE_DELAY_MS) {
        autoSolve?.cancel()
        autoSolve = viewModelScope.launch {
            delay(delayMs)
            solve(auto = true)
        }
    }

    fun cancelQueuedSolve() = queue.cancel(projectId)

    // ---- case studies (D51)

    // ---- dynamics (D86)

    fun openDynamics() = local.update { it.copy(dynamicsOpen = true, editingId = null) }
    fun closeDynamics() = local.update { it.copy(dynamicsOpen = false) }
    fun setDynamicsTiming(duration: Double?, step: Double?) = edit(undoable = false) { fs ->
        FlowsheetEditing.setDynamics(fs) { s -> s.copy(duration = duration ?: s.duration, step = step ?: s.step) }
    }
    fun setDynamicParameter(id: String, key: String, spec: app.flowstead.core.model.Spec?) =
        edit(undoable = false) { FlowsheetEditing.setDynamicParameter(it, id, key, spec) }
    fun addDynamicEvent(e: app.flowstead.core.model.DynamicEvent) = edit { fs -> FlowsheetEditing.setDynamics(fs) { s -> s.copy(events = (s.events + e).sortedBy { it.time }) } }
    fun removeDynamicEvent(index: Int) = edit { fs -> FlowsheetEditing.setDynamics(fs) { s -> s.copy(events = s.events.filterIndexed { i, _ -> i != index }) } }

    /** What a dynamic run's result was run for: the model and the dynamic setup. */
    fun dynamicsKey(fs: Flowsheet): String =
        fs.modelHash() + ":" + app.flowstead.core.model.FlowsheetJson.encodeToString(app.flowstead.core.model.DynamicsSetup.serializer(), app.flowstead.core.model.Dynamics.setup(fs)).hashCode()

    fun runDynamics() {
        val fs = current ?: return
        if (local.value.dynamics?.running == true) return
        app.flowstead.core.model.Dynamics.problem(fs)?.let { _messages.trySend(it); return }
        val key = dynamicsKey(fs)
        local.update { it.copy(dynamics = DynamicsUi(running = true, result = it.dynamics?.result, key = it.dynamics?.key)) }
        viewModelScope.launch {
            val url = settingsRepository.settings.first().engineTarget
            val next = if (url.isBlank()) DynamicsUi(error = "Set the engine address in Settings")
            else when (val r = engine.dynamics(url, secrets.apiKey(), app.flowstead.core.model.Dynamics.request(fs))) {
                is EngineResult.Ok -> DynamicsUi(result = r.value, key = key,
                    error = r.value.messages.firstOrNull { it.level == app.flowstead.core.api.MessageLevel.Error }?.text)
                is EngineResult.Failed -> DynamicsUi(error = "Not run: ${r.reason}")
            }
            local.update { it.copy(dynamics = next) }
        }
    }

    // ---- flowsheet tools (D105, D106)

    fun openTools() = local.update { it.copy(toolsOpen = true, editingId = null) }
    fun closeTools() = local.update { it.copy(toolsOpen = false) }

    fun setSpreadsheet(sheet: app.flowstead.core.model.Spreadsheet) = edit(undoable = false) { it.copy(spreadsheet = sheet) }

    /** Writes each exported cell's value (in the spec's display unit) into its specification. */
    fun applySpreadsheet(values: Map<String, app.flowstead.core.model.CellValue>) = edit { fs ->
        val sheet = fs.spreadsheet ?: return@edit fs
        val units = (state.value as? FlowsheetUiState.Ready)?.ui?.unitSet ?: app.flowstead.core.units.UnitSet.SI
        sheet.exports.fold(fs) { acc, e ->
            val v = (values[e.cell] as? app.flowstead.core.model.CellValue.Number)?.value
                ?: throw EditRejected("${e.cell} has no number to export")
            val q = app.flowstead.core.model.SpecKey.quantity(e.key)
            val u = units.unitFor(q)
            FlowsheetEditing.setSpec(acc, e.objectId, e.key, Spec(u.toSi(v, UnitContext()), u.id))
        }
    }

    fun toggleWatch(item: app.flowstead.core.model.WatchItem) = edit(undoable = false) { fs ->
        fs.copy(watch = if (item in fs.watch) fs.watch - item else fs.watch + item)
    }

    fun saveScenario(name: String) {
        val fs = current ?: return
        val view = ResultView(currentSolve, fs.modelHash())
        val s = app.flowstead.core.model.Scenarios.snapshot(fs, view, name.ifBlank { "Scenario ${fs.scenarios.size + 1}" }, System.currentTimeMillis())
        edit(undoable = false) { it.copy(scenarios = it.scenarios + s) }
        if (!s.solved) _messages.trySend("Saved without results: solve first to keep this scenario's numbers")
    }
    fun deleteScenario(index: Int) = edit(undoable = false) { it.copy(scenarios = it.scenarios.filterIndexed { i, _ -> i != index }) }
    fun restoreScenario(index: Int) = edit { fs -> fs.scenarios.getOrNull(index)?.let { app.flowstead.core.model.Scenarios.restore(fs, it) } ?: fs }

    fun runOptimiser(request: (Flowsheet) -> app.flowstead.core.api.OptimiseRequest) {
        val fs = current ?: return
        if (local.value.optimiser?.running == true) return
        Specification.problems(fs).firstOrNull()?.let { _messages.trySend("Fix the flowsheet first: ${it.second}"); return }
        local.update { it.copy(optimiser = ToolRunUi(running = true)) }
        viewModelScope.launch {
            val url = settingsRepository.settings.first().engineTarget
            val next = if (url.isBlank()) ToolRunUi(error = "Set the engine address in Settings")
            else when (val r = engine.optimise(url, secrets.apiKey(), request(Readiness.of(fs).solvable))) {
                is EngineResult.Ok -> ToolRunUi(result = r.value)
                is EngineResult.Failed -> ToolRunUi<app.flowstead.core.api.OptimiseResult>(error = "Not run: ${r.reason}")
            }
            local.update { it.copy(optimiser = next) }
        }
    }

    /** Puts the optimum's values into the specifications it moved (one undoable change). */
    fun applyOptimum(variables: List<app.flowstead.core.api.OptimiserVariable>, values: List<Double>) = edit { fs ->
        variables.zip(values).fold(fs) { acc, (v, x) ->
            val old = acc.obj(v.objectId)?.specs?.get(v.key) ?: return@fold acc
            FlowsheetEditing.setSpec(acc, v.objectId, v.key, old.copy(si = x))
        }
    }

    /**
     * PID tuning (D105): an open-loop step test of [controllerId] by the engine's dynamic run, then a FOPDT fit and
     * the textbook settings. A short first run reads the controller's starting output.
     */
    fun runStepTest(controllerId: String, step: Double, duration: Double) {
        val fs = current ?: return
        if (local.value.pidTest?.running == true) return
        app.flowstead.core.model.Dynamics.problem(fs)?.let { _messages.trySend(it); return }
        val controller = fs.obj(controllerId) ?: return
        // The gain is in % of the measurement span the engine uses: for a level that is the vessel's height, so it
        // must be known here too (or the controller's range), not guessed.
        val params = app.flowstead.core.model.Dynamics.setup(fs).parameters
        val ranged = params[controllerId]?.let { it[app.flowstead.core.model.DynamicKey.PV_MINIMUM] != null && it[app.flowstead.core.model.DynamicKey.PV_MAXIMUM] != null } == true
        if (controller.refs[app.flowstead.core.model.AdjustKeys.CONTROLLED_PROPERTY] == "liquidLevel" && !ranged &&
            params[controller.refs[app.flowstead.core.model.AdjustKeys.CONTROLLED_OBJECT]]?.get(app.flowstead.core.model.DynamicKey.HEIGHT) == null
        ) {
            _messages.trySend("Give the vessel's height (or ${controller.tag}'s measurement range) in Dynamics first: the tuning is in % of that span")
            return
        }
        local.update { it.copy(pidTest = ToolRunUi(running = true)) }
        viewModelScope.launch {
            val url = settingsRepository.settings.first().engineTarget
            val key = secrets.apiKey()
            suspend fun run(req: app.flowstead.core.api.DynamicsRequest) = engine.dynamics(url, key, req)
            fun trend(r: app.flowstead.core.api.DynamicsResult, k: String) = r.trends.firstOrNull { it.objectId == controllerId && it.key == k }?.values
            val setup = app.flowstead.core.model.Dynamics.setup(fs)
            val next: ToolRunUi<PidTestUi> = if (url.isBlank()) ToolRunUi(error = "Set the engine address in Settings") else {
                val first = run(app.flowstead.core.model.Dynamics.request(fs).copy(duration = 2 * setup.step, events = emptyList()))
                val op0 = (first as? EngineResult.Ok)?.value?.let { trend(it, "output")?.firstOrNull() }
                if (op0 == null) ToolRunUi(error = (first as? EngineResult.Failed)?.reason ?: "The dynamic run gave no output for ${controller.tag}")
                else {
                    val stepTime = 5 * setup.step
                    val s = if (op0 + step > 100) -kotlin.math.abs(step) else if (op0 + step < 0) kotlin.math.abs(step) else step
                    when (val r = run(app.flowstead.core.model.PidTuning.stepTest(fs, controllerId, op0, s, stepTime, duration))) {
                        is EngineResult.Failed -> ToolRunUi(error = "Not run: ${r.reason}")
                        is EngineResult.Ok -> {
                            val pv = trend(r.value, "controlledValue")
                            val sp = trend(r.value, "target")?.firstOrNull()
                            if (pv == null || sp == null) ToolRunUi(error = r.value.messages.firstOrNull()?.text ?: "The run gave no measurement for ${controller.tag}")
                            else {
                                val span = app.flowstead.core.model.PidTuning.span(fs, controller, sp)
                                val pct = pv.map { it / span * 100 }
                                val model = app.flowstead.core.model.PidTuning.fit(r.value.times, pct, stepTime, s)
                                ToolRunUi(result = PidTestUi(controllerId, r.value.times, pct, stepTime, s, model,
                                    model?.let { app.flowstead.core.model.PidTuning.settings(it) }.orEmpty()))
                            }
                        }
                    }
                }
            }
            local.update { it.copy(pidTest = next) }
        }
    }

    /** Puts a rule's settings (and the action the process needs) into the controller's dynamic tuning. */
    fun applyTuning(controllerId: String, settings: app.flowstead.core.model.PidSettings, model: app.flowstead.core.model.Fopdt) = edit { fs ->
        listOf(
            app.flowstead.core.model.DynamicKey.GAIN to Spec(settings.gain, ""),
            app.flowstead.core.model.DynamicKey.INTEGRAL_TIME to Spec(settings.integralTime, "s"),
            app.flowstead.core.model.DynamicKey.DERIVATIVE_TIME to Spec(settings.derivativeTime, "s"),
            app.flowstead.core.model.DynamicKey.ACTION to Spec(app.flowstead.core.model.PidTuning.action(model).toDouble(), ""),
        ).fold(fs) { acc, (k, v) -> FlowsheetEditing.setDynamicParameter(acc, controllerId, k, v) }
    }

    /** The engine's T–x–y of a pair at the column pressure, for McCabe–Thiele (D106). */
    fun runMcCabe(first: String, second: String, pressure: Double) {
        val fs = current ?: return
        local.update { it.copy(mcCabe = ToolRunUi(running = true)) }
        viewModelScope.launch {
            val url = settingsRepository.settings.first().engineTarget
            val next = if (url.isBlank()) ToolRunUi(error = "Set the engine address in Settings")
            else when (val r = engine.binaryDiagram(url, secrets.apiKey(), app.flowstead.core.api.BinaryDiagramRequest(fs, first, second, app.flowstead.core.api.BinaryDiagramKinds.TXY, pressure, 298.15, 60, false))) {
                is EngineResult.Ok -> ToolRunUi(result = r.value)
                is EngineResult.Failed -> ToolRunUi<app.flowstead.core.api.BinaryDiagramResult>(error = r.reason)
            }
            local.update { it.copy(mcCabe = next) }
        }
    }

    /** The case as a Markdown report (D105). */
    fun report(): String? {
        val fs = current ?: return null
        val ui = (state.value as? FlowsheetUiState.Ready)?.ui ?: return null
        return app.flowstead.core.export.Report.markdown(ui.projectName, fs, ResultView(currentSolve, fs.modelHash()), ui.unitSet,
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date()), app.flowstead.BuildConfig.VERSION_NAME)
    }

    fun openCaseStudies() = local.update { it.copy(caseStudiesOpen = true, editingId = null) }
    fun closeCaseStudies() = local.update { it.copy(caseStudiesOpen = false) }
    fun upsertCaseStudy(cs: CaseStudy) = edit { FlowsheetEditing.upsertCaseStudy(it, cs) }
    fun deleteCaseStudy(id: String) = edit { FlowsheetEditing.deleteCaseStudy(it, id) }

    /**
     * Solves the flowsheet once per point of the study, in order, and saves what the engine returned. The project's
     * own flowsheet and results are not touched; a point that fails is kept as a gap with its reason.
     */
    fun runCaseStudy(id: String) {
        val base = current ?: return
        if (caseJob?.isActive == true || local.value.solving) return
        val cs = base.caseStudies.firstOrNull { it.id == id } ?: return
        CaseStudies.problem(base, cs)?.let { _messages.trySend(it); return }
        Specification.problems(base).firstOrNull()?.let { _messages.trySend("Fix the flowsheet first: ${it.second}"); return }
        val grid = CaseStudies.grid(cs)
        val xs = grid.map { it.first }
        local.update { it.copy(caseRun = CaseRunUi(id, 0, xs.size)) }
        caseJob = viewModelScope.launch {
            try {
                val url = settingsRepository.settings.first().engineTarget
                if (url.isBlank()) {
                    _messages.send("Set the engine address in Settings to run a case study")
                    return@launch
                }
                val key = secrets.apiKey()
                val values = cs.outputs.map { mutableListOf<Double?>() }
                val errors = mutableListOf<String?>()
                for ((i, point) in grid.withIndex()) {
                    val at = CaseStudies.at(base, cs, point.first, point.second)
                    when (val r = engine.solve(url, key, at)) {
                        is EngineResult.Ok -> {
                            val (v, error) = CaseStudies.read(at, cs, r.value)
                            v.forEachIndexed { k, value -> values[k] += value }
                            errors += error
                        }
                        is EngineResult.Failed -> {
                            if (r.unreachable) {
                                _messages.send("Case study stopped: the engine is unreachable")
                                return@launch
                            }
                            values.forEach { it += null }
                            errors += r.reason
                        }
                    }
                    local.update { it.copy(caseRun = CaseRunUi(id, i + 1, xs.size)) }
                }
                val x2 = grid.map { it.second }.takeIf { CaseStudies.twoWay(cs) }?.map { it!! }
                edit(undoable = false) { FlowsheetEditing.setCaseResults(it, id, CaseResults(base.modelHash(), xs, values, errors, x2)) }
                val failed = errors.count { it != null }
                _messages.send("Case study done: ${xs.size} points" + if (failed > 0) ", $failed did not converge" else "")
            } finally {
                local.update { it.copy(caseRun = null) }
            }
        }
    }

    fun cancelCaseStudy() {
        caseJob?.cancel()
    }

    /**
     * The composition the engine's stream calculations use: the solved one, or a feed's own as typed when the flowsheet
     * has not been solved since its last change. Null with the reason when there is none.
     */
    private fun streamComposition(fs: Flowsheet, streamId: String): Pair<Map<String, Double>?, String?> {
        val stream = fs.objects.firstOrNull { it.id == streamId } ?: return null to "That stream no longer exists"
        if (fs.propertyPackage == null) return null to "Choose a fluid package first"
        val solved = currentSolve?.takeIf { it.modelHash == fs.modelHash() }?.result?.objects?.get(streamId)
            ?.phases?.get(app.flowstead.core.api.Phases.OVERALL)?.composition
        val composition = solved ?: stream.composition.takeIf { fs.isFeed(stream) && it.values.sum() > 0 }
        return if (composition.isNullOrEmpty()) null to "Solve first: the stream's composition comes from the solve" else composition to null
    }

    /** The stream's solved pressure, when the results are current. */
    private fun streamPressure(fs: Flowsheet, streamId: String): Double? = streamProperty(fs, streamId, "pressure")

    private fun streamProperty(fs: Flowsheet, streamId: String, key: String): Double? =
        currentSolve?.takeIf { it.modelHash == fs.modelHash() }?.result?.objects?.get(streamId)?.properties?.get(key)

    /**
     * Traces a stream's phase envelope (D50) with the chosen quality lines and the hydrate curve (D52). The engine returns
     * every point; nothing is drawn that it did not trace.
     */
    fun phaseEnvelope(streamId: String, qualities: List<Double> = emptyList(), hydrates: Boolean = false, inhibitor: String? = null, inhibitorFraction: Double = 0.0) {
        val fs = current ?: return
        if (local.value.envelope?.loading == true) return
        val (composition, why) = streamComposition(fs, streamId)
        if (composition == null) {
            local.update { it.copy(envelope = EnvelopeUi(streamId, error = why)) }
            return
        }
        local.update { it.copy(envelope = EnvelopeUi(streamId, loading = true, envelope = it.envelope?.takeIf { e -> e.streamId == streamId }?.envelope)) }
        viewModelScope.launch {
            val url = settingsRepository.settings.first().engineTarget
            val request = PhaseEnvelopeRequest(
                SubflowsheetSolve.forObject(fs, streamId), composition, qualities, hydrates, streamPressure(fs, streamId), streamProperty(fs, streamId, "temperature"),
                inhibitor.takeIf { inhibitorFraction > 0 }, inhibitorFraction,
            )
            val next = if (url.isBlank()) EnvelopeUi(streamId, error = "Set the engine address in Settings")
            else when (val r = engine.phaseEnvelope(url, secrets.apiKey(), request)) {
                is EngineResult.Ok -> EnvelopeUi(streamId, envelope = r.value)
                is EngineResult.Failed -> EnvelopeUi(streamId, error = "No envelope: ${r.reason}")
            }
            local.update { it.copy(envelope = next, envelopeHash = fs.modelHash()) }
        }
    }

    /**
     * Line sizing of a stream (D148): its fluid through a test pipe of each size, in one engine solve; [onResult] gets the
     * table (or why there is none) on the main thread.
     */
    fun streamLineSizing(streamId: String, input: app.flowstead.core.model.StreamLineSizing.Input, onResult: (LineSizingUi) -> Unit) {
        val fs = current ?: return onResult(LineSizingUi(error = "No flowsheet"))
        val view = ResultView(currentSolve, fs.modelHash())
        app.flowstead.core.model.StreamLineSizing.problem(fs, view, streamId)?.let { return onResult(LineSizingUi(error = it)) }
        if (app.flowstead.core.model.StreamLineSizing.candidates(input).isEmpty())
            return onResult(LineSizingUi(error = if (input.design) "No size has that schedule" else "Choose a size and schedule, or give the inside diameter"))
        viewModelScope.launch {
            val url = settingsRepository.settings.first().engineTarget
            val sizing = app.flowstead.core.model.StreamLineSizing
            if (url.isBlank()) return@launch onResult(LineSizingUi(error = "Set the engine address in Settings"))
            val key = secrets.apiKey()
            var solved = when (val r = engine.solve(url, key, sizing.flowsheet(fs, view, streamId, input))) {
                is EngineResult.Ok -> r.value
                is EngineResult.Failed -> return@launch onResult(LineSizingUi(error = "Not sized: ${r.reason}"))
            }
            // The engine stops at the first pipe that fails: those it did not reach are solved again, each alone.
            for (tag in sizing.unsolved(input, solved)) {
                when (val again = engine.solve(url, key, sizing.flowsheet(fs, view, streamId, input, setOf(tag)))) {
                    is EngineResult.Ok -> solved = sizing.merge(solved, again.value)
                    is EngineResult.Failed -> Unit
                }
            }
            onResult(LineSizingUi(sizing.outcome(fs, view, streamId, input, solved), hash = fs.modelHash(), total = input.totalLength))
        }
    }

    /** A stream's distillation curves and petroleum product properties, DWSIM 10's (D99). */
    fun petroleumProperties(streamId: String) {
        val fs = current ?: return
        if (local.value.petroleum?.loading == true) return
        val (composition, why) = streamComposition(fs, streamId)
        if (composition == null) {
            local.update { it.copy(petroleum = PetroleumUi(streamId, error = why)) }
            return
        }
        local.update { it.copy(petroleum = PetroleumUi(streamId, loading = true)) }
        viewModelScope.launch {
            val url = settingsRepository.settings.first().engineTarget
            val next = if (url.isBlank()) PetroleumUi(streamId, error = "Set the engine address in Settings")
            else when (val r = engine.petroleumProperties(url, secrets.apiKey(), app.flowstead.core.api.PetroleumPropertiesRequest(SubflowsheetSolve.forObject(fs, streamId), composition))) {
                is EngineResult.Ok -> PetroleumUi(streamId, result = r.value)
                is EngineResult.Failed -> PetroleumUi(streamId, error = "No distillation curves: ${r.reason}")
            }
            local.update { it.copy(petroleum = next, petroleumHash = fs.modelHash()) }
        }
    }

    // ---- safety analysis (D54–D57)

    fun openSafety() = local.update { it.copy(safetyOpen = true, editingId = null) }
    fun closeSafety() = local.update { it.copy(safetyOpen = false) }
    fun upsertReliefDevice(d: ReliefDeviceDef) = edit { FlowsheetEditing.upsertReliefDevice(it, d) }
    fun deleteReliefDevice(id: String) = edit { FlowsheetEditing.deleteReliefDevice(it, id) }
    fun upsertDepressuring(c: DepressuringCase) = edit { FlowsheetEditing.upsertDepressuring(it, c) }
    fun deleteDepressuring(id: String) = edit { FlowsheetEditing.deleteDepressuring(it, id) }
    fun upsertPressurization(c: app.flowstead.core.model.PressurizationCase) = edit { FlowsheetEditing.upsertPressurization(it, c) }
    fun deletePressurization(id: String) = edit { FlowsheetEditing.deletePressurization(it, id) }

    /** Fills a pipeline network from its supply (HYSYS's pipeline pressurization), sizing the orifice when asked. */
    fun runPressurization(id: String) {
        val fs = current ?: return
        val c = fs.safety?.pressurization?.firstOrNull { it.id == id } ?: return
        val view = ResultView(currentSolve, fs.modelHash())
        Safety.pressurizationProblem(fs, view, c)?.let { _messages.trySend(it); return }
        engineCall(id) { url, key ->
            when (val r = engine.depressuring(url, key, Safety.pressurizationRequest(fs, view, c))) {
                is EngineResult.Ok -> edit(undoable = false) { FlowsheetEditing.setPressurizationResult(it, id, r.value, fs.modelHash()) }
                is EngineResult.Failed -> _messages.send("Not run: ${r.reason}")
            }
        }
    }

    /** Sizes one relief valve on the engine, from the protected equipment's solved contents. */
    fun runRelief(id: String) {
        val fs = current ?: return
        val d = fs.safety?.devices?.firstOrNull { it.id == id } ?: return
        val view = ResultView(currentSolve, fs.modelHash())
        Safety.problem(fs, view, d)?.let { _messages.trySend(it); return }
        engineCall(id) { url, key ->
            when (val r = engine.relief(url, key, ReliefRequest(SubflowsheetSolve.forObject(fs, d.protectedId), Safety.request(fs, view, d)))) {
                is EngineResult.Ok -> edit(undoable = false) { FlowsheetEditing.setReliefResult(it, id, r.value, fs.modelHash()) }
                is EngineResult.Failed -> _messages.send("Not sized: ${r.reason}")
            }
        }
    }

    /** Runs one blowdown on the engine (sizing the orifice when none is given). */
    fun runDepressuring(id: String) {
        val fs = current ?: return
        val c = fs.safety?.depressuring?.firstOrNull { it.id == id } ?: return
        val view = ResultView(currentSolve, fs.modelHash())
        Safety.depressuringProblem(fs, view, c)?.let { _messages.trySend(it); return }
        engineCall(id) { url, key ->
            when (val r = engine.depressuring(url, key, Safety.depressuringRequest(fs, view, c))) {
                is EngineResult.Ok -> edit(undoable = false) { FlowsheetEditing.setDepressuringResult(it, id, r.value, fs.modelHash()) }
                is EngineResult.Failed -> _messages.send("Not run: ${r.reason}")
            }
        }
    }

    private fun engineCall(id: String, block: suspend (url: String, key: String?) -> Unit) {
        if (id in local.value.safetyRunning) return
        local.update { it.copy(safetyRunning = it.safetyRunning + id) }
        viewModelScope.launch {
            try {
                val url = settingsRepository.settings.first().engineTarget
                if (url.isBlank()) _messages.send("Set the engine address in Settings") else block(url, secrets.apiKey())
            } finally {
                local.update { it.copy(safetyRunning = it.safetyRunning - id) }
            }
        }
    }

    // ---- PVT (D53)

    fun openPvt(streamId: String) = local.update { it.copy(pvtFor = streamId, editingId = null) }
    fun closePvt() = local.update { it.copy(pvtFor = null) }

    /** Runs one PVT experiment on the stream's composition at reservoir temperature [temperature] (K). */
    fun runPvt(streamId: String, experiment: String, temperature: Double, stages: List<SeparatorStage>) {
        val fs = current ?: return
        if (local.value.pvt?.loading == true) return
        val (composition, why) = streamComposition(fs, streamId)
        if (composition == null) {
            local.update { it.copy(pvt = PvtUi(streamId, experiment, error = why)) }
            return
        }
        local.update { it.copy(pvt = PvtUi(streamId, experiment, loading = true)) }
        viewModelScope.launch {
            val url = settingsRepository.settings.first().engineTarget
            val next = if (url.isBlank()) PvtUi(streamId, experiment, error = "Set the engine address in Settings")
            else when (val r = engine.pvt(url, secrets.apiKey(), PvtRequest(SubflowsheetSolve.forObject(fs, streamId), composition, experiment, temperature, stages = stages))) {
                is EngineResult.Ok -> PvtUi(streamId, experiment, result = r.value)
                is EngineResult.Failed -> PvtUi(streamId, experiment, error = "Not run: ${r.reason}")
            }
            local.update { it.copy(pvt = next, pvtHash = fs.modelHash()) }
        }
    }

    /**
     * Tunes the stream's plus fraction to lab data (D53). The tuned constants are shown, not applied: [applyTuning]
     * writes them into the flowsheet's hypotheticals when the user accepts them.
     */
    fun runTuning(streamId: String, temperature: Double, observations: List<PvtObservation>, maxChange: Double = 0.2) {
        val fs = current ?: return
        if (local.value.tuning?.loading == true) return
        val (composition, why) = streamComposition(fs, streamId)
        PvtTuning.problem(fs, composition, observations)?.let { p ->
            local.update { it.copy(tuning = TuningUi(streamId, error = composition?.let { p } ?: why)) }
            return
        }
        local.update { it.copy(tuning = TuningUi(streamId, loading = true)) }
        viewModelScope.launch {
            val url = settingsRepository.settings.first().engineTarget
            val next = if (url.isBlank()) TuningUi(streamId, error = "Set the engine address in Settings")
            else when (val r = engine.pvtRegression(url, secrets.apiKey(), PvtRegressionRequest(fs, composition!!, temperature, observations, maxChange = maxChange))) {
                is EngineResult.Ok -> TuningUi(streamId, result = r.value)
                is EngineResult.Failed -> TuningUi(streamId, error = "Not tuned: ${r.reason}")
            }
            local.update { it.copy(tuning = next) }
        }
    }

    /** Writes the tuned critical constants into the hypotheticals (undoable). */
    fun applyTuning() {
        val r = local.value.tuning?.result ?: return
        edit { PvtTuning.apply(it, r.tuned, r.interactionParameters) }
        local.update { it.copy(tuning = it.tuning?.copy(applied = true)) }
    }

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
        SolveStatus.Converged -> "Converged in %.1f s".format(java.util.Locale.ROOT, r.elapsedMs / 1000.0) +
            r.messages.count { it.level == app.flowstead.core.api.MessageLevel.Warning }.let { n ->
                if (n == 0) "" else " · $n warning" + if (n == 1) "" else "s"
            }
        SolveStatus.Failed -> "Solve failed: ${r.messages.firstOrNull()?.text ?: "see the solver log"}"
        SolveStatus.Invalid -> "Engine refused the flowsheet: ${r.messages.firstOrNull()?.text ?: "see the solver log"}"
        SolveStatus.Timeout -> "Solve timed out: ${r.messages.firstOrNull()?.text ?: ""}".trimEnd(' ', ':')
    }

    fun undo() {
        val previous = local.value.undo.lastOrNull() ?: return
        local.update { it.copy(undo = it.undo.dropLast(1)) }
        val active = current?.solverActive == true
        // Undo restores the model, not the solver mode.
        val restored = previous.copy(solverActive = active)
        current = restored
        viewModelScope.launch { repository.saveFlowsheet(projectId, restored) }
        if (active) solveSoon()
    }

    private fun edit(undoable: Boolean = true, block: (Flowsheet) -> Flowsheet) {
        val before = current ?: return
        val after = try {
            // Inside a sub-flowsheet, whatever the edit made belongs to it (D133).
            Subflowsheets.adopt(before, block(before), local.value.level)
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
        if (after.solverActive && after.modelHash() != before.modelHash()) solveSoon()
    }

    companion object {
        const val UNDO_DEPTH = 50
        /** Active solver: quiet time after the last change before it solves. */
        const val ACTIVE_DELAY_MS = 800L
    }
}
