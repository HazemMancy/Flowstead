/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdatePromptTest {
    @Test
    fun showsThreeTimesPerVersionThenStops() {
        var seen = UpdatePrompt.Seen()
        repeat(3) { i ->
            seen = UpdatePrompt.next("1.4.0", seen) ?: error("shown ${i + 1}")
            assertEquals(i + 1, seen.count)
        }
        assertNull(UpdatePrompt.next("1.4.0", seen))
    }

    @Test
    fun aNewerVersionStartsANewCount() {
        assertEquals(UpdatePrompt.Seen("1.5.0", 1), UpdatePrompt.next("1.5.0", UpdatePrompt.Seen("1.4.0", 3)))
    }
}
