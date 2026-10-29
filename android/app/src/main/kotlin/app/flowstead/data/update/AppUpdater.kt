/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.data.update

import android.app.DownloadManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.IntentCompat
import app.flowstead.BuildConfig
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
    data class Downloaded(val version: String, val downloads: Intent, val canInstall: Boolean = BuildConfig.DIRECT_INSTALL) : InstallState
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
 * permission, the pattern Play Protect blocked in 1.8.4 and 1.8.5 (D74). Without that permission Android lets no app
 * start an install itself, so the tap stays the user's. The file goes to the phone's Downloads folder (D85), where
 * Downloads and the Files app show it. Android still asks the user to confirm, and refuses an APK signed with any key
 * but the installed app's.
 */
@Singleton
class DownloadUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: HttpClient,
) : AppUpdater {
    private val status get() = installStatus
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var last: UpdateCheck.Available? = null
    /** The DownloadManager id of the checked download, for installing it (test builds only). */
    private var downloadedId: Long? = null
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
                removeEarlierDownloads()
                val request = DownloadManager.Request(Uri.parse(apkUrl))
                    .setTitle("Flowstead ${update.version}")
                    .setDescription("Tap to install the update")
                    .setMimeType(APK_MIME)
                    .addRequestHeader("User-Agent", USER_AGENT)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .apply {
                        // The phone's own Downloads folder, so the file is in Downloads (1.9.0); Android 9 and older
                        // would need the storage permission for it, so there it stays in the app's folder.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                        else {
                            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, name).delete() }
                            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, name)
                        }
                    }
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
                downloadedId = id
                status.value = InstallState.Downloaded(update.version, Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.message ?: "Download failed")
            }
        }
    }

    /** Earlier Flowstead updates this app downloaded, with their files, so the new one keeps its plain name. */
    private fun removeEarlierDownloads() {
        val ids = downloads.query(DownloadManager.Query()).use { c ->
            buildList {
                while (c.moveToNext()) {
                    if (c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE))?.startsWith("Flowstead ") == true) {
                        add(c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)))
                    }
                }
            }
        }
        if (ids.isNotEmpty()) downloads.remove(*ids.toLongArray())
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

    /**
     * Test builds (BuildConfig.DIRECT_INSTALL): hands the checked download to Android's PackageInstaller, which shows
     * its own "Update this app?" prompt. Releases can't (they hold no install permission): they download again.
     */
    override fun install() {
        val id = downloadedId
        if (!BuildConfig.DIRECT_INSTALL || id == null) {
            last?.let(::start)
            return
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            status.value = InstallState.NeedsPermission
            return
        }
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                .apply { setAppPackageName(context.packageName) }
            val session = installer.createSession(params)
            installer.openSession(session).use { s ->
                downloads.openDownloadedFile(id).use { pfd ->
                    FileInputStream(pfd.fileDescriptor).use { input ->
                        s.openWrite("flowstead.apk", 0, pfd.statSize).use { out ->
                            input.copyTo(out)
                            s.fsync(out)
                        }
                    }
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val callback = PendingIntent.getBroadcast(context, session, Intent(context, InstallResultReceiver::class.java), flags)
                s.commit(callback.intentSender)
            }
            status.value = InstallState.Prompted()
        } catch (e: Exception) {
            fail(e.message ?: "Android refused the installation")
        }
    }

    override fun permissionSettings(): Intent =
        (if (BuildConfig.DIRECT_INSTALL) Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val USER_AGENT = "Flowstead-updater"
    }
}

/** Shared with [InstallResultReceiver], which hears from Android's installer outside any injection scope. */
internal val installStatus = MutableStateFlow<InstallState>(InstallState.Idle)

/**
 * Android's installer reports here (test builds only; the receiver is declared by the directInstall manifest): it
 * needs the user's tap (opens its prompt), succeeded, or failed.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Kept too, so the screen can open it if Android blocks a start from the background.
                installStatus.value = InstallState.Prompted(confirm)
                runCatching { context.startActivity(confirm) }
            }
            PackageInstaller.STATUS_SUCCESS -> installStatus.value = InstallState.Idle
            PackageInstaller.STATUS_FAILURE_ABORTED -> installStatus.value = InstallState.Failed("Update cancelled")
            else -> installStatus.value = InstallState.Failed(
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Android could not install the update",
            )
        }
    }
}
