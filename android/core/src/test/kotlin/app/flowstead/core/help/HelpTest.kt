/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.help

import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.Specification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HelpTest {
    @Test
    fun everyAvailableUnitHasItsF1Page() {
        ObjectType.entries.filter { it.available }.forEach { assertNotNull(Help.forType(it), "no help page for $it") }
    }

    @Test
    fun everyLinkedMethodExistsInTheMethodsReference() {
        val methods = Help.methods.map { it.id }.toSet()
        assertTrue(methods.size > 30)
        HelpTopics.ALL.flatMap { it.methods }.forEach { assertTrue(it in methods, "$it is linked but not in the reference") }
        assertTrue(Help.methods.none { it.id == "D4" }) // build-and-ship rows are left out
    }

    @Test
    fun topicIdsAreUniqueAndSearchFindsTheVapourFractionPage() {
        assertEquals(Help.topics.size, Help.topics.map { it.id }.toSet().size)
        assertEquals("flash", Help.search("dew point").first().id)
        assertTrue(Help.search("Souders").any { it.id == "sep_sizing" })
        assertTrue(Help.search("").isEmpty())
    }

    @Test
    fun everyExampleLoadsLaidOutAndFullySpecified() {
        assertTrue(Help.examples.size >= 15)
        for (e in Help.examples) {
            val fs = assertNotNull(Help.example(e.id), e.id)
            assertTrue(e.name.isNotBlank())
            assertEquals(fs.objects.size, fs.objects.map { it.x to it.y }.distinct().size, "${e.id} overlaps")
            assertEquals(emptyList(), Specification.problems(fs), e.id)
        }
    }

    @Test
    fun releasesAreNewestFirst() {
        val versions = Help.releases.map { it.version }
        assertTrue("3.0.1" in versions && "3.0.2" in versions)
        assertEquals("3.0.2", versions.first())
    }
}
