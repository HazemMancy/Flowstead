/*
 * Flowstead web (D110): the part of Compose's graphics that the app's canvas code uses (colours, brushes, paths, stroke
 * styles), so Icons.kt compiles for the browser unchanged. Same names and signatures as androidx.compose.ui.graphics.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package androidx.compose.ui.graphics

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** An sRGB colour, components 0..1. */
class Color(val red: Float, val green: Float, val blue: Float, val alpha: Float = 1f) {
    /** 0xAARRGGBB, as Compose's Color(Long). */
    constructor(argb: Long) : this(
        ((argb shr 16) and 0xFF).toFloat() / 255f, ((argb shr 8) and 0xFF).toFloat() / 255f,
        (argb and 0xFF).toFloat() / 255f, ((argb shr 24) and 0xFF).toFloat() / 255f,
    )

    fun copy(alpha: Float = this.alpha, red: Float = this.red, green: Float = this.green, blue: Float = this.blue) = Color(red, green, blue, alpha)

    override fun equals(other: Any?) = other is Color && other.red == red && other.green == green && other.blue == blue && other.alpha == alpha
    override fun hashCode() = listOf(red, green, blue, alpha).hashCode()

    /** CSS colour, for the browser canvas. */
    fun css(): String {
        fun c(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return "rgba(${c(red)},${c(green)},${c(blue)},${alpha.coerceIn(0f, 1f)})"
    }

    companion object {
        val Black = Color(0xFF000000)
        val White = Color(0xFFFFFFFF)
        val Transparent = Color(0x00000000)
    }
}

/**
 * As Compose's lerp: interpolated in Oklab (perceptually even), then back to sRGB, so lit steel and its shading look
 * as they do in the app.
 */
fun lerp(start: Color, stop: Color, fraction: Float): Color {
    val a = start.oklab()
    val b = stop.oklab()
    fun mix(x: Float, y: Float) = x + (y - x) * fraction
    return fromOklab(mix(a[0], b[0]), mix(a[1], b[1]), mix(a[2], b[2]), mix(start.alpha, stop.alpha))
}

private fun toLinear(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
private fun toSrgb(c: Float): Float = if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f

private fun Color.oklab(): FloatArray {
    val r = toLinear(red); val g = toLinear(green); val b = toLinear(blue)
    val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
    val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
    val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
    return floatArrayOf(
        0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
        1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
        0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s,
    )
}

private fun fromOklab(L: Float, A: Float, B: Float, alpha: Float): Color {
    val l = (L + 0.3963377774f * A + 0.2158037573f * B).let { it * it * it }
    val m = (L - 0.1055613458f * A - 0.0638541728f * B).let { it * it * it }
    val s = (L - 0.0894841775f * A - 1.2914855480f * B).let { it * it * it }
    fun clamp(v: Float) = toSrgb(max(0f, min(1f, v))).coerceIn(0f, 1f)
    return Color(
        clamp(4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s),
        clamp(-1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s),
        clamp(-0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s),
        alpha,
    )
}

enum class StrokeCap { Butt, Round, Square }
enum class StrokeJoin { Miter, Round, Bevel }
enum class TileMode { Clamp }

class PathEffect private constructor(val intervals: FloatArray, val phase: Float) {
    companion object {
        fun dashPathEffect(intervals: FloatArray, phase: Float = 0f) = PathEffect(intervals, phase)
    }
}

sealed class Brush {
    companion object {
        private fun even(colors: List<Color>) = colors.mapIndexed { i, c -> (if (colors.size == 1) 0f else i.toFloat() / (colors.size - 1)) to c }

        fun horizontalGradient(colors: List<Color>, startX: Float = 0f, endX: Float = Float.POSITIVE_INFINITY, tileMode: TileMode = TileMode.Clamp): Brush =
            LinearGradient(even(colors), Offset(startX, 0f), Offset(endX, 0f))
        fun horizontalGradient(vararg colorStops: Pair<Float, Color>, startX: Float = 0f, endX: Float = Float.POSITIVE_INFINITY, tileMode: TileMode = TileMode.Clamp): Brush =
            LinearGradient(colorStops.toList(), Offset(startX, 0f), Offset(endX, 0f))
        fun verticalGradient(colors: List<Color>, startY: Float = 0f, endY: Float = Float.POSITIVE_INFINITY, tileMode: TileMode = TileMode.Clamp): Brush =
            LinearGradient(even(colors), Offset(0f, startY), Offset(0f, endY))
        fun verticalGradient(vararg colorStops: Pair<Float, Color>, startY: Float = 0f, endY: Float = Float.POSITIVE_INFINITY, tileMode: TileMode = TileMode.Clamp): Brush =
            LinearGradient(colorStops.toList(), Offset(0f, startY), Offset(0f, endY))
        fun linearGradient(colors: List<Color>, start: Offset = Offset.Zero, end: Offset = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY), tileMode: TileMode = TileMode.Clamp): Brush =
            LinearGradient(even(colors), start, end)
        fun radialGradient(colors: List<Color>, center: Offset = Offset.Unspecified, radius: Float = Float.POSITIVE_INFINITY, tileMode: TileMode = TileMode.Clamp): Brush =
            RadialGradient(even(colors), center, radius)
    }
}

class SolidColor(val value: Color) : Brush()
class LinearGradient internal constructor(val stops: List<Pair<Float, Color>>, val start: Offset, val end: Offset) : Brush()
class RadialGradient internal constructor(val stops: List<Pair<Float, Color>>, val center: Offset, val radius: Float) : Brush()

/** A path, kept as its commands so the browser canvas can replay it. */
class Path {
    sealed interface Op
    data class MoveTo(val x: Float, val y: Float) : Op
    data class LineTo(val x: Float, val y: Float) : Op
    data class QuadTo(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : Op
    data class Oval(val rect: Rect) : Op
    data object Close : Op

    val ops = mutableListOf<Op>()
    private var lastX = 0f
    private var lastY = 0f
    private var minX = Float.POSITIVE_INFINITY
    private var minY = Float.POSITIVE_INFINITY
    private var maxX = Float.NEGATIVE_INFINITY
    private var maxY = Float.NEGATIVE_INFINITY

    private fun include(x: Float, y: Float) {
        minX = min(minX, x); minY = min(minY, y); maxX = max(maxX, x); maxY = max(maxY, y)
    }

    fun moveTo(x: Float, y: Float) { ops += MoveTo(x, y); include(x, y); lastX = x; lastY = y }
    fun lineTo(x: Float, y: Float) { ops += LineTo(x, y); include(x, y); lastX = x; lastY = y }

    fun quadraticTo(x1: Float, y1: Float, x2: Float, y2: Float) {
        ops += QuadTo(x1, y1, x2, y2)
        // The curve's own extremes (not its control point), as an exact bounds would give.
        for (t in floatArrayOf(extremum(lastX, x1, x2), extremum(lastY, y1, y2))) if (t > 0f && t < 1f) {
            val u = 1 - t
            include(u * u * lastX + 2 * u * t * x1 + t * t * x2, u * u * lastY + 2 * u * t * y1 + t * t * y2)
        }
        include(x2, y2); lastX = x2; lastY = y2
    }

    private fun extremum(a: Float, b: Float, c: Float): Float = (a - 2 * b + c).let { d -> if (d == 0f) -1f else (a - b) / d }

    fun addOval(oval: Rect) { ops += Oval(oval); include(oval.left, oval.top); include(oval.right, oval.bottom) }
    fun close() { ops += Close }

    fun getBounds(): Rect = if (minX > maxX) Rect.Zero else Rect(minX, minY, maxX, maxY)
}
