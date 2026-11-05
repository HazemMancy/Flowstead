/*
 * Flowstead web core (D109): resources bundled with the core (help pages, examples), on every platform.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 * On the JVM they come from the class path, as in android/core. In the browser the web app loads the
 * same files (one JSON bundle built from the repository) and hands them over with [provide] before use.
 */
package app.flowstead.core.compat

object Resources {
    private var provided: Map<String, String>? = null

    /** Called by the web app with path → text for every bundled file ("help/index.txt", …). */
    fun provide(files: Map<String, String>) { provided = files }

    fun read(path: String): String? = provided?.get(path) ?: platformResource(path)
}

internal expect fun platformResource(path: String): String?
