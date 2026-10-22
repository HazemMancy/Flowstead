/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.data.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.IntentCompat
import app.flowstead.core.update.UpdateCheck
import app.flowstead.core.update.Updates
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
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
 * In-app updates from the GitHub releases (decision D43). The APK is downloaded into the app's cache in the
 * background (it carries on if the user leaves Settings), its SHA-256 is checked against the release's
 * published `.apk.sha256`, and its package name against the app's; then Android's PackageInstaller opens its
 * own "Update this app?" prompt. Android never lets an app update itself without that tap, and refuses an APK
 * signed with any other key than the installed app's.
 */
@Singleton
class UpdateInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: HttpClient,
) : AppUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var downloaded: File? = null

    override val state: StateFlow<InstallState> = status

    override fun start(update: UpdateCheck.Available) {
        if (job?.isActive == true) return
        val apkUrl = update.apkUrl ?: return fail("This release has no APK; use the browser download")
        val shaUrl = update.sha256Url ?: return fail("This release has no checksum; use the browser download")
        job = scope.launch {
            try {
                status.value = InstallState.Downloading(update.version, null)
                val expected = Updates.parseSha256(http.get(shaUrl) { header("User-Agent", USER_AGENT) }.bodyAsText())
                    ?: error("The release's checksum file is unreadable")
                val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
                val file = File(dir, "flowstead-${update.version}.apk")
                download(apkUrl, file, update.version)
                status.value = InstallState.Verifying
                if (sha256(file) != expected) {
                    file.delete()
                    error("The download is damaged (checksum mismatch); try again")
                }
                val info = context.packageManager.getPackageArchiveInfo(file.path, 0)
                if (info?.packageName != context.packageName) error("The file is not a Flowstead APK")
                downloaded = file
                install()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.message ?: "Download failed")
            }
        }
    }

    override fun install() {
        val file = downloaded?.takeIf { it.exists() } ?: return fail("Download the update first")
        if (!context.packageManager.canRequestPackageInstalls()) {
            status.value = InstallState.NeedsPermission
            return
        }
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                .apply { setAppPackageName(context.packageName) }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("flowstead.apk", 0, file.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val callback = PendingIntent.getBroadcast(context, id, Intent(context, InstallResultReceiver::class.java), flags)
                session.commit(callback.intentSender)
            }
            status.value = InstallState.Prompted()
        } catch (e: Exception) {
            fail(e.message ?: "Android refused the installation")
        }
    }

    override fun permissionSettings(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    private suspend fun download(url: String, file: File, version: String) {
        http.prepareGet(url) {
            header("User-Agent", USER_AGENT)
            // The APK is ~75 MB; the shared client's 2-minute request limit is for engine calls.
            timeout {
                requestTimeoutMillis = 30 * 60_000L
                socketTimeoutMillis = 60_000L
            }
        }.execute { response ->
            if (!response.status.isSuccess()) error("GitHub answered ${response.status.value}")
            val total = response.contentLength()?.takeIf { it > 0 }
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(64 * 1024)
            var done = 0L
            var shown = -1
            file.outputStream().use { out ->
                while (true) {
                    val n = channel.readAvailable(buffer, 0, buffer.size)
                    if (n < 0) break
                    if (n == 0) continue
                    out.write(buffer, 0, n)
                    done += n
                    val percent = total?.let { (done * 100 / it).toInt() } ?: -1
                    if (percent != shown) {
                        shown = percent
                        status.value = InstallState.Downloading(version, total?.let { done.toFloat() / it })
                    }
                }
            }
            if (total != null && done != total) error("The download stopped early; try again")
        }
    }

    private fun fail(reason: String) {
        status.value = InstallState.Failed(reason)
    }

    companion object {
        private const val USER_AGENT = "Flowstead (in-app update)"
        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** Shared with [InstallResultReceiver], which hears from Android's installer outside any injection scope. */
        internal val status = MutableStateFlow<InstallState>(InstallState.Idle)
    }
}

/** Android's installer reports here: it needs the user's tap (opens the prompt), succeeded, or failed. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Keep it too, so the screen can open it if Android blocks a start from the background.
                UpdateInstaller.status.value = InstallState.Prompted(confirm)
                runCatching { context.startActivity(confirm) }
            }
            PackageInstaller.STATUS_SUCCESS -> UpdateInstaller.status.value = InstallState.Idle
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateInstaller.status.value = InstallState.Failed("Update cancelled")
            else -> UpdateInstaller.status.value = InstallState.Failed(
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Android could not install the update",
            )
        }
    }
}
