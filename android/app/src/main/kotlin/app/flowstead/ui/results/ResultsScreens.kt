/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.results

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.flowstead.core.api.MessageLevel
import app.flowstead.core.api.Phases
import app.flowstead.core.api.SolveStatus
import app.flowstead.core.model.DisplayValue
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.ResultKeys
import app.flowstead.core.model.StreamLayout
import app.flowstead.core.model.ResultView
import app.flowstead.core.model.SimObject
import app.flowstead.core.model.StoredSolve
import app.flowstead.core.model.displayName
import app.flowstead.core.model.modelHash
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.UnitSet
import app.flowstead.data.ProjectRepository
import app.flowstead.data.settings.SettingsRepository
import app.flowstead.ui.components.BackButton
import app.flowstead.ui.components.ValueText
import app.flowstead.ui.nav.ResultsRoute
import app.flowstead.ui.theme.NumberStyles
import app.flowstead.ui.theme.valueColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

data class ResultsUi(
    val name: String,
    val flowsheet: Flowsheet,
    val solve: StoredSolve?,
    val results: ResultView,
    val unitSet: UnitSet,
    val ctx: UnitContext,
)

/** Shared by the results table and the solver log. */
@HiltViewModel
class ResultsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    repository: ProjectRepository,
    settings: SettingsRepository,
) : ViewModel() {
    private val projectId = savedState.toRoute<ResultsRoute>().projectId

    val state: StateFlow<ResultsUi?> = combine(repository.observeProject(projectId), settings.settings) { p, s ->
        p?.let {
            ResultsUi(
                name = it.name,
                flowsheet = it.flowsheet,
                solve = it.solve,
                results = ResultView(it.solve, it.flowsheet.modelHash()),
                unitSet = s.unitSet,
                ctx = UnitContext(it.flowsheet.atmosphericPressurePa),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResultsScaffold(title: String, onBack: () -> Unit, content: @Composable (Modifier) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding -> content(Modifier.padding(padding)) }
}

@Composable
fun StaleBanner(ui: ResultsUi) {
    val v = MaterialTheme.valueColors
    val (text, color) = when {
        ui.solve == null -> "Not solved yet. Solve from the flowsheet." to v.underSpecifiedText
        ui.results.stale -> "Out of date: the flowsheet changed after this solve. Re-solve before using these numbers." to v.underSpecifiedText
        ui.results.status != SolveStatus.Converged -> "Last solve ${ui.results.status?.name?.lowercase()}. See the solver log." to v.error
        else -> "Converged · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ui.solve.solvedAtMillis))}" to v.convergedText
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp).testTag("results_banner"))
    }
}

private val LABEL_WIDTH = 150.dp
private val CELL_WIDTH = 132.dp

@Composable
fun ResultsScreen(onBack: () -> Unit, viewModel: ResultsViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    ResultsScaffold("Results", onBack) { modifier ->
        val u = ui ?: return@ResultsScaffold
        ResultsContent(u, modifier)
    }
}

@Composable
fun ResultsContent(u: ResultsUi, modifier: Modifier = Modifier) {
    var sortKey by rememberSaveable { mutableStateOf<String?>(null) }
    var descending by rememberSaveable { mutableStateOf(true) }
    val scroll = rememberScrollState()

    val baseStreams = u.flowsheet.objects.filter { it.type == ObjectType.MaterialStream }
    // Sorting is by a row of the HYSYS-style layout, keyed by its label.
    val sortRow = sortKey?.let { label -> StreamLayout.SECTIONS.flatMap { it.rows }.firstOrNull { it.label == label } }
    val streams = sortRow?.let { row ->
        val sorted = baseStreams.sortedBy { (StreamLayout.value(u.results, it.id, row) as? DisplayValue.Calculated)?.si ?: Double.NEGATIVE_INFINITY }
        if (descending) sorted.reversed() else sorted
    } ?: baseStreams.sortedWith(compareBy({ it.tag.toIntOrNull() ?: Int.MAX_VALUE }, { it.tag }))
    val unitOps = u.flowsheet.objects.filter { !it.type.isStream }

    LazyColumn(modifier.fillMaxSize().testTag("results_table")) {
        item { StaleBanner(u) }
        if (streams.isNotEmpty()) {
            item { SectionHeader("Material streams") }
            item {
                TableRow(scroll, label = "", labelUnit = null) {
                    streams.forEach { s ->
                        Text(s.tag, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(CELL_WIDTH).padding(end = 8.dp))
                    }
                }
            }
            // As a HYSYS workbook: Overall, then Vapour, Liquid and Aqueous blocks for the phases present.
            for (section in StreamLayout.sections(u.results, streams.map { it.id })) {
                item(key = "section-${section.phase}") {
                    Text(
                        section.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                items(section.rows, key = { "${section.phase}-${it.label}" }) { row ->
                    val unit = StreamLayout.unit(row, u.unitSet)
                    val arrow = if (sortKey == row.label) (if (descending) " ↓" else " ↑") else ""
                    TableRow(
                        scroll,
                        label = row.label + arrow,
                        labelUnit = unit.symbol,
                        onLabelClick = {
                            if (sortKey == row.label) descending = !descending else { sortKey = row.label; descending = true }
                        },
                    ) {
                        streams.forEach { s ->
                            Box(Modifier.width(CELL_WIDTH).padding(end = 8.dp)) { ValueText(StreamLayout.value(u.results, s.id, row), unit, ctx = u.ctx) }
                        }
                    }
                }
            }
            if (u.flowsheet.compounds.isNotEmpty()) {
                item { SectionHeader("Composition (overall mole fraction)") }
                items(u.flowsheet.compounds) { c ->
                    val unit = u.unitSet.unitFor(app.flowstead.core.units.Quantity.Fraction)
                    TableRow(scroll, label = c, labelUnit = null) {
                        streams.forEach { s ->
                            Box(Modifier.width(CELL_WIDTH).padding(end = 8.dp)) {
                                ValueText(u.results.composition(s.id, Phases.OVERALL, c), unit, ctx = u.ctx)
                            }
                        }
                    }
                }
            }
        }
        if (unitOps.isNotEmpty()) {
            item { SectionHeader("Unit operations") }
            items(unitOps, key = { it.id }) { op -> UnitOpCard(op, u) }
        }
        if (streams.isEmpty() && unitOps.isEmpty()) {
            item { Text("The flowsheet is empty.", modifier = Modifier.padding(16.dp)) }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
private fun TableRow(
    scroll: ScrollState,
    label: String,
    labelUnit: String?,
    onLabelClick: (() -> Unit)? = null,
    cells: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier
                .width(LABEL_WIDTH)
                .padding(start = 16.dp, end = 8.dp)
                .let { m -> onLabelClick?.let { m.clickable(role = Role.Button, onClickLabel = "Sort streams by $label", onClick = it) } ?: m },
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            if (!labelUnit.isNullOrEmpty()) Text(labelUnit, style = NumberStyles.tag, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.horizontalScroll(scroll), horizontalArrangement = Arrangement.Start) { cells() }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun UnitOpCard(op: SimObject, u: ResultsUi) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("${op.tag} · ${op.type.displayName()}", style = MaterialTheme.typography.titleSmall)
        u.results.warnings(op.id).forEach {
            Text("⚠ ${it.text}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.valueColors.underSpecifiedText)
        }
        val keys = ResultKeys.forType(op.type)
        if (keys.isEmpty()) {
            Text("No unit-level results; see its product streams.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        keys.forEach { key ->
            val unit = u.unitSet.unitFor(ResultKeys.quantityFor(op, key))
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(ResultKeys.label(key), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                ValueText(u.results.value(op.id, key), unit, ctx = u.ctx)
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
fun SolverLogScreen(onBack: () -> Unit, viewModel: ResultsViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    ResultsScaffold("Solver log", onBack) { modifier ->
        val u = ui ?: return@ResultsScaffold
        SolverLogContent(u, modifier)
    }
}

@Composable
fun SolverLogContent(u: ResultsUi, modifier: Modifier = Modifier) {
    val v = MaterialTheme.valueColors
    val r = u.solve?.result
    LazyColumn(modifier.fillMaxSize().testTag("solver_log")) {
        item { StaleBanner(u) }
        if (r != null) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LogLine("Status", r.status.name.lowercase())
                    LogLine("Wall time", "%.2f s".format(java.util.Locale.ROOT, r.elapsedMs / 1000.0))
                    r.iterations?.let { LogLine("Recycle iterations", it.toString()) }
                    LogLine("Engine", "ThermoDock ${r.engineVersion} · DWSIM ${r.dwsimVersion}")
                    LogLine("Objects solved", "${r.objects.values.count { it.status == app.flowstead.core.api.ObjectStatus.Solved }} of ${u.flowsheet.objects.size}")
                }
            }
            item { SectionHeader("Messages") }
            if (r.messages.isEmpty()) {
                item { Text("No warnings or errors.", color = v.convergedText, modifier = Modifier.padding(horizontal = 16.dp)) }
            }
            items(r.messages) { m ->
                val tag = m.objectId?.let { id -> u.flowsheet.objects.firstOrNull { it.id == id }?.tag }
                val color = when (m.level) {
                    MessageLevel.Error -> v.error
                    MessageLevel.Warning -> v.underSpecifiedText
                    MessageLevel.Info -> MaterialTheme.colorScheme.onSurface
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Box(
                        Modifier.padding(top = 6.dp, end = 10.dp).width(8.dp).heightIn(min = 8.dp)
                            .background(color, androidx.compose.foundation.shape.CircleShape),
                    )
                    Column {
                        Text(
                            listOfNotNull(m.level.name, tag).joinToString(" · "),
                            style = NumberStyles.tag,
                            color = color,
                        )
                        Text(m.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogLine(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(140.dp))
        Text(value, style = NumberStyles.tag)
    }
}
