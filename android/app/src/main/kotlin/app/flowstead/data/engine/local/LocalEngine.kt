/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.data.engine.local

import android.content.Context
import android.os.Build
import app.flowstead.BuildConfig
import app.flowstead.core.model.FlowsheetJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/** JNI entry points of libflowstead_host (engine/embedded/android/jni_bridge.c). */
internal object NativeEngine {
    @Volatile private var loaded = false

    fun load() {
        if (loaded) return
        synchronized(this) {
            if (!loaded) {
                System.loadLibrary("monosgen-2.0")
                System.loadLibrary("flowstead_host")
                loaded = true
            }
        }
    }

    @JvmStatic external fun start(framework: ByteArray, app: ByteArray, dwsim: ByteArray, natives: ByteArray): ByteArray?
    @JvmStatic external fun invoke(method: ByteArray, arg: ByteArray): ByteArray?
}

class LocalEngineException(message: String) : Exception(message)

/**
 * DWSIM running in this process on the phone's CPU (decision D25): the same engine the server runs,
 * reached through the embedded .NET runtime. The first call unpacks the engine from the APK (once
 * per version) and loads DWSIM, which takes a few seconds; later calls go straight to the solver.
 * Calls are serialised, as DWSIM keeps process-wide state.
 */
interface LocalEngine {
    /** True when this build carries the on-device engine. */
    val available: Boolean

    /** Calls a worker method (see engine WorkerMethods) and returns its result. */
    suspend fun call(method: String, params: JsonElement? = null): JsonElement

    /** DWSIM version once started, else null. */
    val dwsimVersion: String?
}

@Singleton
class EmbeddedLocalEngine @Inject constructor(@ApplicationContext private val context: Context) : LocalEngine {
    override val available: Boolean = BuildConfig.LOCAL_ENGINE
    private val lock = Mutex()
    private var started = false
    private var nextId = 1L

    @Volatile override var dwsimVersion: String? = null
        private set

    override suspend fun call(method: String, params: JsonElement?): JsonElement = lock.withLock {
        withContext(Dispatchers.Default) {
            ensureStarted()
            val request = buildJsonObject {
                put("id", JsonPrimitive(nextId++))
                put("method", JsonPrimitive(method))
                put("params", params ?: JsonNull)
            }
            val response = invoke("Call", request.toString())
            response["error"]?.jsonPrimitive?.contentOrNull?.let { throw LocalEngineException(it) }
            response["result"] ?: JsonNull
        }
    }

    private fun invoke(method: String, arg: String): JsonObject {
        val bytes = NativeEngine.invoke(method.toByteArray(), arg.toByteArray())
            ?: throw LocalEngineException("The on-device engine stopped responding")
        return FlowsheetJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    }

    private fun ensureStarted() {
        if (started) return
        if (!available) throw LocalEngineException("This build doesn't include the on-device engine")
        val abi = ABIS.firstOrNull { it in Build.SUPPORTED_ABIS } ?: throw LocalEngineException("This phone's processor isn't supported by the on-device engine")
        val root = unpack(abi)
        NativeEngine.load()
        val native = context.applicationInfo.nativeLibraryDir
        NativeEngine.start(
            File(root, "framework").path.toByteArray(),
            File(root, "app").path.toByteArray(),
            File(root, "dwsim").path.toByteArray(),
            "$native:${File(root, "dwsim").path}".toByteArray(),
        )?.let { throw LocalEngineException("The on-device engine didn't start: " + it.toString(Charsets.UTF_8)) }
        val ready = invoke("Init", File(root, "dwsim").path)
        ready["error"]?.jsonPrimitive?.contentOrNull?.let { throw LocalEngineException(it) }
        dwsimVersion = ready["result"]?.jsonObject?.get("dwsimVersion")?.jsonPrimitive?.contentOrNull
        started = true
    }

    /** Unpacks the engine from the APK into app storage, once per engine version. */
    private fun unpack(abi: String): File {
        val assets = context.assets
        val version = assets.open("engine/VERSION").bufferedReader().use { it.readText().trim() }
        val base = File(context.filesDir, "engine")
        val root = File(base, "$version-$abi")
        val done = File(root, ".complete")
        if (done.exists()) return root
        base.listFiles()?.forEach { if (it != root) it.deleteRecursively() } // earlier versions
        root.deleteRecursively()
        unzip("engine/dwsim.zip", root)
        unzip("engine/framework-$abi.zip", File(root, "framework"))
        done.writeText(version)
        return root
    }

    private fun unzip(asset: String, into: File) {
        into.mkdirs()
        ZipInputStream(context.assets.open(asset).buffered()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { e ->
                val out = File(into, e.name).canonicalFile
                if (!out.path.startsWith(into.canonicalPath)) throw IOException("Bad entry ${e.name}")
                if (e.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
            }
        }
    }

    private companion object {
        /** ABIs the engine is built for (engine/embedded/android/prepare.sh). */
        val ABIS = listOf("arm64-v8a", "x86_64")
    }
}
