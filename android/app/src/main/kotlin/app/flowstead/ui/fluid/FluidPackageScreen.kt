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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import app.flowstead.core.compounds.Compound
import app.flowstead.core.compounds.CompoundCatalogue
import app.flowstead.core.format.ValueFormat
import app.flowstead.core.model.EditRejected
import app.flowstead.core.model.FlowsheetEditing
import app.flowstead.core.model.Hypothetical
import app.flowstead.core.model.LiquidDensityMethods
import app.flowstead.core.units.Quantity
import app.flowstead.core.units.UnitSet
import app.flowstead.data.ProjectRepository
import app.flowstead.data.compounds.CompoundLibrary
import app.flowstead.data.engine.EngineClient
import app.flowstead.data.engine.EngineResult
import app.flowstead.data.engine.ON_DEVICE_ENGINE
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

data class FluidUi(
    val loaded: Boolean = false,
    val propertyPackage: String? = null,
    /** Null: the engine default; [LiquidDensityMethods.COSTALD]: COSTALD. */
    val liquidDensity: String? = null,
    val compounds: List<String> = emptyList(),
    val hypotheticals: List<Hypothetical> = emptyList(),
    val packages: List<String> = OfflineCatalogue.propertyPackages,
    val query: String = "",
    /** Family filter; null = all, [CompoundCatalogue.COMMON] = the oil & gas favourites. */
    val family: String? = CompoundCatalogue.COMMON,
    val families: List<String> = emptyList(),
    val results: List<Compound> = emptyList(),
    val libraryLoaded: Boolean = false,
    val librarySize: Int = 0,
    val unitSet: UnitSet = UnitSet.SI,
)

private data class Search(val query: String, val family: String?)

/**
 * Fluid package: property package and the component list. Compounds come from the bundled
 * library (all of DWSIM's databases), so searching works offline; the engine solves with the same
 * databases, so a compound found here is one the engine knows.
 */
