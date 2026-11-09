/*
 * Flowstead (D133): a sub-flowsheet block's sheet, as HYSYS's sub-flowsheet view: its name, its fluid package (the
 * parent's or its own), the streams that go into and come out of it (picked from the parent's free streams, as HYSYS's
 * Connections tab), each with its transfer basis when the packages differ, and their values from the last solve.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.flowsheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.PropertyTable
import app.flowstead.core.model.PropertyTables
import app.flowstead.core.model.ResultView
import app.flowstead.core.model.Subflowsheets
import app.flowstead.core.model.TransferBasis
import app.flowstead.core.units.UnitSet

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SubflowsheetSheet(
    fs: Flowsheet,
    blockId: String,
    results: ResultView,
    unitSet: UnitSet,
    vm: FlowsheetViewModel,
    onOpenFluid: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val b = Subflowsheets.of(fs, blockId) ?: return
    val bd = Subflowsheets.boundary(fs, blockId)
    val own = b.fluid != null
    val parentPkg = Subflowsheets.fluidAt(fs, b.parent).propertyPackage ?: "—"
    val differs = Subflowsheets.regionOf(fs, b.id) != Subflowsheets.regionOf(fs, b.parent)
    var name by remember(b.tag) { mutableStateOf(b.tag) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Sub-flowsheet", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("block_name"),
                trailingIcon = if (name.trim() != b.tag && name.isNotBlank()) ({ TextButton(onClick = { vm.renameBlock(b.id, name) }) { Text("Save") } }) else null,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.openLevel(b.id) }, modifier = Modifier.testTag("block_open")) { Text("Open") }
                OutlinedButton(onClick = { vm.ungroupBlock(b.id) }) { Text("Ungroup") }
                OutlinedButton(onClick = { vm.deleteBlock(b.id) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }

            HorizontalDivider()
            Text("Fluid package", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !own, onClick = { if (own) vm.setOwnFluid(b.id, false) }, label = { Text("The parent’s ($parentPkg)") })
                FilterChip(selected = own, onClick = { if (!own) { vm.setOwnFluid(b.id, true); onOpenFluid(b.id) } }, label = { Text("Its own") })
            }
            if (own) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${b.fluid?.propertyPackage ?: "No package"} · ${b.fluid?.compounds?.size ?: 0} components", Modifier.weight(1f))
                    TextButton(onClick = { onOpenFluid(b.id) }) { Text("Edit") }
                }
                Text(
                    "Solved on its own package: each stream crosses as a feed defined by the engine’s result on the other side, by its transfer basis (T–P: temperature and pressure; P–VF: pressure and vapour fraction), with its molar flow and composition, as HYSYS’s.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("On the parent’s package it solves with the parent as one flowsheet: the same numbers as without the block.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            HorizontalDivider()
            Text("Streams in and out", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StreamPicker("Add a stream in", Subflowsheets.connectable(fs, b.id, true).map { it.id to it.tag }, Modifier.weight(1f)) { vm.connectToBlock(b.id, it, true) }
                StreamPicker("Add a stream out", Subflowsheets.connectable(fs, b.id, false).map { it.id to it.tag }, Modifier.weight(1f)) { vm.connectToBlock(b.id, it, false) }
            }
            Text("Or drag a stream onto the block. Inside, each one is a stream to join to the equipment there.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (x in bd) {
                val outer = x.port?.outer?.let { o -> fs.objects.firstOrNull { it.id == o } }
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${if (x.feed) "In" else "Out"}: ${x.stream.tag}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        IconButton(onClick = { vm.disconnectFromBlock(b.id, x.stream.id) }) { Icon(Icons.Filled.Close, contentDescription = "Take ${x.stream.tag} out of the block") }
                    }
                    Text(if (outer != null) (if (x.feed) "from ${outer.tag}" else "to ${outer.tag}") else "Not matched to a stream outside",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (differs && x.port != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = x.port?.basis != TransferBasis.PVF, onClick = { vm.setTransferBasis(b.id, x.stream.id, TransferBasis.TP) }, label = { Text("T–P") })
                            FilterChip(selected = x.port?.basis == TransferBasis.PVF, onClick = { vm.setTransferBasis(b.id, x.stream.id, TransferBasis.PVF) }, label = { Text("P–VF") })
                        }
                    }
                    // The engine's values for the stream inside, from the last solve, in the user's units.
                    if (!results.stale) {
                        val rows = PropertyTables.rows(fs, PropertyTable(x.stream.id, listOf("temperature", "pressure", "massFlow")), results, unitSet)
                            .filter { it.value != "—" }
                        if (rows.isNotEmpty()) Text(rows.joinToString(" · ") { "${it.value} ${it.unit}" }, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StreamPicker(label: String, options: List<Pair<String, String>>, modifier: Modifier, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box(modifier) {
        OutlinedButton(onClick = { open = true }, enabled = options.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(if (options.isEmpty()) "$label: none free" else label)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, tag) -> DropdownMenuItem(text = { Text(tag) }, onClick = { open = false; onPick(id) }) }
        }
    }
}

