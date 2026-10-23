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
import app.flowstead.core.model.PerformanceCurve
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
    /** The last phase envelope asked for, while the project is open (D50). */
    val envelope: EnvelopeUi? = null,
    val caseStudiesOpen: Boolean = false,
    /** A case study running now (D51). */
    val caseRun: CaseRunUi? = null,
    /** The stream whose PVT sheet is open (D53), and the last experiment run. */
    val pvtFor: String? = null,
    val pvt: PvtUi? = null,
    val tuning: TuningUi? = null,
    val safetyOpen: Boolean = false,
    val safetyRunning: Set<String> = emptySet(),
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
        val envelope: EnvelopeUi? = null,
        /** Model hash the envelope was traced for. */
        val envelopeHash: String? = null,
        val caseStudiesOpen: Boolean = false,
        val caseRun: CaseRunUi? = null,
        val pvtFor: String? = null,
        val pvt: PvtUi? = null,
        val pvtHash: String? = null,
        val tuning: TuningUi? = null,
        val safetyOpen: Boolean = false,
        /** Ids of relief valves and depressuring cases the engine is working on. */
        val safetyRunning: Set<String> = emptySet(),
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
                envelope = l.envelope?.copy(stale = l.envelopeHash != null && l.envelopeHash != fs.modelHash()),
                caseStudiesOpen = l.caseStudiesOpen,
                caseRun = l.caseRun,
                pvtFor = l.pvtFor?.takeIf { id -> fs.objects.any { it.id == id } },
                pvt = l.pvt?.copy(stale = l.pvtHash != null && l.pvtHash != fs.modelHash()),
                tuning = l.tuning,
                safetyOpen = l.safetyOpen,
                safetyRunning = l.safetyRunning,
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

    fun setCurve(id: String, curve: PerformanceCurve?) = edit { FlowsheetEditing.setCurve(it, id, curve) }

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
        val problems = Specification.problems(fs)
        if (problems.isNotEmpty()) {
            if (!auto) _messages.trySend("Fix ${problems.size} issue(s) first: ${problems.first().second}")
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
                when (val r = engine.solve(url, secrets.apiKey(), fs)) {
                    is EngineResult.Failed -> if (r.unreachable && !auto) {
                        queue.enqueue(projectId)
                        _messages.send("Engine unreachable. Queued: it will solve when the phone is back online")
                    } else {
                        _messages.send("Not solved: ${r.reason}")
                    }
                    is EngineResult.Ok -> {
                        queue.cancel(projectId)
                        repository.saveSolve(projectId, StoredSolve(r.value, fs.modelHash(), clock.now()))
                        if (!auto || r.value.status != SolveStatus.Converged) _messages.send(summary(r.value))
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
                fs, composition, qualities, hydrates, streamPressure(fs, streamId), streamProperty(fs, streamId, "temperature"),
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

    // ---- safety analysis (D54–D57)

    fun openSafety() = local.update { it.copy(safetyOpen = true, editingId = null) }
    fun closeSafety() = local.update { it.copy(safetyOpen = false) }
    fun upsertReliefDevice(d: ReliefDeviceDef) = edit { FlowsheetEditing.upsertReliefDevice(it, d) }
    fun deleteReliefDevice(id: String) = edit { FlowsheetEditing.deleteReliefDevice(it, id) }
    fun upsertDepressuring(c: DepressuringCase) = edit { FlowsheetEditing.upsertDepressuring(it, c) }
    fun deleteDepressuring(id: String) = edit { FlowsheetEditing.deleteDepressuring(it, id) }

    /** Sizes one relief valve on the engine, from the protected equipment's solved contents. */
    fun runRelief(id: String) {
        val fs = current ?: return
        val d = fs.safety?.devices?.firstOrNull { it.id == id } ?: return
        val view = ResultView(currentSolve, fs.modelHash())
        Safety.problem(fs, view, d)?.let { _messages.trySend(it); return }
        engineCall(id) { url, key ->
            when (val r = engine.relief(url, key, ReliefRequest(fs, Safety.request(fs, view, d)))) {
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
            else when (val r = engine.pvt(url, secrets.apiKey(), PvtRequest(fs, composition, experiment, temperature, stages = stages))) {
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
        val tuned = local.value.tuning?.result?.tuned ?: return
        edit { PvtTuning.apply(it, tuned) }
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
        if (after.solverActive && after.modelHash() != before.modelHash()) solveSoon()
    }

    companion object {
        const val UNDO_DEPTH = 50
        /** Active solver: quiet time after the last change before it solves. */
        const val ACTIVE_DELAY_MS = 800L
    }
}
