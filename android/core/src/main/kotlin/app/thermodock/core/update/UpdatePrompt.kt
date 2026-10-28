/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.core.update

/**
 * The "update available" prompt shown when the app opens (decision D45): once per launch, and at most
 * [MAX_PER_VERSION] times for the same new version; a newer version starts a new count.
 */
object UpdatePrompt {
    const val MAX_PER_VERSION = 3

    /** The version last prompted for and how many times it was shown. */
    data class Seen(val version: String? = null, val count: Int = 0)

    /** What to remember after showing the prompt for [available], or null when it has been shown enough. */
    fun next(available: String, seen: Seen): Seen? = when {
        seen.version != available -> Seen(available, 1)
        seen.count >= MAX_PER_VERSION -> null
        else -> seen.copy(count = seen.count + 1)
    }
}
