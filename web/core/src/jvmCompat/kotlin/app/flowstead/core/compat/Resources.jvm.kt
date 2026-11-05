// SPDX-License-Identifier: GPL-3.0-or-later
package app.flowstead.core.compat

internal actual fun platformResource(path: String): String? =
    Resources::class.java.getResourceAsStream("/$path")?.use { it.readBytes().decodeToString() }
