/*
 * Flowstead web (D110): the part of Compose's geometry that the app's canvas code (Icons.kt, CanvasGeometry.kt) uses,
 * so those files compile for the browser unchanged. Same names, signatures and arithmetic as androidx.compose.ui.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package androidx.compose.ui.geometry

import kotlin.math.sqrt

data class Offset(val x: Float, val y: Float) {
    operator fun plus(o: Offset) = Offset(x + o.x, y + o.y)
    operator fun minus(o: Offset) = Offset(x - o.x, y - o.y)
    operator fun times(k: Float) = Offset(x * k, y * k)
    operator fun div(k: Float) = Offset(x / k, y / k)
    operator fun unaryMinus() = Offset(-x, -y)
    fun getDistance(): Float = sqrt(x * x + y * y)

    companion object {
        val Zero = Offset(0f, 0f)
        val Unspecified = Offset(Float.NaN, Float.NaN)
    }
}

data class Size(val width: Float, val height: Float) {
    val minDimension: Float get() = minOf(width, height)
    val center: Offset get() = Offset(width / 2, height / 2)

    companion object { val Zero = Size(0f, 0f) }
}

data class CornerRadius(val x: Float, val y: Float = x) {
    companion object { val Zero = CornerRadius(0f) }
}

data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    constructor(offset: Offset, size: Size) : this(offset.x, offset.y, offset.x + size.width, offset.y + size.height)
    constructor(topLeft: Offset, bottomRight: Offset) : this(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)
    constructor(center: Offset, radius: Float) : this(center.x - radius, center.y - radius, center.x + radius, center.y + radius)

    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val size: Size get() = Size(width, height)
    val topLeft: Offset get() = Offset(left, top)
    val bottomRight: Offset get() = Offset(right, bottom)
    val center: Offset get() = Offset(left + width / 2, top + height / 2)

    fun inflate(delta: Float) = Rect(left - delta, top - delta, right + delta, bottom + delta)
    fun translate(offset: Offset) = Rect(left + offset.x, top + offset.y, right + offset.x, bottom + offset.y)
    /** As Compose: the left and top edges are inside, the right and bottom edges are not. */
    operator fun contains(offset: Offset): Boolean = offset.x >= left && offset.x < right && offset.y >= top && offset.y < bottom

    companion object { val Zero = Rect(0f, 0f, 0f, 0f) }
}
