/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.flowstead.BuildConfig
import app.flowstead.core.update.UpdateCheck
import app.flowstead.data.update.InstallState
import app.flowstead.ui.about.Credits
import app.flowstead.ui.about.DeveloperLine
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
    DeveloperLine()
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .toggleable(value = state.checkUpdatesOnOpen, role = Role.Switch, onValueChange = vm::setCheckUpdatesOnOpen)
            .testTag("check_updates_on_open"),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Check for updates when Flowstead opens", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Asks GitHub for the latest version; a new one is offered at most three times.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = state.checkUpdatesOnOpen, onCheckedChange = null)
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
                InstallUpdate(r, state.install, vm)
                TextButton(onClick = { uri.openUri(r.pageUrl) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("What's new") }
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

/**
 * Update (decision D79): the release's APK downloads here through Android's download service and is checked against its
 * published checksum; Android's "Download complete" notification installs it. The browser download is the fallback.
 */
@Composable
private fun InstallUpdate(r: UpdateCheck.Available, install: InstallState, vm: SettingsViewModel) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val live = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    val hint = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (install) {
        is InstallState.Downloading -> {
            val pct = install.fraction?.let { " ${(it * 100).toInt()} %" }.orEmpty()
            Text("Downloading ${install.version}…$pct", style = hint, color = muted, modifier = live)
            val f = install.fraction
            if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().testTag("update_progress"))
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("update_progress"))
            Text("You can keep using Flowstead; the download carries on in the background and in the notification.", style = hint, color = muted)
        }
        InstallState.Verifying -> Text("Checking the download…", style = hint, color = muted, modifier = live)
        InstallState.NeedsPermission -> {
            Text(
                "Android needs your permission, once, for Flowstead to install its updates: allow it, come back and tap Install.",
                style = hint, color = muted, modifier = live,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { context.startActivity(vm.installPermissionSettings()) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Allow")
                }
                Button(onClick = vm::retryInstall, modifier = Modifier.heightIn(min = 48.dp).testTag("retry_install")) { Text("Install") }
            }
        }
        is InstallState.Downloaded -> {
            Text(
                "Downloaded and checked. Tap the \"Flowstead ${install.version}\" notification to install it over this version (Android asks you to confirm), or open flowstead-v${install.version}.apk in Downloads. Your projects are kept.",
                style = hint, color = muted, modifier = live,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Test builds hold the install permission and install from here; releases go through Downloads (D74).
                if (install.canInstall) Button(onClick = vm::retryInstall, modifier = Modifier.heightIn(min = 48.dp).testTag("install_update")) { Text("Install") }
                OutlinedButton(onClick = { runCatching { context.startActivity(install.downloads) } }, modifier = Modifier.heightIn(min = 48.dp).testTag("open_downloads")) {
                    Text("Open Downloads")
                }
            }
        }
        is InstallState.Prompted -> {
            Text("The download opened in your browser: open the file when it finishes to install it. Your projects are kept.", style = hint, color = muted, modifier = live)
            install.confirm?.let { confirm ->
                Button(onClick = { runCatching { context.startActivity(confirm) } }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Open the install prompt")
                }
            }
        }
        InstallState.Idle, is InstallState.Failed -> {
            if (install is InstallState.Failed) {
                Text("Update failed: ${install.reason}", color = MaterialTheme.valueColors.error, style = MaterialTheme.typography.bodyMedium, modifier = live)
            }
            Text(
                "Downloads the update here and checks it; then tap Android's \"Download complete\" notification to install it. Your projects are kept.",
                style = hint, color = muted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.installUpdate(r) },
                    enabled = r.apkUrl != null,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("download_update"),
                ) { Text("Update to ${r.version}") }
                if (install is InstallState.Failed || r.apkUrl == null) {
                    TextButton(onClick = { uri.openUri(r.apkUrl ?: r.pageUrl) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Download in the browser")
                    }
                }
            }
        }
    }
}
