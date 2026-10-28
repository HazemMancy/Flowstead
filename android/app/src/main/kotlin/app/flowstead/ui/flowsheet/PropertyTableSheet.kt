/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.flowsheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.PropertyTables
import app.flowstead.core.model.ResultKeys
import app.flowstead.core.model.SimObject
import app.flowstead.core.model.displayName

/**
 * Picks what [obj]'s table on the flowsheet shows, as HYSYS's property tables on a PFD. The table is
 * drawn next to the object, moves with it, can be dragged, and prints on the PFD.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PropertyTableSheet(fs: Flowsheet, obj: SimObject, onApply: (List<String>) -> Unit, onDismiss: () -> Unit) {
    val existing = fs.tables.firstOrNull { it.objectId == obj.id }
    val available = PropertyTables.available(fs, obj)
    val chosen = remember(obj.id) { mutableStateListOf<String>().apply { addAll(existing?.keys ?: PropertyTables.defaults(fs, obj)) } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Table on flowsheet — ${obj.tag}", style = MaterialTheme.typography.titleLarge)
            Text(
                "${obj.type.displayName()}. Tick what to show; values come from the last solve, in your units. " +
                    "Drag the table to place it; it prints on the PFD.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (key in available) {
                val on = key in chosen
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .toggleable(on, role = Role.Checkbox) { if (it) chosen += key else chosen -= key }
                        .testTag("table_$key"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = on, onCheckedChange = null)
                    Text(ResultKeys.label(key), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                Button(
                    onClick = { onApply(available.filter { it in chosen }); onDismiss() },
                    enabled = chosen.isNotEmpty(),
                    modifier = Modifier.testTag("table_apply"),
                ) { Text(if (existing == null) "Show table" else "Update table") }
                if (existing != null) {
                    TextButton(onClick = { onApply(emptyList()); onDismiss() }) { Text("Remove table", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    }
}
