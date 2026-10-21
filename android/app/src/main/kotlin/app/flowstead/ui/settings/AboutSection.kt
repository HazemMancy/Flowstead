/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.flowstead.BuildConfig
import app.flowstead.core.update.UpdateCheck
import app.flowstead.ui.about.Credits
import app.flowstead.ui.components.FlowsteadMark
import app.flowstead.ui.components.Wordmark
import app.flowstead.ui.theme.NumberStyles
import app.flowstead.ui.theme.valueColors

/** Who built the app and on what, and "Check for updates" against the GitHub releases. */
@Composable
internal fun AboutSection(state: SettingsUiState, vm: SettingsViewModel, onOpenAbout: () -> Unit) {
    Text("About Flowstead", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        FlowsteadMark(40.dp)
        Spacer(Modifier.padding(start = 12.dp))
        Column {
            Wordmark(MaterialTheme.typography.titleLarge)
            Text("Version ${BuildConfig.VERSION_NAME}", style = NumberStyles.tag, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Text(Credits.DEVELOPER, style = MaterialTheme.typography.bodyLarge)
    Text(Credits.ENGINE, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

    val uri = LocalUriHandler.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = vm::checkForUpdates,
            enabled = state.update != UpdateState.Checking,
            modifier = Modifier.heightIn(min = 48.dp).testTag("check_updates"),
        ) { Text("Check for updates") }
        if (state.update == UpdateState.Checking) CircularProgressIndicator(Modifier.size(24.dp))
    }
    val live = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    when (val u = state.update) {
        is UpdateState.Done -> when (val r = u.result) {
            is UpdateCheck.UpToDate -> Text(
                "✓ You have the latest version (${BuildConfig.VERSION_NAME}).",
                color = MaterialTheme.valueColors.convergedText, style = MaterialTheme.typography.bodyMedium, modifier = live,
            )
            is UpdateCheck.Available -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Version ${r.version} is available.", style = MaterialTheme.typography.bodyLarge, modifier = live)
                Text(
                    "Download the APK, open it and allow the install; it updates Flowstead and keeps your projects.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { uri.openUri(r.apkUrl ?: r.pageUrl) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("download_update"),
                    ) { Text("Download ${r.version}") }
                    TextButton(onClick = { uri.openUri(r.pageUrl) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("What's new") }
                }
            }
        }
        is UpdateState.Failed -> Text(
            "Couldn't check: ${u.reason}",
            color = MaterialTheme.valueColors.error, style = MaterialTheme.typography.bodyMedium, modifier = live,
        )
        else -> Unit
    }
    TextButton(onClick = onOpenAbout, modifier = Modifier.heightIn(min = 48.dp).testTag("open_about")) {
        Text("Licences, privacy policy and source code")
    }
}
