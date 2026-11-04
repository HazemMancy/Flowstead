/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.data

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackupStoreTest {
    private fun store(keep: Int = 3) = FileBackupStore(Files.createTempDirectory("backups").toFile(), interval = 600_000L, keep = keep)

    @Test
    fun keepsACopyEveryIntervalAndTheNewestFew() {
        val s = store(keep = 3)
        s.keep("p1", "Letdown", "{\"v\":1}", now = 1_000_000)
        s.keep("p1", "Letdown", "{\"v\":2}", now = 1_100_000) // within 10 min: not kept
        assertEquals(listOf("{\"v\":1}"), s.list("p1").map { it.flowsheetJson })
        s.keep("p1", "Letdown", "{\"v\":1}", now = 2_000_000) // unchanged since the last copy: not kept
        assertEquals(1, s.list("p1").size)
        for (k in 2..6) s.keep("p1", "Letdown", "{\"v\":$k}", now = 1_000_000L * (k + 1))
        val kept = s.list("p1")
        assertEquals(3, kept.size)
        assertEquals(listOf("{\"v\":6}", "{\"v\":5}", "{\"v\":4}"), kept.map { it.flowsheetJson }) // newest first
    }

    @Test
    fun deletedProjectsAreRecoverable() {
        val s = store()
        s.keep("gone", "Old case", "{}", now = 10, force = true)
        s.keep("here", "Live case", "{}", now = 20, force = true)
        val orphans = s.orphans(existing = setOf("here"))
        assertEquals(listOf("gone"), orphans.map { it.projectId })
        assertEquals("Old case", orphans.single().name)
        s.forget("gone")
        assertTrue(s.orphans(setOf("here")).isEmpty())
    }
}
