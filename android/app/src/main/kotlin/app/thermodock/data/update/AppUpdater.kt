/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import app.thermodock.core.update.UpdateCheck
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface InstallState {
    data object Idle : InstallState
    /** [fraction] is null while the size is unknown. */
    data class Downloading(val version: String, val fraction: Float?) : InstallState
    data object Verifying : InstallState
    /** Android needs the user to allow installs from ThermoDock once (Settings → Install unknown apps). */
    data object NeedsPermission : InstallState
    /** Android's "Update this app?" prompt is up (or [confirm] opens it if it couldn't appear on its own). */
    data class Prompted(val confirm: Intent? = null) : InstallState
    data class Failed(val reason: String) : InstallState
}

/** Downloads, verifies and hands a new release to Android's installer; an interface so tests needn't touch the network. */
interface AppUpdater {
    val state: StateFlow<InstallState>
    fun start(update: UpdateCheck.Available)
    /** Installs the downloaded file again, e.g. after the user allowed installs from ThermoDock. */
    fun install()
    /** The system screen where the user allows installs from ThermoDock. */
    fun permissionSettings(): Intent
}

/**
 * Updates from the GitHub releases (decision D68, replaces D43's in-app installer): the release's APK opens in the
 * browser, which downloads it, and the user installs it from there as any download. The app no longer holds the
 * "install other apps" permission or installs packages itself, which Play Protect weighs against sideloaded apps.
 */
@Singleton
class BrowserUpdater @Inject constructor(@ApplicationContext private val context: Context) : AppUpdater {
    private val status = MutableStateFlow<InstallState>(InstallState.Idle)
    private var last: UpdateCheck.Available? = null
    override val state: StateFlow<InstallState> = status

    override fun start(update: UpdateCheck.Available) {
        last = update
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(update.apkUrl ?: update.pageUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        status.value = runCatching { context.startActivity(intent) }
            .fold({ InstallState.Prompted() }, { InstallState.Failed("No browser to open the download") })
    }

    override fun install() {
        last?.let(::start)
    }

    override fun permissionSettings(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
