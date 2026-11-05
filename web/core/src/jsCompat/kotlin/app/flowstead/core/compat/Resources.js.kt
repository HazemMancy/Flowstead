// SPDX-License-Identifier: GPL-3.0-or-later
package app.flowstead.core.compat

/** In the browser there is no class path: everything comes through [Resources.provide]. */
internal actual fun platformResource(path: String): String? = null