@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class FluidPackageViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val projects: ProjectRepository,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val engine: EngineClient,
    private val library: CompoundLibrary,
) : ViewModel() {
    private val projectId = savedState.toRoute<FluidRoute>().projectId
    private val search = MutableStateFlow(Search("", CompoundCatalogue.COMMON))
    private val packages = MutableStateFlow(OfflineCatalogue.propertyPackages)
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private val results: Flow<Pair<CompoundCatalogue, List<Compound>>> = search.debounce(150).mapLatest { s ->
        val c = library.catalogue()
        c to c.search(s.query, s.family, limit = 300)
    }

    val state: StateFlow<FluidUi> = combine(projects.observeProject(projectId), search, results, packages, settings.settings) { p, s, (cat, hits), pk, st ->
        FluidUi(
            loaded = p != null,
            propertyPackage = p?.flowsheet?.propertyPackage,
            liquidDensity = p?.flowsheet?.liquidDensity,
            compounds = p?.flowsheet?.compounds.orEmpty(),
            hypotheticals = p?.flowsheet?.hypotheticals.orEmpty(),
            packages = pk,
            query = s.query,
            family = s.family,
            families = cat.families,
            results = hits,
            libraryLoaded = true,
            librarySize = cat.all.size,
            unitSet = st.unitSet,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FluidUi())

    init {
        viewModelScope.launch {
            val url = settings.settings.first().engineTarget
            // The bundled list is the on-device engine's own set; don't load DWSIM just for it.
            if (url.isBlank() || url == ON_DEVICE_ENGINE) return@launch
            val r = engine.propertyPackages(url, secrets.apiKey())
            if (r is EngineResult.Ok) packages.value = r.value.filter { it.mvp }.map { it.name }.ifEmpty { OfflineCatalogue.propertyPackages }
        }
    }

    fun setQuery(q: String) = search.value.let { search.value = it.copy(query = q, family = if (q.isNotBlank() && it.family == CompoundCatalogue.COMMON) null else it.family) }

    fun setFamily(family: String?) {
        search.value = search.value.copy(family = family)
    }

    fun setPackage(name: String) = viewModelScope.launch {
        val p = projects.observeProject(projectId).first() ?: return@launch
        projects.saveFlowsheet(projectId, p.flowsheet.copy(propertyPackage = name))
    }

    fun setLiquidDensity(method: String?) = viewModelScope.launch {
        val p = projects.observeProject(projectId).first() ?: return@launch
        projects.saveFlowsheet(projectId, p.flowsheet.copy(liquidDensity = method))
    }

    fun add(name: String) = viewModelScope.launch {
        val p = projects.observeProject(projectId).first() ?: return@launch
        if (name in p.flowsheet.compounds) {
            _messages.send("$name is already in the list")
            return@launch
        }
        projects.saveFlowsheet(projectId, FlowsheetEditing.setCompounds(p.flowsheet, p.flowsheet.compounds + name))
    }

    private val _estimate = MutableStateFlow<EstimateState>(EstimateState.Idle)
    val estimate: StateFlow<EstimateState> = _estimate

    fun clearEstimate() { _estimate.value = EstimateState.Idle }

    /** The engine's constants for [h], before it is saved (HYSYS "Estimate unknown properties"). */
    fun estimate(h: Hypothetical) = viewModelScope.launch {
        val url = settings.settings.first().engineTarget
        if (url.isBlank()) {
            _estimate.value = EstimateState.Failed("Choose an engine in Settings to estimate properties")
            return@launch
        }
        _estimate.value = EstimateState.Working
        _estimate.value = when (val r = engine.estimateHypotheticals(url, secrets.apiKey(), listOf(h))) {
            is EngineResult.Ok -> r.value.singleOrNull()?.let { EstimateState.Done(h, it) } ?: EstimateState.Failed("The engine returned no estimate")
            is EngineResult.Failed -> EstimateState.Failed(r.reason)
        }
    }

    fun saveHypothetical(h: Hypothetical, onSaved: () -> Unit) = viewModelScope.launch {
        val p = projects.observeProject(projectId).first() ?: return@launch
        try {
            projects.saveFlowsheet(projectId, FlowsheetEditing.upsertHypothetical(p.flowsheet, h))
            onSaved()
        } catch (e: EditRejected) {
            _messages.send(e.message ?: "Not saved")
        }
    }

    fun deleteHypothetical(name: String) = remove(name)

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
    val estimate by viewModel.estimate.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Hypothetical?>(null) }
    var creating by remember { mutableStateOf(false) }
    FluidPackageContent(
        ui, snackbar, viewModel::setPackage, viewModel::add, viewModel::remove, viewModel::setQuery, viewModel::setFamily, onBack,
        onNewHypothetical = { viewModel.clearEstimate(); creating = true },
        onEditHypothetical = { viewModel.clearEstimate(); editing = it },
        onLiquidDensity = viewModel::setLiquidDensity,
    )
    if (creating || editing != null) {
        val close = { creating = false; editing = null }
        HypotheticalSheet(
            initial = editing,
            unitSet = ui.unitSet,
            estimate = estimate,
            onEstimate = viewModel::estimate,
            onSave = { h -> viewModel.saveHypothetical(h, close) },
            onDelete = { name -> viewModel.deleteHypothetical(name); close() },
            onDismiss = close,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FluidPackageContent(
    ui: FluidUi,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    onPackage: (String) -> Unit = {},
    onAdd: (String) -> Unit = {},
    onRemove: (String) -> Unit = {},
    onQuery: (String) -> Unit = {},
    onFamily: (String?) -> Unit = {},
    onBack: () -> Unit = {},
    onNewHypothetical: () -> Unit = {},
    onEditHypothetical: (Hypothetical) -> Unit = {},
    onLiquidDensity: (String?) -> Unit = {},
) {
    var details by remember { mutableStateOf<Compound?>(null) }
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
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(padding).testTag("fluid_list"),
        ) {
            item { Text("Property package", style = MaterialTheme.typography.titleMedium) }
            items(ui.packages, key = { "pkg_$it" }) { name ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(selected = ui.propertyPackage == name, role = Role.RadioButton) { onPackage(name) },
                ) {
                    RadioButton(selected = ui.propertyPackage == name, onClick = null)
                    Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                }
            }
            item {
                Text(
                    "Binary interaction parameters come from DWSIM's databanks for the chosen package. Pairs without data use zero. Check them for polar or non-ideal systems.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { HorizontalDivider() }
            item { Text("Liquid density", style = MaterialTheme.typography.titleMedium) }
            items(LIQUID_DENSITY_OPTIONS, key = { "ld_${it.method}" }) { o ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(selected = ui.liquidDensity == o.method, role = Role.RadioButton) { onLiquidDensity(o.method) }
                        .testTag("liquid_density_${o.method ?: "default"}"),
                ) {
                    RadioButton(selected = ui.liquidDensity == o.method, onClick = null)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(o.label, style = MaterialTheme.typography.bodyLarge)
                        Text(o.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { HorizontalDivider() }
            item { Text("Components (${ui.compounds.size})", style = MaterialTheme.typography.titleMedium) }
            item {
                if (ui.compounds.isEmpty()) {
                    Text("No components yet. Search the library below and tap to add.", color = MaterialTheme.valueColors.underSpecifiedText)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ui.compounds.forEach { c ->
                        InputChip(
                            selected = false,
                            onClick = { onRemove(c) },
                            label = { Text(c) },
                            trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove $c") },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }
            item { HorizontalDivider() }
            item { Text("Hypothetical components (${ui.hypotheticals.size})", style = MaterialTheme.typography.titleMedium) }
            item {
                Text(
                    "Pseudo-components for petroleum cuts or compounds not in the library, as in HYSYS: from the normal " +
                        "boiling point, molecular weight and specific gravity.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(ui.hypotheticals, key = { "hypo_${it.name}" }) { h ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = "Edit ${h.name}") { onEditHypothetical(h) },
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(h.name, style = MaterialTheme.typography.bodyLarge)
                        Text(hypoSummary(h, ui.unitSet), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(Hypothetical.TAG, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                }
            }
            item {
                OutlinedButton(onClick = onNewHypothetical, modifier = Modifier.testTag("add_hypothetical")) { Text("Add hypothetical") }
            }
            item { HorizontalDivider() }
            item {
                Text(
                    if (ui.libraryLoaded) "Compound library · ${ui.librarySize} compounds" else "Compound library",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "DWSIM's databases (ChemSep, ChEDL Thermo, CoolProp, electrolytes, food, DWSIM), on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedTextField(
                    value = ui.query,
                    onValueChange = onQuery,
                    label = { Text("Search compounds") },
                    placeholder = { Text("Name, formula, CAS or shorthand: CO2, nC4, TEG") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("compound_search"),
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("family_chips")) {
                    item { FamilyChip("All", ui.family == null) { onFamily(null) } }
                    item { FamilyChip(CompoundCatalogue.COMMON, ui.family == CompoundCatalogue.COMMON) { onFamily(CompoundCatalogue.COMMON) } }
                    items(ui.families) { f -> FamilyChip(f, ui.family == f) { onFamily(f) } }
                }
            }
            if (ui.libraryLoaded && ui.results.isEmpty()) {
                item { Text("No compound matches.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(ui.results, key = { "c_${it.name}" }) { c ->
                CompoundRow(c, added = c.name in ui.compounds, unitSet = ui.unitSet, onAdd = { onAdd(c.name) }, onInfo = { details = c })
            }
        }
    }
    details?.let { c ->
        CompoundSheet(c, added = c.name in ui.compounds, unitSet = ui.unitSet, onAdd = { onAdd(c.name); details = null }, onDismiss = { details = null })
    }
}

private data class LiquidDensityOption(val method: String?, val label: String, val detail: String)

private val LIQUID_DENSITY_OPTIONS = listOf(
    LiquidDensityOption(
        null, "DWSIM default",
        "Pure-component liquid densities (experimental data, else Rackett), mixed by mass. Cubic packages use COSTALD when dissolved gas is above its critical temperature.",
    ),
    LiquidDensityOption(
        LiquidDensityMethods.COSTALD, "COSTALD (as HYSYS)",
        "COSTALD for every liquid, as HYSYS does for hydrocarbons. About 1 % high for water.",
    ),
)

/** "NBP 174.2 °C · MW 142.3 · SG 0.734": what the user gave. */
private fun hypoSummary(h: Hypothetical, unitSet: UnitSet): String = buildList {
    h.normalBoilingPoint?.let { add("NBP " + ValueFormat.si(it, unitSet.unitFor(Quantity.Temperature))) }
    h.molecularWeight?.let { add("MW " + ValueFormat.number(it, 1)) }
    h.specificGravity?.let { add("SG " + ValueFormat.number(it, 3)) }
    if (h.criticalTemperature != null || h.criticalPressure != null || h.acentricFactor != null) add("critical constants given")
}.joinToString("  ·  ")

@Composable
private fun FamilyChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        modifier = Modifier.heightIn(min = 48.dp).testTag("family_$label"),
    )
}

@Composable
private fun CompoundRow(c: Compound, added: Boolean, unitSet: UnitSet, onAdd: () -> Unit, onInfo: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clickable(enabled = !added, role = Role.Button, onClickLabel = "Add ${c.name}") { onAdd() }
                .padding(vertical = 6.dp)
                .testTag("compound_${c.name}"),
        ) {
            Text(if (added) "${c.name}  ✓" else c.name, style = MaterialTheme.typography.bodyLarge)
            Text(summary(c, unitSet), style = NumberStyles.tag, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onInfo) { Icon(Icons.Outlined.Info, contentDescription = "Details of ${c.name}") }
    }
}

private fun summary(c: Compound, unitSet: UnitSet): String = buildList {
    if (c.formula.isNotBlank()) add(c.formula)
    c.molecularWeight?.let { add("MW " + ValueFormat.number(it, 2)) }
    c.normalBoilingPointK?.let { add("Tb " + ValueFormat.si(it, unitSet.unitFor(Quantity.Temperature))) }
    add(c.database)
}.joinToString(" · ")

/** Every database constant of one compound, in the user's units. Database values, not engine results. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompoundSheet(c: Compound, added: Boolean, unitSet: UnitSet, onAdd: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 24.dp)
                .testTag("compound_sheet"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(c.name, style = MaterialTheme.typography.titleLarge)
            Text("${c.family} · ${c.database} database", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val t = unitSet.unitFor(Quantity.Temperature)
            val p = unitSet.unitFor(Quantity.Pressure)
            val rows = listOf(
                "Formula" to c.formula.ifBlank { null },
                "Structure" to c.structure.ifBlank { null },
                "CAS" to c.cas.ifBlank { null },
                "SMILES" to c.smiles.ifBlank { null }?.let { if (it.length > 60) it.take(57) + "…" else it },
                "Molecular weight" to c.molecularWeight?.let { ValueFormat.number(it, 3) + " kg/kmol" },
                "Normal boiling point" to c.normalBoilingPointK?.let { ValueFormat.si(it, t) },
                "Melting point" to c.meltingPointK?.let { ValueFormat.si(it, t) },
                "Critical temperature" to c.criticalTemperatureK?.let { ValueFormat.si(it, t) },
                "Critical pressure" to c.criticalPressurePa?.let { ValueFormat.si(it, p) },
                "Critical volume" to c.criticalVolumeM3PerMol?.let { ValueFormat.number(it * 1000.0, 4) + " m³/kmol" },
                "Critical compressibility" to c.criticalCompressibility?.let { ValueFormat.number(it, 4) },
                "Acentric factor" to c.acentricFactor?.let { ValueFormat.number(it, 4) },
                "Dipole moment" to c.dipoleMomentCoulombMetre?.let { ValueFormat.number(it / DEBYE, 3) + " D" },
                "ΔHf° ideal gas, 25 °C" to c.idealGasEnthalpyOfFormationJPerMol?.let { ValueFormat.number(it / 1000.0, 2) + " kJ/mol" },
            )
            rows.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(value ?: "—", style = NumberStyles.value, color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                }
            }
            if (c.aliases.isNotEmpty()) Text("Also found as: ${c.aliases.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
            Text(
                "Database values from DWSIM's compound library; \"—\" where the database has none.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onAdd, enabled = !added, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("add_from_sheet")) {
                Text(if (added) "Already in the component list" else "Add to component list")
            }
        }
    }
}

/** 1 debye in C·m. */
private const val DEBYE = 3.33564e-30
