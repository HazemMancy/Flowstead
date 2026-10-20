/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.fluid

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.flowstead.core.api.OfflineCatalogue
import app.flowstead.core.model.FlowsheetEditing
import app.flowstead.data.ProjectRepository
import app.flowstead.data.engine.EngineClient
import app.flowstead.data.engine.EngineResult
import app.flowstead.data.settings.SecretStore
import app.flowstead.data.settings.SettingsRepository
import app.flowstead.ui.components.BackButton
import app.flowstead.ui.nav.FluidRoute
import app.flowstead.ui.theme.NumberStyles
import app.flowstead.ui.theme.valueColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchHit(val name: String, val detail: String)

data class FluidUi(
    val loaded: Boolean = false,
    val propertyPackage: String? = null,
    val compounds: List<String> = emptyList(),
    val packages: List<String> = OfflineCatalogue.propertyPackages,
    val query: String = "",
    val results: List<SearchHit> = emptyList(),
    /** Null when results come from the engine; otherwise why the offline list is shown. */
    val offlineReason: String? = null,
)

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class FluidPackageViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val projects: ProjectRepository,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val engine: EngineClient,
) : ViewModel() {
    private val projectId = savedState.toRoute<FluidRoute>().projectId
    private val query = MutableStateFlow("")
    private val packages = MutableStateFlow(OfflineCatalogue.propertyPackages)
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private val search: Flow<Pair<List<SearchHit>, String?>> = query.debounce(250).mapLatest { q -> lookup(q) }

    val state: StateFlow<FluidUi> = combine(projects.observeProject(projectId), query, search, packages) { p, q, (hits, offline), pk ->
        FluidUi(
            loaded = p != null,
            propertyPackage = p?.flowsheet?.propertyPackage,
            compounds = p?.flowsheet?.compounds.orEmpty(),
            packages = pk,
            query = q,
            results = hits,
            offlineReason = offline,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FluidUi())

    init {
        viewModelScope.launch {
            val url = settings.settings.first().engineUrl
            if (url.isBlank()) return@launch
            val r = engine.propertyPackages(url, secrets.apiKey())
            if (r is EngineResult.Ok) packages.value = r.value.filter { it.mvp }.map { it.name }.ifEmpty { OfflineCatalogue.propertyPackages }
        }
    }

    private suspend fun lookup(q: String): Pair<List<SearchHit>, String?> {
        val url = settings.settings.first().engineUrl
        if (url.isNotBlank()) {
            when (val r = engine.compounds(url, secrets.apiKey(), q, 25)) {
                is EngineResult.Ok -> return r.value.map { c ->
                    val mw = if (c.molecularWeight.isFinite()) " · MW ${"%.3f".format(java.util.Locale.ROOT, c.molecularWeight)}" else ""
                    SearchHit(c.name, "${c.cas.ifBlank { "no CAS" }}$mw · ${c.database}")
                } to null
                is EngineResult.Failed -> return offline(q) to "Engine unavailable (${r.reason}). Showing common compounds."
            }
        }
        return offline(q) to "No engine set. Showing common compounds; set the engine in Settings for the full DWSIM database."
    }

    private fun offline(q: String) = OfflineCatalogue.search(q).map { name ->
        SearchHit(name, OfflineCatalogue.compounds.first { it.first == name }.second.joinToString(" · ").ifEmpty { "common compound" })
    }

    fun setQuery(q: String) {
        query.value = q
    }

    fun setPackage(name: String) = viewModelScope.launch {
        val p = projects.observeProject(projectId).first() ?: return@launch
        projects.saveFlowsheet(projectId, p.flowsheet.copy(propertyPackage = name))
    }

    fun add(name: String) = viewModelScope.launch {
        val p = projects.observeProject(projectId).first() ?: return@launch
        if (name in p.flowsheet.compounds) {
            _messages.send("$name is already in the list")
            return@launch
        }
        projects.saveFlowsheet(projectId, FlowsheetEditing.setCompounds(p.flowsheet, p.flowsheet.compounds + name))
    }

    fun remove(name: String) = viewModelScope.launch {
        val p = projects.observeProject(projectId).first() ?: return@launch
        val used = p.flowsheet.objects.count { name in it.composition }
        projects.saveFlowsheet(projectId, FlowsheetEditing.setCompounds(p.flowsheet, p.flowsheet.compounds - name))
        if (used > 0) _messages.send("Removed $name from $used stream composition(s); check that they still add up to 1")
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FluidPackageScreen(onBack: () -> Unit, viewModel: FluidPackageViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fluid package") },
                navigationIcon = { BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Property package", style = MaterialTheme.typography.titleMedium)
            ui.packages.forEach { name ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(selected = ui.propertyPackage == name, role = Role.RadioButton) { viewModel.setPackage(name) },
                ) {
                    RadioButton(selected = ui.propertyPackage == name, onClick = null)
                    Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                }
            }
            Text(
                "Binary interaction parameters come from DWSIM's databanks for the chosen package. Pairs without data use zero. Check them for polar or non-ideal systems.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()

            Text("Components (${ui.compounds.size})", style = MaterialTheme.typography.titleMedium)
            if (ui.compounds.isEmpty()) {
                Text("No components yet. Search below and tap to add.", color = MaterialTheme.valueColors.underSpecifiedText)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ui.compounds.forEach { c ->
                    InputChip(
                        selected = false,
                        onClick = { viewModel.remove(c) },
                        label = { Text(c) },
                        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove $c") },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
            OutlinedTextField(
                value = ui.query,
                onValueChange = viewModel::setQuery,
                label = { Text("Search compounds") },
                placeholder = { Text("Name, CAS or shorthand: CO2, nC4, TEG") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("compound_search"),
            )
            ui.offlineReason?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.valueColors.underSpecifiedText)
            }
            ui.results.forEach { hit ->
                val added = hit.name in ui.compounds
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(enabled = !added, role = Role.Button, onClickLabel = "Add ${hit.name}") { viewModel.add(hit.name) }
                        .padding(vertical = 6.dp),
                ) {
                    Text(if (added) "${hit.name}  ✓" else hit.name, style = MaterialTheme.typography.bodyLarge)
                    Text(hit.detail, style = NumberStyles.tag, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
