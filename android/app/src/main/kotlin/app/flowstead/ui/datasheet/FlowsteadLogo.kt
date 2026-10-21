/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.datasheet

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import java.io.ByteArrayOutputStream

/**
 * The Flowstead mark (res/drawable/ic_flowstead_mark.xml: the letter F as a trayed column with overhead vapour
 * and a side draw) and the "Flowstead" wordmark, drawn with Canvas so documents carry them as vectors.
 */
object FlowsteadLogo {
    private val navy = Color.rgb(0x0B, 0x1F, 0x33)
    private val teal = Color.rgb(0x12, 0xA8, 0x9D)
    private val tealText = Color.rgb(0x0E, 0x7C, 0x74)
    private val tealLight = Color.rgb(0x7F, 0xDC, 0xD3)
    private val amber = Color.rgb(0xF2, 0xA3, 0x3A)

    /** The mark in a [size] × [size] square at ([x], [y]). Same geometry as the 120-unit vector drawable. */
    fun drawMark(c: Canvas, x: Float, y: Float, size: Float) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        c.save()
        c.translate(x, y)
        c.scale(size / 120f, size / 120f)
        fill.color = navy
        c.drawRoundRect(RectF(0f, 0f, 120f, 120f), 28f, 28f, fill)
        fill.color = teal
        c.drawRoundRect(RectF(28f, 20f, 52f, 100f), 12f, 12f, fill)
        stroke.color = navy
        stroke.strokeWidth = 3f
        for (ty in listOf(42f, 54f, 66f, 78f)) c.drawLine(34f, ty, 46f, ty, stroke)
        arrow(c, 50f, 80f, 33f, Triple(78f, 93f, 10f), amber, fill, stroke)
        arrow(c, 50f, 68f, 60f, Triple(66f, 79f, 9f), tealLight, fill, stroke)
        c.restore()
    }

    /** A thick rounded shaft from [x0] to [x1] at [y], with a triangular head (tail x, tip x, half height). */
    private fun arrow(c: Canvas, x0: Float, x1: Float, y: Float, head: Triple<Float, Float, Float>, color: Int, fill: Paint, stroke: Paint) {
        stroke.color = color
        stroke.strokeWidth = 10f
        c.drawLine(x0, y, x1, y, stroke)
        val (tail, tip, half) = head
        val p = Path().apply {
            moveTo(tail, y - half)
            lineTo(tip, y)
            lineTo(tail, y + half)
            close()
        }
        fill.color = color
        c.drawPath(p, fill)
        stroke.strokeWidth = 2f
        c.drawPath(p, stroke)
    }

    /** Mark plus "Flow" (navy) "stead" (teal) to its right, [height] tall; returns the width used. */
    fun drawLockup(c: Canvas, x: Float, y: Float, height: Float): Float {
        drawMark(c, x, y, height)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = height * 0.56f
        }
        val tx = x + height * 1.25f
        val baseline = y + height * 0.70f
        text.color = navy
        c.drawText("Flow", tx, baseline, text)
        val flowWidth = text.measureText("Flow")
        text.color = tealText
        c.drawText("stead", tx + flowWidth, baseline, text)
        return tx + flowWidth + text.measureText("stead") - x
    }

    /** The lockup as a PNG on a transparent background, for documents that take images (Excel). */
    fun png(heightPx: Int): Pair<ByteArray, IntArray> {
        val probe = Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        val width = (drawLockup(probe, 0f, 0f, heightPx.toFloat()) + heightPx * 0.1f).toInt()
        val bmp = Bitmap.createBitmap(width, heightPx, Bitmap.Config.ARGB_8888)
        drawLockup(Canvas(bmp), 0f, 0f, heightPx.toFloat())
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray() to intArrayOf(width, heightPx)
    }
}
