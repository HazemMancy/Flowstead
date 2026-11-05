/*
 * Flowstead web (D110): java.lang.Math as CanvasGeometry.kt (copied from the app) calls it. Same package, so the
 * copied file finds it without an import, as it finds java.lang on the JVM.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.flowsheet

import kotlin.math.floor

internal object Math {
    /** Java's Math.round(float): the nearest int, halves rounded up (towards positive infinity). */
    fun round(a: Float): Int = floor(a + 0.5f).toInt()
}
