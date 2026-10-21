/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.datasheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.flowstead.core.model.DocumentInfo
import app.flowstead.core.model.SheetKeys
import app.flowstead.core.model.SimObject
import app.flowstead.core.model.displayName

/**
 * What goes on a data sheet beyond the simulation: the project's title block (shared by every sheet) and,
 * for one piece of equipment, its document number, revision, service, design data and notes.
 * [equipment] null: every equipment's sheet in one PDF.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataSheetSheet(
    info: DocumentInfo,
    equipment: SimObject?,
    equipmentCount: Int,
    exporting: Boolean,
    onInfo: (DocumentInfo) -> Unit,
    onField: (key: String, value: String) -> Unit,
    onCreatePdf: () -> Unit,
    onCreateExcel: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (equipment == null) "Data sheets — all equipment" else "Data sheet — ${equipment.tag}",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                if (equipment == null) "One document with a data sheet for each of the $equipmentCount pieces of equipment (Excel: one worksheet each)."
                else "${equipment.type.displayName()}. Process data come from the last solve; anything the engine didn't return prints as “—”.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val canCreate = !exporting && (equipment != null || equipmentCount > 0)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCreatePdf, enabled = canCreate, modifier = Modifier.weight(1f).testTag("create_data_sheet_pdf")) {
                    Text(if (exporting) "Creating…" else "PDF")
                }
                OutlinedButton(onClick = onCreateExcel, enabled = canCreate, modifier = Modifier.weight(1f).testTag("create_data_sheet_xlsx")) {
                    Text("Excel")
                }
            }

            if (equipment != null) {
                HorizontalDivider()
                Text("This equipment", style = MaterialTheme.typography.titleSmall)
                val sheet = equipment.sheet
                CommitField("Service", sheet[SheetKeys.SERVICE], "${equipment.id}.service", "e.g. Export gas to pipeline") { onField(SheetKeys.SERVICE, it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(2f)) {
                        CommitField("Document number", sheet[SheetKeys.DOCUMENT_NUMBER], "${equipment.id}.doc") { onField(SheetKeys.DOCUMENT_NUMBER, it) }
                    }
                    Column(Modifier.weight(1f)) {
                        CommitField("Rev.", sheet[SheetKeys.REVISION], "${equipment.id}.rev", "A") { onField(SheetKeys.REVISION, it) }
                    }
                }
                CommitField("Revision description", sheet[SheetKeys.REVISION_DESCRIPTION], "${equipment.id}.revdesc", "Issued for review") {
                    onField(SheetKeys.REVISION_DESCRIPTION, it)
                }
                Text("Mechanical / design data", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Optional. Empty fields print as blank boxes to complete by hand.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for ((key, label) in SheetKeys.DESIGN) {
                    CommitField(label, sheet[key], "${equipment.id}.$key") { onField(key, it) }
                }
                CommitField("Notes (one per line)", sheet[SheetKeys.NOTES], "${equipment.id}.notes", singleLine = false) { onField(SheetKeys.NOTES, it) }
            }

            HorizontalDivider()
            Text("Title block (all data sheets of this project)", style = MaterialTheme.typography.titleSmall)
            CommitField("Company", info.company, "doc.company", "Your company's name") { onInfo(info.copy(company = it)) }
            CommitField("Project", info.project, "doc.project", "Project name (defaults to this project's)") { onInfo(info.copy(project = it)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) { CommitField("Project No.", info.projectNumber, "doc.number") { onInfo(info.copy(projectNumber = it)) } }
                Column(Modifier.weight(1f)) { CommitField("Client", info.client, "doc.client") { onInfo(info.copy(client = it)) } }
            }
            CommitField("Site / location", info.site, "doc.site") { onInfo(info.copy(site = it)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) { CommitField("Prepared", info.preparedBy, "doc.prep") { onInfo(info.copy(preparedBy = it)) } }
                Column(Modifier.weight(1f)) { CommitField("Checked", info.checkedBy, "doc.chk") { onInfo(info.copy(checkedBy = it)) } }
                Column(Modifier.weight(1f)) { CommitField("Approved", info.approvedBy, "doc.appr") { onInfo(info.copy(approvedBy = it)) } }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Close") }
        }
    }
}

/** A text field that commits on Done or when focus leaves it, and only when the text changed. */
@Composable
private fun CommitField(
    label: String,
    value: String?,
    key: String,
    hint: String? = null,
    singleLine: Boolean = true,
    onCommit: (String) -> Unit,
) {
    val stored = value.orEmpty()
    var text by remember(key, stored) { mutableStateOf(stored) }
    fun commit() { if (text.trim() != stored) onCommit(text.trim()) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        placeholder = hint?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        keyboardOptions = KeyboardOptions(imeAction = if (singleLine) ImeAction.Done else ImeAction.Default),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { if (!it.isFocused) commit() }
            .testTag("sheet_$key"),
    )
}
