/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** One kept copy of a project (D105): its name and flowsheet JSON as they were at [savedAt]. */
@Serializable
data class Backup(val projectId: String, val name: String, val savedAt: Long, val flowsheetJson: String)

/**
 * Automatic backups (D105), as DWSIM's backup recovery: while a project is edited, a copy of it is kept at most every
 * [interval] ms, the newest [keep] per project; deleting a project keeps its last state, so it can be recovered. Each copy
 * is a file in the app's private storage, separate from the database.
 */
interface BackupStore {
    fun keep(projectId: String, name: String, flowsheetJson: String, now: Long, force: Boolean = false)
    fun list(projectId: String): List<Backup>
    /** The newest backup of every project not in [existing]: projects deleted, or lost with their database row. */
    fun orphans(existing: Set<String>): List<Backup>
    fun forget(projectId: String)
}

class FileBackupStore(private val dir: File, private val interval: Long = 10 * 60_000L, private val keep: Int = 20) : BackupStore {
    private val json = Json { ignoreUnknownKeys = true }

    private fun folder(projectId: String) = File(dir, projectId.replace(Regex("[^A-Za-z0-9-]"), "_"))

    @Synchronized
    override fun keep(projectId: String, name: String, flowsheetJson: String, now: Long, force: Boolean) {
        val f = folder(projectId)
        val files = f.listFiles { x -> x.name.endsWith(".json") }?.sortedBy { it.name }.orEmpty()
        val last = files.lastOrNull()
        if (!force && last != null) {
            val lastTime = last.name.removeSuffix(".json").toLongOrNull() ?: 0
            if (now - lastTime < interval) return
            // Nothing changed since the last copy: no new one.
            if (runCatching { json.decodeFromString(Backup.serializer(), last.readText()).flowsheetJson == flowsheetJson }.getOrDefault(false)) return
        }
        f.mkdirs()
        File(f, "$now.json").writeText(json.encodeToString(Backup.serializer(), Backup(projectId, name, now, flowsheetJson)))
        (files + File(f, "$now.json")).distinctBy { it.name }.sortedBy { it.name }.dropLast(keep).forEach { it.delete() }
    }

    override fun list(projectId: String): List<Backup> =
        folder(projectId).listFiles { x -> x.name.endsWith(".json") }.orEmpty()
            .mapNotNull { runCatching { json.decodeFromString(Backup.serializer(), it.readText()) }.getOrNull() }
            .sortedByDescending { it.savedAt }

    override fun orphans(existing: Set<String>): List<Backup> =
        dir.listFiles { x -> x.isDirectory }.orEmpty().mapNotNull { d ->
            val newest = d.listFiles { x -> x.name.endsWith(".json") }?.maxByOrNull { it.name } ?: return@mapNotNull null
            runCatching { json.decodeFromString(Backup.serializer(), newest.readText()) }.getOrNull()?.takeIf { it.projectId !in existing }
        }.sortedByDescending { it.savedAt }

    @Synchronized
    override fun forget(projectId: String) {
        folder(projectId).deleteRecursively()
    }
}
