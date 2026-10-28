/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.data.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import app.flowstead.core.update.UpdateCheck
import app.flowstead.core.update.Updates
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

sealed interface InstallState {
    data object Idle : InstallState
    /** [fraction] is null while the size is unknown. */
    data class Downloading(val version: String, val fraction: Float?) : InstallState
    data object Verifying : InstallState
    /** Android needs the user to allow installs from Flowstead once (Settings → Install unknown apps). */
    data object NeedsPermission : InstallState
    /** Android's "Update this app?" prompt is up (or [confirm] opens it if it couldn't appear on its own). */
    data class Prompted(val confirm: Intent? = null) : InstallState
    /**
     * Downloaded and checked (D79): Android's "Download complete" notification installs it when tapped; [downloads]
     * opens Android's Downloads list, where the file is too.
     */
    data class Downloaded(val version: String, val downloads: Intent) : InstallState
    data class Failed(val reason: String) : InstallState
}

/** Downloads, verifies and hands a new release to Android's installer; an interface so tests needn't touch the network. */
interface AppUpdater {
    val state: StateFlow<InstallState>
    fun start(update: UpdateCheck.Available)
    /** Installs the downloaded file again, e.g. after the user allowed installs from Flowstead. */
    fun install()
    /** The system screen where the user allows installs from Flowstead. */
    fun permissionSettings(): Intent
}

/**
 * Updates inside the app without installing anything itself (decision D79; D68's browser download stays as the
 * fallback). Android's DownloadManager downloads the release APK with progress shown here and in a notification; its
 * SHA-256 is checked against the release's published `.apk.sha256`. Tapping Android's "Download complete" notification
 * then opens the installer: Android's download service hands it the file, so Flowstead needs no "install other apps"
 * permission, the pattern Play Protect blocked in 1.8.4 and 1.8.5 (D74). Android still asks the user to confirm, and
 * refuses an APK signed with any key but the installed app's.
 */
@Singleton
class DownloadUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: HttpClient,
) : AppUpdater {
    private val status = MutableStateFlow<InstallState>(InstallState.Idle)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var last: UpdateCheck.Available? = null
    override val state: StateFlow<InstallState> = status

    private val downloads get() = context.getSystemService(DownloadManager::class.java)

    override fun start(update: UpdateCheck.Available) {
        if (job?.isActive == true) return
        last = update
        val apkUrl = update.apkUrl ?: return fail("This release has no APK; use the browser download")
        val shaUrl = update.sha256Url ?: return fail("This release has no checksum; use the browser download")
        job = scope.launch {
            var id = -1L
            try {
                status.value = InstallState.Downloading(update.version, null)
                val expected = Updates.parseSha256(http.get(shaUrl) { header("User-Agent", USER_AGENT) }.bodyAsText())
                    ?: error("The release's checksum file is unreadable")
                val name = "flowstead-v${update.version}.apk"
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, name).delete() }
                val request = DownloadManager.Request(Uri.parse(apkUrl))
                    .setTitle("Flowstead ${update.version}")
                    .setDescription("Tap when finished to install the update")
                    .setMimeType(APK_MIME)
                    .addRequestHeader("User-Agent", USER_AGENT)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, name)
                id = downloads.enqueue(request)
                while (true) {
                    val (state, done, total) = query(id) ?: error("The download was cancelled")
                    when (state) {
                        DownloadManager.STATUS_SUCCESSFUL -> break
                        DownloadManager.STATUS_FAILED -> error("The download failed; check the connection and try again")
                        else -> status.value = InstallState.Downloading(update.version, total.takeIf { it > 0 }?.let { done.toFloat() / it })
                    }
                    delay(400)
                }
                status.value = InstallState.Verifying
                val actual = downloads.openDownloadedFile(id).use { pfd -> sha256(FileInputStream(pfd.fileDescriptor)) }
                if (actual != expected) {
                    downloads.remove(id)
                    error("The download is damaged (checksum mismatch); try again")
                }
                status.value = InstallState.Downloaded(update.version, Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.message ?: "Download failed")
            }
        }
    }

    /** (status, bytes so far, total bytes) of download [id], or null if it is gone. */
    private fun query(id: Long): Triple<Int, Long, Long>? =
        downloads.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) null
            else Triple(
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
            )
        }

    private fun sha256(input: java.io.InputStream): String = input.use { stream ->
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fail(reason: String) {
        status.value = InstallState.Failed(reason)
    }

    override fun install() {
        last?.let(::start)
    }

    override fun permissionSettings(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val USER_AGENT = "Flowstead-updater"
    }
}
