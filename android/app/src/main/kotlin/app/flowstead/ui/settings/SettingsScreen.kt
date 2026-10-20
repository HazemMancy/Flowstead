/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.settings

import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import app.flowstead.BuildConfig
import app.flowstead.data.settings.EngineMode
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flowstead.core.format.ValueFormat
import app.flowstead.core.units.EngUnit
import app.flowstead.core.units.Quantity
import app.flowstead.core.units.UnitSet
import app.flowstead.ui.components.BackButton
import app.flowstead.ui.theme.NumberStyles
import app.flowstead.ui.theme.ThemeMode
import app.flowstead.ui.theme.valueColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (!state.loaded) return@Scaffold
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            EngineSection(state, viewModel)
            HorizontalDivider()
            UnitsSection(state, viewModel)
            HorizontalDivider()
            ThemeSection(state.settings.themeMode, viewModel::setTheme)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun EngineSection(state: SettingsUiState, vm: SettingsViewModel) {
    SectionTitle("Engine")
    val onDevice = state.settings.engineMode == EngineMode.OnDevice
    EngineChoice(
        selected = onDevice,
        enabled = BuildConfig.LOCAL_ENGINE,
        title = "On this phone",
        detail = if (BuildConfig.LOCAL_ENGINE) "DWSIM runs on the phone's processor. Works offline; the first solve after starting the app takes a few seconds to load DWSIM."
        else "Not included in this build.",
        tag = "engine_on_device",
    ) { vm.setEngineMode(EngineMode.OnDevice) }
    EngineChoice(
        selected = !onDevice,
        enabled = true,
        title = "Remote engine",
        detail = "Your own Flowstead engine server: a VPS or a PC on the same network. HTTPS only.",
        tag = "engine_remote",
    ) { vm.setEngineMode(EngineMode.Remote) }
    if (!onDevice) RemoteEngineFields(state, vm)
    ConnectionTest(state, vm, if (onDevice) "Start and test" else "Test connection")
}

@Composable
private fun EngineChoice(selected: Boolean, enabled: Boolean, title: String, detail: String, tag: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .testTag(tag),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RemoteEngineFields(state: SettingsUiState, vm: SettingsViewModel) {
    var url by rememberSaveable(state.settings.engineUrl) { mutableStateOf(state.settings.engineUrl) }
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text("Engine address") },
        placeholder = { Text("https://engine.example.com") },
        singleLine = true,
        isError = state.urlError != null,
        supportingText = state.urlError?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { vm.saveEngineUrl(url) }),
        modifier = Modifier.fillMaxWidth().testTag("engine_url"),
    )
    if (url.trim().trimEnd('/') != state.settings.engineUrl) {
        Button(onClick = { vm.saveEngineUrl(url) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save address") }
    }

    var key by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(
        value = key,
        onValueChange = { key = it },
        label = { Text(if (state.hasApiKey) "API key (stored — enter to replace)" else "API key") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { vm.saveApiKey(key); key = "" }),
        modifier = Modifier.fillMaxWidth().testTag("api_key"),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (key.isNotBlank()) {
            Button(onClick = { vm.saveApiKey(key); key = "" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save key") }
        }
        if (state.hasApiKey) {
            TextButton(onClick = { vm.clearApiKey() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove key") }
        }
    }
}

@Composable
private fun ConnectionTest(state: SettingsUiState, vm: SettingsViewModel, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = { vm.testConnection() },
            enabled = state.connection != ConnectionState.Testing,
            modifier = Modifier.heightIn(min = 48.dp).testTag("test_engine"),
        ) { Text(label) }
        if (state.connection == ConnectionState.Testing) CircularProgressIndicator(Modifier.size(24.dp))
    }
    val colors = MaterialTheme.valueColors
    when (val c = state.connection) {
        is ConnectionState.Connected -> Text(
            "✓ Ready · ${c.summary}",
            color = colors.convergedText,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        is ConnectionState.Failed -> Text(
            c.reason,
            color = colors.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        else -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnitsSection(state: SettingsUiState, vm: SettingsViewModel) {
    SectionTitle("Units")
    val current = state.settings.unitSet
    val options = UnitSet.PRESETS.map { it.name } + UnitSet.CUSTOM
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, name ->
            SegmentedButton(
                selected = current.name == name,
                onClick = {
                    UnitSet.PRESETS.firstOrNull { it.name == name }?.let(vm::selectPreset)
                        ?: vm.selectPreset(current.copy(name = UnitSet.CUSTOM))
                },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(name) }
        }
    }
    Text(
        "Pick any unit below to make a custom set. Values are always stored in SI.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SettingsViewModel.EDITABLE_QUANTITIES.forEach { q ->
        UnitPicker(q, current.unitFor(q)) { vm.setUnit(q, it) }
    }

    val patmKpa = ValueFormat.number(state.settings.defaultAtmosphericPressurePa / 1e3, 3)
    var patm by rememberSaveable(patmKpa) { mutableStateOf(patmKpa) }
    OutlinedTextField(
        value = patm,
        onValueChange = { patm = it },
        label = { Text("Atmospheric reference for barg / psig (kPa abs)") },
        supportingText = { Text(state.patmError ?: "Default for new projects. 101.325 kPa at sea level.") },
        isError = state.patmError != null,
        singleLine = true,
        textStyle = NumberStyles.value,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { vm.setAtmosphericPressureKPa(patm) }),
        modifier = Modifier.fillMaxWidth().testTag("patm"),
    )
    if (patm != patmKpa) {
        Button(onClick = { vm.setAtmosphericPressureKPa(patm) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Save reference")
        }
    }
}

@Composable
private fun UnitPicker(quantity: Quantity, selected: EngUnit, onSelect: (EngUnit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label(quantity), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(selected.symbol.ifEmpty { "fraction" }, style = NumberStyles.value)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                SettingsViewModel.unitsFor(quantity).forEach { u ->
                    DropdownMenuItem(
                        text = { Text(u.symbol.ifEmpty { "fraction" }, style = NumberStyles.value) },
                        onClick = { open = false; onSelect(u) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSection(mode: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    SectionTitle("Theme")
    val modes = ThemeMode.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        modes.forEachIndexed { i, m ->
            SegmentedButton(
                selected = mode == m,
                onClick = { onSelect(m) },
                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
            ) { Text(m.name) }
        }
    }
}

private fun label(q: Quantity): String = when (q) {
    Quantity.Temperature -> "Temperature"
    Quantity.TemperatureDifference -> "Temperature difference"
    Quantity.Pressure -> "Pressure"
    Quantity.PressureDifference -> "Pressure drop"
    Quantity.MolarFlow -> "Molar flow"
    Quantity.MassFlow -> "Mass flow"
    Quantity.VolumeFlow -> "Actual volume flow"
    Quantity.Power -> "Duty and power"
    Quantity.MassEnthalpy -> "Mass enthalpy"
    Quantity.MolarEnthalpy -> "Molar enthalpy"
    Quantity.MassEntropy -> "Mass entropy"
    Quantity.MassHeatCapacity -> "Heat capacity"
    Quantity.Density -> "Density"
    Quantity.MolecularWeight -> "Molecular weight"
    Quantity.Viscosity -> "Viscosity"
    Quantity.Fraction -> "Fractions"
    Quantity.Ratio -> "Ratios"
    Quantity.Count -> "Counts"
    Quantity.Length -> "Length"
    Quantity.HeatTransferCoefficient -> "Heat transfer coefficient"
}
