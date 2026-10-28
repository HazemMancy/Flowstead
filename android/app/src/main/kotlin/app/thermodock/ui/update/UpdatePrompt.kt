/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.thermodock.BuildConfig
import app.thermodock.core.update.UpdateCheck
import app.thermodock.core.update.UpdatePrompt
import app.thermodock.data.update.AppUpdater
import app.thermodock.data.update.InstallState
import app.thermodock.data.update.UpdateChecker
import app.thermodock.data.update.UpdatePromptStore
import app.thermodock.ui.components.ThermoDockMark
import app.thermodock.ui.theme.valueColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Checks for a new release when the app opens and offers it in a dialog (decision D45): once per launch
 * (not again on rotation or when returning to the app), at most three times per version, and never when the
 * user turned "Check for updates when Flowstead opens" off.
 */
@HiltViewModel
class UpdatePromptViewModel @Inject constructor(
    private val checker: UpdateChecker,
    private val store: UpdatePromptStore,
    private val updater: AppUpdater,
) : ViewModel() {
    private val _offer = MutableStateFlow<UpdateCheck.Available?>(null)
    val offer: StateFlow<UpdateCheck.Available?> = _offer
    val install: StateFlow<InstallState> = updater.state

    init {
        if (launchChecked.compareAndSet(false, true)) viewModelScope.launch { checkOnce() }
    }

    internal suspend fun checkOnce() {
        if (!store.checkOnOpen.first()) return
        val available = checker.check(BuildConfig.VERSION_NAME).getOrNull() as? UpdateCheck.Available ?: return
        val next = UpdatePrompt.next(available.version, store.seen()) ?: return
        store.remember(next)
        _offer.value = available
    }

    fun update() {
        _offer.value?.let(updater::start)
    }

    fun later() {
        _offer.value = null
    }

    companion object {
        /** Once per process, i.e. per launch of the app. */
        internal val launchChecked = AtomicBoolean(false)
    }
}

@Composable
fun UpdatePromptHost(viewModel: UpdatePromptViewModel = hiltViewModel()) {
    val offer by viewModel.offer.collectAsStateWithLifecycle()
    val install by viewModel.install.collectAsStateWithLifecycle()
    val r = offer ?: return
    // Android's own install prompt takes over from here.
    LaunchedEffect(install) { if (install is InstallState.Prompted) viewModel.later() }
    UpdateDialog(r, install, onUpdate = viewModel::update, onLater = viewModel::later)
}

@Composable
internal fun UpdateDialog(r: UpdateCheck.Available, install: InstallState, onUpdate: () -> Unit, onLater: () -> Unit) {
    val uri = LocalUriHandler.current
    val busy = install is InstallState.Downloading || install == InstallState.Verifying
    AlertDialog(
        onDismissRequest = onLater,
        icon = { ThermoDockMark(40.dp) },
        title = { Text("Flowstead ${r.version} is ready") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "A new version is available. Update opens the download in your browser; open the file when it finishes to install it. Your projects are kept.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                when (install) {
                    is InstallState.Downloading -> {
                        val f = install.fraction
                        Text("Downloading…" + (f?.let { " ${(it * 100).toInt()} %" } ?: ""), style = MaterialTheme.typography.bodySmall)
                        if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    InstallState.Verifying -> Text("Checking the download…", style = MaterialTheme.typography.bodySmall)
                    InstallState.NeedsPermission -> Text(
                        "Android needs your permission, once, for Flowstead to install updates: Settings → About → Update.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    is InstallState.Failed -> Text("Update failed: ${install.reason}", color = MaterialTheme.valueColors.error, style = MaterialTheme.typography.bodySmall)
                    else -> Unit
                }
            }
        },
        confirmButton = {
            if (install is InstallState.Failed) {
                TextButton(onClick = { uri.openUri(r.apkUrl ?: r.pageUrl); onLater() }) { Text("Download in the browser") }
            } else {
                TextButton(onClick = onUpdate, enabled = !busy && r.apkUrl != null, modifier = Modifier.testTag("prompt_update")) { Text("Update") }
            }
        },
        dismissButton = {
            TextButton(onClick = onLater, modifier = Modifier.testTag("prompt_later")) { Text(if (busy) "Hide" else "Later") }
        },
        modifier = Modifier.testTag("update_prompt"),
    )
}
