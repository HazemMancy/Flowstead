/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.flowstead.BuildConfig
import app.flowstead.core.update.UpdateCheck
import app.flowstead.core.update.UpdatePrompt
import app.flowstead.data.update.AppUpdater
import app.flowstead.data.update.InstallState
import app.flowstead.data.update.UpdateChecker
import app.flowstead.data.update.UpdatePromptStore
import app.flowstead.ui.components.FlowsteadMark
import app.flowstead.ui.theme.valueColors
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
    /** The version whose finished download the user has dismissed, so its popup doesn't come back. */
    private val _dismissed = MutableStateFlow<String?>(null)
    val dismissed: StateFlow<String?> = _dismissed

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

    /** Installs the finished download (test builds: Android's installer; releases: downloads again). */
    fun installNow() = updater.install()

    fun permissionSettings() = updater.permissionSettings()

    fun dismissDownloaded(version: String) {
        _dismissed.value = version
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
    val dismissed by viewModel.dismissed.collectAsStateWithLifecycle()
    // Android's own install prompt takes over from here.
    LaunchedEffect(install) { if (install is InstallState.Prompted) viewModel.later() }
    val done = install as? InstallState.Downloaded
    // A finished download always pops up (also when the popup was hidden, or the download started in Settings).
    if (done != null && done.version != dismissed || install == InstallState.NeedsPermission && dismissed != PERMISSION) {
        val context = LocalContext.current
        DownloadedDialog(
            version = done?.version,
            install = install,
            onInstall = viewModel::installNow,
            onPermission = { runCatching { context.startActivity(viewModel.permissionSettings()) } },
            onDownloads = { done?.let { runCatching { context.startActivity(it.downloads) } } },
            onLater = { viewModel.dismissDownloaded(done?.version ?: PERMISSION) },
        )
        return
    }
    val r = offer ?: return
    UpdateDialog(r, install, onUpdate = viewModel::update, onLater = viewModel::later)
}

/** What dismissing the "allow installs" popup is remembered as. */
private const val PERMISSION = "permission"

/**
 * The download is finished and checked. Test builds (BuildConfig.DIRECT_INSTALL) install it from here; releases
 * can't start an install themselves (no install permission, D74), so Android's "Download complete" notification or
 * the file in Downloads installs it.
 */
@Composable
internal fun DownloadedDialog(
    version: String?,
    install: InstallState,
    onInstall: () -> Unit,
    onPermission: () -> Unit,
    onDownloads: () -> Unit,
    onLater: () -> Unit,
) {
    val canInstall = (install as? InstallState.Downloaded)?.canInstall == true || install == InstallState.NeedsPermission
    AlertDialog(
        onDismissRequest = onLater,
        icon = { FlowsteadMark(40.dp) },
        title = { Text(if (version != null) "Flowstead $version is downloaded" else "Allow Flowstead to install its update") },
        text = {
            Text(
                when {
                    install == InstallState.NeedsPermission ->
                        "Android needs your permission, once, for Flowstead to install its updates. Tap Allow, switch it on, come back and tap Install."
                    canInstall -> "Downloaded and checked. Tap Install; Android asks you to confirm the update. Your projects are kept."
                    else -> "Downloaded and checked. Tap Android's \"Flowstead $version\" notification to install it, or open flowstead-v$version.apk in Downloads. Your projects are kept."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            when {
                install == InstallState.NeedsPermission -> Row {
                    TextButton(onClick = onPermission) { Text("Allow") }
                    TextButton(onClick = onInstall, modifier = Modifier.testTag("prompt_install")) { Text("Install") }
                }
                canInstall -> TextButton(onClick = onInstall, modifier = Modifier.testTag("prompt_install")) { Text("Install") }
                else -> TextButton(onClick = { onDownloads(); onLater() }, modifier = Modifier.testTag("prompt_downloads")) { Text("Open Downloads") }
            }
        },
        dismissButton = { TextButton(onClick = onLater, modifier = Modifier.testTag("prompt_later")) { Text("Later") } },
        modifier = Modifier.testTag("update_downloaded"),
    )
}

@Composable
internal fun UpdateDialog(r: UpdateCheck.Available, install: InstallState, onUpdate: () -> Unit, onLater: () -> Unit) {
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    val busy = install is InstallState.Downloading || install == InstallState.Verifying
    AlertDialog(
        onDismissRequest = onLater,
        icon = { FlowsteadMark(40.dp) },
        title = { Text("Flowstead ${r.version} is ready") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "A new version is available. Update downloads it here and checks it; then tap Android's \"Download complete\" notification to install it. Your projects are kept.",
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

                    is InstallState.Failed -> Text("Update failed: ${install.reason}", color = MaterialTheme.valueColors.error, style = MaterialTheme.typography.bodySmall)
                    else -> Unit
                }
            }
        },
        confirmButton = {
            if (install is InstallState.Failed) {
                TextButton(onClick = { uri.openUri(r.apkUrl ?: r.pageUrl); onLater() }) { Text("Download in the browser") }
            } else if (install is InstallState.Downloaded) {
                TextButton(onClick = { runCatching { context.startActivity(install.downloads) }; onLater() }) { Text("Open Downloads") }
            } else {
                TextButton(onClick = onUpdate, enabled = !busy && r.apkUrl != null, modifier = Modifier.testTag("prompt_update")) { Text("Update") }
            }
        },
        dismissButton = {
            TextButton(onClick = onLater, modifier = Modifier.testTag("prompt_later")) { Text(if (busy) "Hide" else if (install is InstallState.Downloaded) "Done" else "Later") }
        },
        modifier = Modifier.testTag("update_prompt"),
    )
}
