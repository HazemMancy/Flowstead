/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.datasheet

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.io.ByteArrayOutputStream

/**
 * The Flowstead mark (res/drawable/ic_flowstead_mark.xml, decision D37: one stream between two nodes, violet feed
 * to cyan product, on a dark rounded tile) and the "Flowstead" wordmark, drawn with Canvas so documents carry
 * them as vectors.
 */
object FlowsteadLogo {
    private val tile = Color.rgb(0x0E, 0x13, 0x22)
    private val violet = Color.rgb(0x8B, 0x5C, 0xF6)
    private val cyan = Color.rgb(0x22, 0xD3, 0xEE)
    private val ink = Color.rgb(0x11, 0x11, 0x13)

    /** The mark in a [size] × [size] square at ([x], [y]). Same geometry as the 104-unit vector drawable. */
    fun drawMark(c: Canvas, x: Float, y: Float, size: Float) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        c.save()
        c.translate(x, y)
        c.scale(size / 104f, size / 104f)
        fill.color = tile
        c.drawRoundRect(RectF(0f, 0f, 104f, 104f), 30f, 30f, fill)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 9f
            shader = LinearGradient(19f, 81f, 85f, 23f, violet, cyan, Shader.TileMode.CLAMP)
        }
        c.drawPath(
            Path().apply {
                moveTo(26f, 74f)
                cubicTo(26f, 44f, 50f, 40f, 52f, 52f)
                cubicTo(54f, 64f, 78f, 60f, 78f, 30f)
            },
            line,
        )
        fill.color = violet
        c.drawCircle(26f, 74f, 7f, fill)
        fill.color = cyan
        c.drawCircle(78f, 30f, 7f, fill)
        c.restore()
    }

    /** Mark plus "Flowstead" in ink to its right, [height] tall; returns the width used. */
    fun drawLockup(c: Canvas, x: Float, y: Float, height: Float): Float {
        drawMark(c, x, y, height)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = height * 0.58f
            letterSpacing = -0.03f
            color = ink
        }
        val tx = x + height * 1.25f
        c.drawText("Flowstead", tx, y + height * 0.70f, text)
        return tx + text.measureText("Flowstead") - x
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
