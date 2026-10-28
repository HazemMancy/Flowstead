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
 * The Flowstead mark (res/drawable/ic_flowstead_mark.xml, decision D64: two streams meet at a unit and leave as
 * one, teal on a navy tile with a white node) and the "Flowstead" wordmark, drawn with Canvas so documents carry
 * them as vectors.
 */
object FlowsteadLogo {
    private val tile = Color.rgb(0x0B, 0x19, 0x30)
    private val teal = Color.rgb(0x19, 0xBF, 0xAE)
    private val tealText = Color.rgb(0x0E, 0x7C, 0x72)
    private val navy = Color.rgb(0x0B, 0x19, 0x30)

    /** The mark in a [size] × [size] square at ([x], [y]). Same geometry as the 104-unit vector drawable. */
    fun drawMark(c: Canvas, x: Float, y: Float, size: Float) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        c.save()
        c.translate(x, y)
        c.scale(size / 104f, size / 104f)
        fill.color = tile
        c.drawRoundRect(RectF(0f, 0f, 104f, 104f), 24f, 24f, fill)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 10.5f
            color = teal
        }
        c.drawPath(
            Path().apply {
                moveTo(21f, 30f); lineTo(37f, 30f); quadTo(44f, 30f, 48f, 35f); lineTo(56f, 46f)
                moveTo(21f, 74f); lineTo(37f, 74f); quadTo(44f, 74f, 48f, 69f); lineTo(56f, 58f)
                moveTo(72.5f, 52f); lineTo(85f, 52f)
            },
            line,
        )
        fill.color = Color.WHITE
        c.drawCircle(61f, 52f, 8.5f, fill)
        c.restore()
    }

    /** Mark plus "Flowstead" ("Flow" navy, "stead" teal) to its right, [height] tall; returns the width used. */
    fun drawLockup(c: Canvas, x: Float, y: Float, height: Float): Float {
        drawMark(c, x, y, height)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = height * 0.58f
            letterSpacing = -0.02f
            color = navy
        }
        val tx = x + height * 1.25f
        val baseline = y + height * 0.70f
        c.drawText("Flow", tx, baseline, text)
        val dx = text.measureText("Flow")
        text.color = tealText
        c.drawText("stead", tx + dx, baseline, text)
        return tx + dx + text.measureText("stead") - x
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
